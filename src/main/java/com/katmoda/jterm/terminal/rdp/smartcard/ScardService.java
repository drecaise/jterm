package com.katmoda.jterm.terminal.rdp.smartcard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One connection's smart-card redirection: decodes each call ({@link ScardNdr}), applies the
 * policy ({@link ScardPolicy}) and consent, and runs it against the system's PC/SC ({@link Pcsc}).
 * Swing-free; the questions go through {@link Ui}, which may block (it is never called on the
 * thread that delivers requests).
 *
 * <p><b>Handles are jterm's.</b> The server gets random 8-byte values that map to the system's
 * contexts and card handles inside this connection; a guessed or stale value is
 * {@link Scard#E_INVALID_HANDLE}. <b>Threads</b>, as FreeRDP arranges them: each context has a
 * queue whose calls run in order (a status change may block for ever), and a second one for the
 * calls that may wait for another handle's transaction (BeginTransaction, Connect, Reconnect), so
 * that the EndTransaction freeing them is never stuck behind them. Cancel, IsValidContext and
 * ReleaseContext bypass both. Everything the server can
 * make jterm hold is capped (S7), and {@link #close} releases it all, resetting the cards.
 *
 * <p>Never log a call's contents: a VERIFY carries the PIN.
 */
public final class ScardService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ScardService.class);

    /** The questions and notices; implemented by the pane (dialogs through dialogParent()). */
    public interface Ui {
        /** May block until the user answers. True: allow the remote desktop to use cards this connection. */
        boolean allowCards(String reader);

        /** May block. True: let the remote desktop try the PIN again with one try left. */
        boolean allowLastPinTry(String reader);

        /** A card was blocked by wrong PINs. */
        void pinBlocked(String reader);

        /** The number of open card handles changed (the bar's card button). */
        void cardsInUse(int open);
    }

    static final int MAX_CONTEXTS = 32;
    static final int MAX_CARDS = 8;
    static final int MAX_CACHE_ENTRIES = 64;
    static final int MAX_CACHE_ITEM = 64 * 1024;
    /** How often readers are looked for when PC/SC can't watch the PnP pseudo-reader (macOS). */
    static final int PNP_POLL_MS = 1000;
    /** How often one status change lists the readers again after a listed reader proved unknown. */
    static final int MAX_VANISHED_RETRIES = 3;
    private static final int AUTOALLOCATE = 0xFFFFFFFF;

    public enum Consent { UNASKED, ALLOWED, DENIED }

    private final Pcsc pcsc;
    private final List<String> picks;
    private final Ui ui;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService immediate;

    private final Map<Long, Ctx> contexts = new HashMap<>();
    private final Map<Long, Card> cards = new HashMap<>();
    private final Map<String, CacheItem> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, ScardPolicy.PinGuard> pinGuards = new HashMap<>();
    private final Object consentLock = new Object();
    private Consent consent = Consent.UNASKED;
    private boolean asking;
    private volatile boolean closed;

    private static final class Ctx {
        final long real;
        final ExecutorService thread;
        final ExecutorService txThread;
        volatile boolean cancelled;

        Ctx(long real) {
            this.real = real;
            this.thread = Executors.newSingleThreadExecutor(daemon("rdp-scard-ctx"));
            this.txThread = Executors.newSingleThreadExecutor(daemon("rdp-scard-tx"));
        }

        void shutdown() {
            thread.shutdown();
            txThread.shutdown();
        }
    }

    private static final class Card {
        final long ctx;
        final long real;
        final String reader;
        volatile int protocol;
        volatile int transmits;
        volatile Set<Integer> featureCodes = Set.of();

        Card(long ctx, long real, String reader, int protocol) {
            this.ctx = ctx;
            this.real = real;
            this.reader = reader;
            this.protocol = protocol;
        }
    }

    private record CacheItem(int freshness, byte[] data) {
    }

    public ScardService(Pcsc pcsc, List<String> picks, Ui ui) {
        this.pcsc = pcsc;
        this.picks = List.copyOf(picks);
        this.ui = ui;
        this.immediate = Executors.newCachedThreadPool(daemon("rdp-scard"));
    }

    // ---- entry point ----

    /**
     * Handles one device-control request (the bytes after the device I/O header). {@code done} is
     * called exactly once, on some service thread, possibly much later (a status change waits).
     */
    public void submit(byte[] input, Consumer<ScardNdr.Reply> done) {
        ScardNdr.Request req;
        try {
            req = ScardNdr.decode(input);
        } catch (NdrReader.Malformed m) {
            LOG.debug("Smart-card call refused: {}", m.getMessage());
            done.accept(ScardNdr.malformed());
            return;
        }
        ScardNdr.Call call = req.call();
        Runnable task = () -> done.accept(run(req));
        try {
            if (closed) {
                done.accept(ScardNdr.reply(call.ioctl(), req.outputBufferLength(), Scard.E_SERVICE_STOPPED,
                        ScardNdr.empty()));
                return;
            }
            Ctx ctx = queueFor(call);
            if (ctx == null) {
                immediate.execute(task);
            } else if (call instanceof ScardNdr.Connect || call instanceof ScardNdr.Reconnect
                    || call.ioctl() == Scard.IOCTL_BEGINTRANSACTION) {
                ctx.txThread.execute(task); // may wait for another handle's transaction
            } else {
                ctx.thread.execute(task);
            }
        } catch (RejectedExecutionException e) {
            // The context was released (or the service closed) while this call arrived.
            done.accept(ScardNdr.reply(call.ioctl(), req.outputBufferLength(), Scard.E_INVALID_HANDLE,
                    ScardNdr.empty()));
        }
    }

    /** The context whose thread runs {@code call}, or null for calls answered at once. */
    private synchronized Ctx queueFor(ScardNdr.Call call) {
        long id = switch (call) {
            case ScardNdr.ContextCall c -> 0; // Cancel, IsValidContext, ReleaseContext: never queued
            case ScardNdr.ListReaderGroups c -> c.context();
            case ScardNdr.ListReaders c -> c.context();
            case ScardNdr.GetStatusChange c -> c.context();
            case ScardNdr.LocateCardsByAtr c -> c.context();
            case ScardNdr.Connect c -> c.context();
            case ScardNdr.Reconnect c -> c.context();
            case ScardNdr.CardDisposition c -> c.context();
            case ScardNdr.State c -> c.context();
            case ScardNdr.Status c -> c.context();
            case ScardNdr.Transmit c -> c.context();
            case ScardNdr.Control c -> c.context();
            case ScardNdr.GetAttrib c -> c.context();
            case ScardNdr.ReadCache c -> c.context();
            case ScardNdr.WriteCache c -> c.context();
            case ScardNdr.GetTransmitCount c -> c.context();
            case ScardNdr.ContextAndReader c -> c.context();
            default -> 0;
        };
        return id == 0 ? null : contexts.get(id);
    }

    private ScardNdr.Reply run(ScardNdr.Request req) {
        ScardNdr.Call call = req.call();
        Result r;
        try {
            r = dispatch(call);
        } catch (RuntimeException e) {
            LOG.warn("Smart-card {} failed: {}", Scard.ioctlName(call.ioctl()), e.toString());
            r = new Result(Scard.F_INTERNAL_ERROR, ScardNdr.empty());
        }
        return ScardNdr.reply(call.ioctl(), req.outputBufferLength(), r.rc, r.body);
    }

    private record Result(int rc, byte[] body) {
        static Result of(int rc) {
            return new Result(rc, ScardNdr.empty());
        }
    }

    private Result dispatch(ScardNdr.Call call) {
        return switch (call) {
            case ScardNdr.AccessStartedEvent c -> Result.of(Scard.S_SUCCESS);
            case ScardNdr.Refused c -> Result.of(Scard.E_UNSUPPORTED_FEATURE);
            case ScardNdr.EstablishContext c -> establish(c);
            case ScardNdr.ContextCall c -> switch (c.ioctl()) {
                case Scard.IOCTL_RELEASECONTEXT -> releaseContext(c.context());
                case Scard.IOCTL_CANCEL -> cancel(c.context());
                default -> Result.of(isValid(c.context()));
            };
            case ScardNdr.ListReaderGroups c -> ctx(c.context()) == null ? Result.of(Scard.E_INVALID_HANDLE)
                    : new Result(Scard.S_SUCCESS, ScardNdr.multiStringReturn(
                    ScardNdr.multiString(List.of("SCard$DefaultReaders"), c.unicode())));
            case ScardNdr.ListReaders c -> listReaders(c);
            case ScardNdr.GetStatusChange c -> statusChange(c.context(), c.timeout(), c.states(), null);
            case ScardNdr.LocateCardsByAtr c -> statusChange(c.context(), 0, c.states(), c.masks());
            case ScardNdr.Connect c -> connect(c);
            case ScardNdr.Reconnect c -> reconnect(c);
            case ScardNdr.CardDisposition c -> cardDisposition(c);
            case ScardNdr.State c -> state(c);
            case ScardNdr.Status c -> status(c);
            case ScardNdr.Transmit c -> transmit(c);
            case ScardNdr.Control c -> control(c);
            case ScardNdr.GetAttrib c -> getAttrib(c);
            case ScardNdr.ReadCache c -> readCache(c);
            case ScardNdr.WriteCache c -> writeCache(c);
            case ScardNdr.GetTransmitCount c -> transmitCount(c);
            case ScardNdr.ContextAndReader c -> contextAndReader(c);
        };
    }

    // ---- contexts ----

    private Result establish(ScardNdr.EstablishContext c) {
        synchronized (this) {
            if (contexts.size() >= MAX_CONTEXTS) {
                return Result.of(Scard.E_NO_MEMORY);
            }
        }
        Pcsc.Context real = pcsc.establish(c.scope());
        if (real.rc() != Scard.S_SUCCESS) {
            return Result.of(real.rc());
        }
        long id;
        synchronized (this) {
            if (closed || contexts.size() >= MAX_CONTEXTS) {
                pcsc.release(real.handle());
                return Result.of(closed ? Scard.E_SERVICE_STOPPED : Scard.E_NO_MEMORY);
            }
            id = newId(contexts);
            contexts.put(id, new Ctx(real.handle()));
        }
        return new Result(Scard.S_SUCCESS, ScardNdr.contextReturn(id));
    }

    /**
     * Answered at once, as FreeRDP does: the context disappears for the server now, its waits are
     * cancelled, and the system context is released on its own queue once what runs there returns
     * (PC/SC calls on one context stay on one thread).
     */
    private Result releaseContext(long id) {
        Ctx ctx;
        List<Card> its = new ArrayList<>();
        synchronized (this) {
            ctx = contexts.remove(id);
            if (ctx == null) {
                return Result.of(Scard.E_INVALID_HANDLE);
            }
            cards.values().removeIf(card -> {
                if (card.ctx == id) {
                    its.add(card);
                    return true;
                }
                return false;
            });
        }
        ctx.cancelled = true;
        pcsc.cancel(ctx.real);
        try {
            ctx.thread.execute(() -> {
                for (Card card : its) {
                    pcsc.disconnect(card.real, Scard.RESET_CARD);
                }
                pcsc.release(ctx.real);
            });
        } catch (RejectedExecutionException e) {
            // closing: close() releases it
        }
        ctx.shutdown(); // what is queued still runs (and finds no context); nothing new is taken
        if (!its.isEmpty()) {
            ui.cardsInUse(openCards());
        }
        return Result.of(Scard.S_SUCCESS);
    }

    private Result cancel(long id) {
        Ctx ctx = ctx(id);
        if (ctx == null) {
            return Result.of(Scard.E_INVALID_HANDLE);
        }
        ctx.cancelled = true;
        return Result.of(pcsc.cancel(ctx.real));
    }

    private int isValid(long id) {
        Ctx ctx = ctx(id);
        return ctx == null ? Scard.E_INVALID_HANDLE : pcsc.isValid(ctx.real);
    }

    private synchronized Ctx ctx(long id) {
        return contexts.get(id);
    }

    // ---- readers ----

    private List<String> visibleReaders(long realCtx, int[] rcOut) {
        Pcsc.Readers r = pcsc.listReaders(realCtx);
        rcOut[0] = r.rc();
        return r.rc() == Scard.S_SUCCESS ? ScardPolicy.visible(r.names(), picks) : List.of();
    }

    private Result listReaders(ScardNdr.ListReaders c) {
        Ctx ctx = ctx(c.context());
        if (ctx == null) {
            return Result.of(Scard.E_INVALID_HANDLE);
        }
        int[] rc = new int[1];
        List<String> names = visibleReaders(ctx.real, rc);
        if (rc[0] != Scard.S_SUCCESS && rc[0] != Scard.E_NO_READERS_AVAILABLE) {
            return new Result(rc[0], ScardNdr.multiStringReturn(new byte[0]));
        }
        if (names.isEmpty()) {
            // The same answer whether no reader exists or only unpicked ones do (S1).
            return new Result(Scard.E_NO_READERS_AVAILABLE, ScardNdr.multiStringReturn(new byte[0]));
        }
        return new Result(Scard.S_SUCCESS, ScardNdr.multiStringReturn(ScardNdr.multiString(names, c.unicode())));
    }

    /**
     * GetStatusChange (and LocateCardsByATR, {@code masks} non-null, timeout 0). Readers the server
     * may not see behave like readers that don't exist; the PnP pseudo-reader reports the number of
     * visible readers only, so plugging in an unpicked reader tells the server nothing (S1).
     */
    private Result statusChange(long id, int timeout, List<ScardNdr.ReaderState> states, List<ScardNdr.AtrMask> masks) {
        Ctx ctx = ctx(id);
        if (ctx == null) {
            return Result.of(Scard.E_INVALID_HANDLE);
        }
        ctx.cancelled = false;
        long deadline = timeout == Scard.INFINITE ? Long.MAX_VALUE
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Integer.toUnsignedLong(timeout));
        int n = states.size();
        int[] event = new int[n];
        byte[][] atr = new byte[n][];
        for (int i = 0; i < n; i++) {
            event[i] = states.get(i).eventState();
            atr[i] = states.get(i).atr();
        }
        int pnpReal = -1; // the system's reader count as the PnP slot last saw it
        int vanishedRetries = 0;
        while (true) {
            int[] lrc = new int[1];
            List<String> visible = visibleReaders(ctx.real, lrc);
            List<Pcsc.StatusSlot> slots = new ArrayList<>();
            int[] slotOf = new int[n];
            boolean changed = false;
            Pcsc.StatusSlot pnpSlot = null;
            boolean watchPnp = false;
            for (int i = 0; i < n; i++) {
                ScardNdr.ReaderState s = states.get(i);
                slotOf[i] = -1;
                if (s.reader().equals(Scard.PNP_NOTIFICATION)) {
                    int seen = s.currentState() >>> 16;
                    if (seen != visible.size()) {
                        event[i] = Scard.STATE_CHANGED | (visible.size() << 16);
                        changed = true;
                    } else {
                        event[i] = s.currentState() & ~Scard.STATE_CHANGED;
                        watchPnp = true;
                        if (pcsc.supportsPnpNotification()) {
                            if (pnpReal < 0) {
                                pnpReal = systemReaderCount(ctx.real);
                            }
                            pnpSlot = new Pcsc.StatusSlot(Scard.PNP_NOTIFICATION, pnpReal << 16);
                        }
                    }
                } else if (visible.contains(s.reader())) {
                    slotOf[i] = slots.size();
                    slots.add(new Pcsc.StatusSlot(s.reader(), s.currentState()));
                } else if ((s.currentState() & Scard.STATE_UNKNOWN) == 0 && (s.currentState() & Scard.STATE_IGNORE) == 0) {
                    event[i] = Scard.STATE_UNKNOWN | Scard.STATE_CHANGED;
                    atr[i] = new byte[0];
                    changed = true;
                } else {
                    event[i] = s.currentState() & ~Scard.STATE_CHANGED;
                }
            }
            if (pnpSlot != null) {
                slots.add(pnpSlot);
            }
            long left = deadline == Long.MAX_VALUE ? -1 : Math.max(0, deadline - System.nanoTime());
            int wait = changed || masks != null ? 0
                    : left < 0 ? Scard.INFINITE : (int) Math.min(TimeUnit.NANOSECONDS.toMillis(left), 0x7FFFFFFF);
            // Without PnP support, watching for readers means looking again every second.
            boolean polling = watchPnp && pnpSlot == null && wait != 0;
            if (polling && Integer.compareUnsigned(wait, PNP_POLL_MS) > 0) {
                wait = PNP_POLL_MS;
            }
            int rc;
            if (slots.isEmpty()) {
                rc = changed ? Scard.S_SUCCESS : sleepCancellable(ctx, wait);
            } else {
                rc = pcsc.getStatusChange(ctx.real, wait, slots.toArray(Pcsc.StatusSlot[]::new));
                if (rc == Scard.E_TIMEOUT && changed) {
                    rc = Scard.S_SUCCESS;
                }
            }
            if (rc == Scard.E_UNKNOWN_READER && vanishedRetries < MAX_VANISHED_RETRIES && !ctx.cancelled && !closed) {
                // winscard and pcsc-lite fail the whole call when one name is unknown, not just its
                // slot: a reader unplugged between the listing above and the call. List again, and
                // it is reported UNKNOWN like any reader that isn't there. Bounded, in case the
                // system keeps listing a reader it then calls unknown.
                vanishedRetries++;
                continue;
            }
            if (rc != Scard.S_SUCCESS && rc != Scard.E_TIMEOUT) {
                return new Result(rc, ScardNdr.readerStatesReturn(List.of()));
            }
            if (polling && rc == Scard.E_TIMEOUT && System.nanoTime() < deadline && !ctx.cancelled && !closed) {
                continue; // look at the reader list again
            }
            for (int i = 0; i < n; i++) {
                if (slotOf[i] >= 0) {
                    Pcsc.StatusSlot sl = slots.get(slotOf[i]);
                    event[i] = sl.eventState;
                    atr[i] = sl.atr;
                    changed |= (sl.eventState & Scard.STATE_CHANGED) != 0;
                }
            }
            if (pnpSlot != null && (pnpSlot.eventState & Scard.STATE_CHANGED) != 0 && !changed) {
                // Some reader came or went; tell the server only if a visible one did.
                pnpReal = pnpSlot.eventState >>> 16;
                if (ctx.cancelled || closed) {
                    rc = Scard.E_CANCELLED;
                    return new Result(rc, ScardNdr.readerStatesReturn(List.of()));
                }
                continue;
            }
            if (masks != null) {
                rc = Scard.S_SUCCESS; // a poll: "nothing changed" is not a timeout here
                for (int i = 0; i < n; i++) {
                    for (ScardNdr.AtrMask m : masks) {
                        if (atrMatches(m, atr[i])) {
                            event[i] |= Scard.STATE_ATRMATCH;
                        }
                    }
                }
            }
            List<ScardNdr.ReaderStateOut> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new ScardNdr.ReaderStateOut(states.get(i).currentState(), event[i], atr[i]));
            }
            return new Result(rc, ScardNdr.readerStatesReturn(out));
        }
    }

    private int systemReaderCount(long realCtx) {
        Pcsc.Readers r = pcsc.listReaders(realCtx);
        return r.rc() == Scard.S_SUCCESS ? r.names().size() : 0;
    }

    private static boolean atrMatches(ScardNdr.AtrMask m, byte[] atr) {
        if (m.atr().length != atr.length) {
            return false;
        }
        for (int k = 0; k < atr.length; k++) {
            if ((m.atr()[k] & m.mask()[k]) != (atr[k] & m.mask()[k])) {
                return false;
            }
        }
        return true;
    }

    /** A wait with nothing for PC/SC to watch: until the timeout, a Cancel or the end. */
    private int sleepCancellable(Ctx ctx, int ms) {
        long until = ms == Scard.INFINITE ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Integer.toUnsignedLong(ms));
        while (!ctx.cancelled && !closed) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                return Scard.E_TIMEOUT;
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Scard.E_CANCELLED;
            }
        }
        return Scard.E_CANCELLED;
    }

    // ---- cards ----

    private Result connect(ScardNdr.Connect c) {
        Ctx ctx = ctx(c.context());
        byte[] none = ScardNdr.connectReturn(0, 0, 0);
        if (ctx == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        int[] lrc = new int[1];
        if (!visibleReaders(ctx.real, lrc).contains(c.reader())) {
            return new Result(Scard.E_UNKNOWN_READER, none);
        }
        int share = ScardPolicy.shareMode(c.shareMode());
        if (share < 0) {
            return new Result(Scard.E_SHARING_VIOLATION, none); // no direct (cardless) reader access
        }
        synchronized (this) {
            if (cards.size() >= MAX_CARDS) {
                return new Result(Scard.E_NO_MEMORY, none);
            }
        }
        if (!consented(c.reader())) {
            return new Result(Scard.E_NO_SMARTCARD, none);
        }
        Pcsc.Connected k = pcsc.connect(ctx.real, c.reader(), share, ScardPolicy.protocols(c.protocols()));
        if (k.rc() != Scard.S_SUCCESS) {
            return new Result(k.rc(), none);
        }
        long id;
        synchronized (this) {
            if (closed || !contexts.containsKey(c.context()) || consent != Consent.ALLOWED) {
                pcsc.disconnect(k.card(), Scard.RESET_CARD);
                return new Result(closed ? Scard.E_SERVICE_STOPPED : Scard.E_NO_SMARTCARD, none);
            }
            id = newId(cards);
            cards.put(id, new Card(c.context(), k.card(), c.reader(), k.protocol()));
        }
        ui.cardsInUse(openCards());
        return new Result(Scard.S_SUCCESS, ScardNdr.connectReturn(c.context(), id, k.protocol()));
    }

    /** Asks once per connection; concurrent first connects wait for the same answer. */
    private boolean consented(String reader) {
        synchronized (consentLock) {
            while (asking) {
                try {
                    consentLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (consent != Consent.UNASKED) {
                return consent == Consent.ALLOWED;
            }
            asking = true;
        }
        boolean yes = false;
        try {
            yes = !closed && ui.allowCards(reader);
        } finally {
            synchronized (consentLock) {
                asking = false;
                synchronized (this) {
                    if (consent == Consent.UNASKED) {
                        consent = yes ? Consent.ALLOWED : Consent.DENIED;
                    }
                }
                consentLock.notifyAll();
            }
        }
        synchronized (this) {
            return consent == Consent.ALLOWED;
        }
    }

    private synchronized Card card(long ctxId, long cardId) {
        Card card = cards.get(cardId);
        return card != null && card.ctx == ctxId ? card : null;
    }

    private Result reconnect(ScardNdr.Reconnect c) {
        Card card = card(c.context(), c.card());
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, ScardNdr.u32Return(0));
        }
        int share = ScardPolicy.shareMode(c.shareMode());
        if (share < 0) {
            return new Result(Scard.E_SHARING_VIOLATION, ScardNdr.u32Return(0));
        }
        int init = ScardPolicy.disposition(c.initialization());
        Pcsc.IntResult r = pcsc.reconnect(card.real, share, ScardPolicy.protocols(c.protocols()), init);
        if (r.rc() == Scard.S_SUCCESS) {
            card.protocol = r.value();
            if (init != Scard.LEAVE_CARD) {
                guard(card.reader).cardReset();
            }
        }
        return new Result(r.rc(), ScardNdr.u32Return(r.value()));
    }

    private Result cardDisposition(ScardNdr.CardDisposition c) {
        Card card = card(c.context(), c.card());
        if (card == null) {
            return Result.of(Scard.E_INVALID_HANDLE);
        }
        int disposition = ScardPolicy.disposition(c.disposition());
        return switch (c.ioctl()) {
            case Scard.IOCTL_BEGINTRANSACTION -> Result.of(pcsc.beginTransaction(card.real));
            case Scard.IOCTL_ENDTRANSACTION -> {
                if (disposition != Scard.LEAVE_CARD) {
                    guard(card.reader).cardReset();
                }
                yield Result.of(pcsc.endTransaction(card.real, disposition));
            }
            default -> {
                synchronized (this) {
                    cards.remove(c.card());
                }
                int rc = pcsc.disconnect(card.real, disposition);
                if (disposition != Scard.LEAVE_CARD) {
                    guard(card.reader).cardReset();
                }
                ui.cardsInUse(openCards());
                yield Result.of(rc);
            }
        };
    }

    private Result state(ScardNdr.State c) {
        Card card = card(c.context(), c.card());
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, ScardNdr.stateReturn(0, 0, new byte[0]));
        }
        Pcsc.CardStatus s = pcsc.status(card.real);
        return new Result(s.rc(), s.rc() == Scard.S_SUCCESS ? ScardNdr.stateReturn(s.state(), s.protocol(), s.atr())
                : ScardNdr.stateReturn(0, 0, new byte[0]));
    }

    private Result status(ScardNdr.Status c) {
        Card card = card(c.context(), c.card());
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, ScardNdr.statusReturn(new byte[0], 0, 0, new byte[0]));
        }
        Pcsc.CardStatus s = pcsc.status(card.real);
        if (s.rc() != Scard.S_SUCCESS) {
            return new Result(s.rc(), ScardNdr.statusReturn(new byte[0], 0, 0, new byte[0]));
        }
        // The name the server connected with, never another the system might report.
        byte[] names = c.namesNull() ? new byte[0] : ScardNdr.multiString(List.of(card.reader), c.unicode());
        return new Result(s.rc(), ScardNdr.statusReturn(names, s.state(), s.protocol(), s.atr()));
    }

    private Result transmit(ScardNdr.Transmit c) {
        Card card = card(c.context(), c.card());
        byte[] none = ScardNdr.transmitReturn(c.recvPci(), new byte[0]);
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        ScardPolicy.PinGuard guard = guard(card.reader);
        if (guard.mustAsk(c.send()) && !ui.allowLastPinTry(card.reader)) {
            // As if the card refused: "security status not satisfied", no try used.
            byte[] refused = {0x69, (byte) 0x82};
            return new Result(Scard.S_SUCCESS, ScardNdr.transmitReturn(c.recvPci(), refused));
        }
        int recvMax = c.recvNull() ? 0 : c.recvLength();
        Pcsc.Transmitted t = pcsc.transmit(card.real, c.sendPci().protocol(), c.send(), recvMax);
        card.transmits++;
        if (t.rc() != Scard.S_SUCCESS) {
            return new Result(t.rc(), none);
        }
        if (guard.observe(c.send(), t.response()) == ScardPolicy.PinOutcome.BLOCKED) {
            ui.pinBlocked(card.reader);
        }
        ScardNdr.Pci recvPci = c.recvPci() == null ? null : new ScardNdr.Pci(t.recvProtocol(), c.recvPci().extra());
        return new Result(Scard.S_SUCCESS, ScardNdr.transmitReturn(recvPci, t.response()));
    }

    private Result control(ScardNdr.Control c) {
        Card card = card(c.context(), c.card());
        byte[] none = ScardNdr.bufferReturn(new byte[0]);
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        boolean features = c.code() == ScardPolicy.GET_FEATURE_REQUEST;
        if (!features && !card.featureCodes.contains(c.code())) {
            return new Result(Scard.E_UNSUPPORTED_FEATURE, none); // S3: only PIN-pad codes
        }
        Pcsc.Bytes r = pcsc.control(card.real, c.code(), c.in(), c.outNull() ? 0 : c.outLength());
        if (r.rc() != Scard.S_SUCCESS) {
            return new Result(r.rc(), none);
        }
        byte[] data = r.data();
        if (features) {
            data = ScardPolicy.filterFeatures(data);
            card.featureCodes = ScardPolicy.featureCodes(data);
        }
        return new Result(Scard.S_SUCCESS, ScardNdr.bufferReturn(data));
    }

    private Result getAttrib(ScardNdr.GetAttrib c) {
        Card card = card(c.context(), c.card());
        byte[] none = ScardNdr.bufferReturn(new byte[0]);
        if (card == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        if (!ScardPolicy.attributeAllowed(c.attrId())) {
            return new Result(Scard.E_UNSUPPORTED_FEATURE, none);
        }
        byte[] data;
        if (ScardPolicy.NAME_ATTRIBUTES.contains(c.attrId())) {
            boolean wide = c.attrId() == ScardPolicy.ATTR_DEVICE_FRIENDLY_NAME_W
                    || c.attrId() == ScardPolicy.ATTR_DEVICE_SYSTEM_NAME_W;
            data = (card.reader + "\0").getBytes(wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
        } else {
            Pcsc.Bytes r = pcsc.getAttrib(card.real, c.attrId());
            if (r.rc() != Scard.S_SUCCESS) {
                return new Result(r.rc(), none);
            }
            data = r.data();
        }
        if (c.attrNull()) {
            return new Result(Scard.S_SUCCESS, ScardNdr.bufferReturn(new byte[data.length])); // length only
        }
        if (c.attrLength() != AUTOALLOCATE && Integer.compareUnsigned(c.attrLength(), data.length) < 0) {
            return new Result(Scard.E_INSUFFICIENT_BUFFER, none);
        }
        return new Result(Scard.S_SUCCESS, ScardNdr.bufferReturn(data));
    }

    private Result transmitCount(ScardNdr.GetTransmitCount c) {
        Card card = card(c.context(), c.card());
        return card == null ? new Result(Scard.E_INVALID_HANDLE, ScardNdr.u32Return(0))
                : new Result(Scard.S_SUCCESS, ScardNdr.u32Return(card.transmits));
    }

    private Result contextAndReader(ScardNdr.ContextAndReader c) {
        Ctx ctx = ctx(c.context());
        boolean icon = c.ioctl() == Scard.IOCTL_GETREADERICON;
        byte[] none = icon ? ScardNdr.bufferReturn(new byte[0]) : ScardNdr.u32Return(0);
        if (ctx == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        int[] lrc = new int[1];
        if (!visibleReaders(ctx.real, lrc).contains(c.reader())) {
            return new Result(Scard.E_UNKNOWN_READER, none);
        }
        return icon ? new Result(Scard.E_UNSUPPORTED_FEATURE, none)
                : new Result(Scard.S_SUCCESS, ScardNdr.u32Return(Scard.READER_TYPE_USB));
    }

    // ---- cache (S4: memory only, this connection only) ----

    private static String cacheKey(byte[] cardId, String name) {
        return (cardId == null ? "-" : HexFormat.of().formatHex(cardId)) + "|" + name;
    }

    private Result readCache(ScardNdr.ReadCache c) {
        byte[] none = ScardNdr.bufferReturn(new byte[0]);
        if (ctx(c.context()) == null) {
            return new Result(Scard.E_INVALID_HANDLE, none);
        }
        CacheItem item;
        synchronized (this) {
            item = cache.get(cacheKey(c.cardId(), c.name()));
        }
        if (item == null) {
            return new Result(Scard.W_CACHE_ITEM_NOT_FOUND, none);
        }
        if (item.freshness() != c.freshness()) {
            return new Result(Scard.W_CACHE_ITEM_STALE, none);
        }
        if (c.dataNull() || (c.dataLength() != AUTOALLOCATE
                && Integer.compareUnsigned(c.dataLength(), item.data().length) < 0)) {
            return new Result(Scard.E_INSUFFICIENT_BUFFER, none);
        }
        return new Result(Scard.S_SUCCESS, ScardNdr.bufferReturn(item.data()));
    }

    private Result writeCache(ScardNdr.WriteCache c) {
        if (ctx(c.context()) == null) {
            return Result.of(Scard.E_INVALID_HANDLE);
        }
        if (c.data().length > MAX_CACHE_ITEM) {
            return Result.of(Scard.E_NO_MEMORY);
        }
        synchronized (this) {
            cache.put(cacheKey(c.cardId(), c.name()), new CacheItem(c.freshness(), c.data().clone()));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                String eldest = cache.keySet().iterator().next();
                cache.remove(eldest);
            }
        }
        return Result.of(Scard.S_SUCCESS);
    }

    // ---- state the pane reads or changes ----

    public synchronized int openCards() {
        return cards.size();
    }

    public synchronized Consent consent() {
        return consent;
    }

    /** From the bar: allow again after a refusal or a block. */
    public void allow() {
        synchronized (consentLock) {
            synchronized (this) {
                consent = Consent.ALLOWED;
            }
        }
    }

    /**
     * From the bar: no more cards this connection. Every open card handle is disconnected with a
     * reset, which also drops the card's verified-PIN state.
     */
    public void block() {
        List<Card> open;
        synchronized (consentLock) {
            synchronized (this) {
                consent = Consent.DENIED;
                open = new ArrayList<>(cards.values());
                cards.clear();
            }
        }
        for (Card card : open) {
            pcsc.disconnect(card.real, Scard.RESET_CARD);
            guard(card.reader).cardReset();
        }
        ui.cardsInUse(0);
    }

    private synchronized ScardPolicy.PinGuard guard(String reader) {
        return pinGuards.computeIfAbsent(ScardPolicy.baseName(reader), r -> new ScardPolicy.PinGuard());
    }

    /**
     * Ends the connection's redirection: cancels every waiting call, resets and disconnects every
     * card, releases every context. Calls arriving later are answered "service stopped".
     */
    @Override
    public void close() {
        List<Ctx> ctxs;
        List<Card> open;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ctxs = new ArrayList<>(contexts.values());
            open = new ArrayList<>(cards.values());
            contexts.clear();
            cards.clear();
            cache.clear();
        }
        synchronized (consentLock) {
            consentLock.notifyAll();
        }
        for (Ctx ctx : ctxs) {
            ctx.cancelled = true;
            pcsc.cancel(ctx.real);
            ctx.shutdown();
        }
        for (Ctx ctx : ctxs) {
            try {
                ctx.thread.awaitTermination(500, TimeUnit.MILLISECONDS);
                ctx.txThread.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Card card : open) {
            pcsc.disconnect(card.real, Scard.RESET_CARD);
        }
        for (Ctx ctx : ctxs) {
            pcsc.release(ctx.real);
        }
        immediate.shutdownNow();
        if (!open.isEmpty()) {
            ui.cardsInUse(0);
        }
    }

    // ---- helpers ----

    /** A fresh random id with bit 63 set (so never equal to a 4-byte value) and not already in use. */
    private long newId(Map<Long, ?> taken) {
        long id;
        do {
            id = random.nextLong() | Long.MIN_VALUE;
        } while (taken.containsKey(id));
        return id;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** For tests: the copy of a reply's NDR body after the headers (no contents are ever logged). */
    static byte[] body(ScardNdr.Reply r) {
        return Arrays.copyOfRange(r.output(), 24, r.output().length);
    }
}
