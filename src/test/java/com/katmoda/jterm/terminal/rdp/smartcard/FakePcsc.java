package com.katmoda.jterm.terminal.rdp.smartcard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** A scripted PC/SC: readers, cards that answer APDUs, and a record of what was asked. */
final class FakePcsc implements Pcsc {

    final List<String> readers = new CopyOnWriteArrayList<>();
    final Map<String, byte[]> atrs = Collections.synchronizedMap(new HashMap<>());
    volatile Function<byte[], byte[]> card = apdu -> new byte[]{(byte) 0x90, 0};
    volatile byte[] features = new byte[0];
    /** False: like macOS, the PnP pseudo-reader must never be passed (it crashes there). */
    volatile boolean pnp = true;

    @Override
    public boolean supportsPnpNotification() {
        return pnp;
    }

    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<Integer> disconnects = new CopyOnWriteArrayList<>();
    final List<Integer> shareModes = new CopyOnWriteArrayList<>();
    final List<byte[]> transmitted = new CopyOnWriteArrayList<>();
    final Map<Long, Object> waits = Collections.synchronizedMap(new HashMap<>());
    private final AtomicLong next = new AtomicLong(100);
    final List<Long> released = new CopyOnWriteArrayList<>();

    @Override
    public Context establish(int scope) {
        calls.add("establish");
        return new Context(Scard.S_SUCCESS, next.incrementAndGet());
    }

    @Override
    public int release(long context) {
        released.add(context);
        return Scard.S_SUCCESS;
    }

    @Override
    public int isValid(long context) {
        return Scard.S_SUCCESS;
    }

    /** When set, listed instead of {@link #readers} (which still decides what a status change knows). */
    volatile List<String> listed;

    @Override
    public Readers listReaders(long context) {
        List<String> l = listed;
        if (l != null) {
            return new Readers(Scard.S_SUCCESS, new ArrayList<>(l));
        }
        return readers.isEmpty() ? new Readers(Scard.E_NO_READERS_AVAILABLE, List.of())
                : new Readers(Scard.S_SUCCESS, new ArrayList<>(readers));
    }

    /** Present readers report PRESENT (with the ATR); a slot differing from its current state changes at once. */
    /** Run at the start of every status change: e.g. unplug a reader after it was listed. */
    volatile Runnable beforeStatusChange = () -> { };

    @Override
    public int getStatusChange(long context, int timeoutMs, StatusSlot[] slots) {
        beforeStatusChange.run();
        long until = timeoutMs == Scard.INFINITE ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMs;
        Object lock = waits.computeIfAbsent(context, k -> new Object());
        synchronized (lock) {
            while (true) {
                boolean changed = false;
                for (StatusSlot s : slots) {
                    int now;
                    byte[] atr = new byte[0];
                    if (s.reader.equals(Scard.PNP_NOTIFICATION)) {
                        if (!pnp) {
                            throw new AssertionError("PnP pseudo-reader passed to a PC/SC without it");
                        }
                        now = readers.size() << 16;
                    } else if (readers.contains(s.reader)) {
                        atr = atrs.getOrDefault(s.reader, new byte[]{0x3B, 0x00});
                        now = atr.length > 0 ? Scard.STATE_PRESENT : Scard.STATE_EMPTY;
                    } else {
                        return Scard.E_UNKNOWN_READER; // what pcsc-lite does
                    }
                    boolean differs = s.reader.equals(Scard.PNP_NOTIFICATION)
                            ? (s.currentState >>> 16) != (now >>> 16)
                            : (s.currentState & 0xFFFF & ~Scard.STATE_CHANGED) != now;
                    s.eventState = now | (differs ? Scard.STATE_CHANGED : 0);
                    s.atr = atr;
                    changed |= differs;
                }
                if (changed) {
                    return Scard.S_SUCCESS;
                }
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return Scard.E_TIMEOUT;
                }
                try {
                    lock.wait(Math.min(left, 50));
                } catch (InterruptedException e) {
                    return Scard.E_CANCELLED;
                }
                if (cancelled.remove(context)) {
                    return Scard.E_CANCELLED;
                }
            }
        }
    }

    final List<Long> cancelled = new CopyOnWriteArrayList<>();

    @Override
    public int cancel(long context) {
        cancelled.add(context);
        Object lock = waits.computeIfAbsent(context, k -> new Object());
        synchronized (lock) {
            lock.notifyAll();
        }
        return Scard.S_SUCCESS;
    }

    @Override
    public Connected connect(long context, String reader, int shareMode, int protocols) {
        calls.add("connect " + reader);
        shareModes.add(shareMode);
        if (!readers.contains(reader)) {
            return new Connected(Scard.E_UNKNOWN_READER, 0, 0);
        }
        return new Connected(Scard.S_SUCCESS, next.incrementAndGet(), Scard.PROTOCOL_T1);
    }

    @Override
    public IntResult reconnect(long card, int shareMode, int protocols, int initialization) {
        return new IntResult(Scard.S_SUCCESS, Scard.PROTOCOL_T1);
    }

    @Override
    public int disconnect(long card, int disposition) {
        disconnects.add(disposition);
        return Scard.S_SUCCESS;
    }

    /** When set, BeginTransaction waits for it (another handle holds the card). */
    volatile java.util.concurrent.CountDownLatch beginGate;

    @Override
    public int beginTransaction(long card) {
        java.util.concurrent.CountDownLatch g = beginGate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException e) {
                return Scard.E_CANCELLED;
            }
        }
        return Scard.S_SUCCESS;
    }

    @Override
    public int endTransaction(long card, int disposition) {
        return Scard.S_SUCCESS;
    }

    @Override
    public CardStatus status(long card) {
        return new CardStatus(Scard.S_SUCCESS, "whatever the system says", Scard.CARD_SPECIFIC, Scard.PROTOCOL_T1,
                new byte[]{0x3B, 0x00});
    }

    @Override
    public Transmitted transmit(long card, int protocol, byte[] send, int recvMax) {
        transmitted.add(send);
        return new Transmitted(Scard.S_SUCCESS, protocol, this.card.apply(send));
    }

    @Override
    public Bytes control(long card, int code, byte[] in, int outMax) {
        calls.add(String.format("control %08x", code));
        return new Bytes(Scard.S_SUCCESS, code == ScardPolicy.GET_FEATURE_REQUEST ? features : new byte[]{1});
    }

    @Override
    public Bytes getAttrib(long card, int attrId) {
        calls.add(String.format("attrib %08x", attrId));
        return new Bytes(Scard.S_SUCCESS, new byte[]{7, 7});
    }
}
