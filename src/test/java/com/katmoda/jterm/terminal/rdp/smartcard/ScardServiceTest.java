package com.katmoda.jterm.terminal.rdp.smartcard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScardServiceTest {

    static final String PICKED = "Picked Reader 00 00";
    static final String OTHER = "Contactless Reader 01 00";

    FakePcsc pcsc;
    ScardService service;
    final AtomicInteger consentAsked = new AtomicInteger();
    volatile boolean allowCards = true;
    final AtomicInteger lastTryAsked = new AtomicInteger();
    volatile boolean allowLastTry = false;
    final List<String> blocked = new ArrayList<>();
    volatile int inUse = -1;

    @BeforeEach
    void setUp() {
        pcsc = new FakePcsc();
        pcsc.readers.add(PICKED);
        pcsc.readers.add(OTHER);
        // Picked without pcsc-lite's index: still matches after a replug renumbers it.
        service = new ScardService(pcsc, List.of("Picked Reader"), new ScardService.Ui() {
            @Override
            public boolean allowCards(String reader) {
                consentAsked.incrementAndGet();
                return allowCards;
            }

            @Override
            public boolean allowLastPinTry(String reader) {
                lastTryAsked.incrementAndGet();
                return allowLastTry;
            }

            @Override
            public void pinBlocked(String reader) {
                blocked.add(reader);
            }

            @Override
            public void cardsInUse(int open) {
                inUse = open;
            }
        });
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    record Answer(int ioStatus, int rc, byte[] body) {
    }

    Answer call(byte[] request) throws Exception {
        CompletableFuture<ScardNdr.Reply> f = new CompletableFuture<>();
        service.submit(request, f::complete);
        ScardNdr.Reply r = f.get(5, TimeUnit.SECONDS);
        int rc = r.output().length >= 24 ? ScardNdr.le32(r.output(), 20) : 0;
        return new Answer(r.ioStatus(), rc, r.output().length >= 24 ? ScardService.body(r) : new byte[0]);
    }

    long establish() throws Exception {
        Answer a = call(Calls.establish());
        assertEquals(Scard.S_SUCCESS, a.rc());
        return le64(a.body(), 12);
    }

    long connect(long ctx) throws Exception {
        Answer a = call(Calls.connect(ctx, PICKED, Scard.SHARE_SHARED));
        assertEquals(Scard.S_SUCCESS, a.rc());
        return le64(a.body(), 36);
    }

    @Test
    void handlesAreJtermsOwn_andAGuessIsInvalid() throws Exception {
        long ctx = establish();
        assertTrue(ctx < 0, "bit 63 set");
        assertNotEquals(101L, ctx, "never the system's value");
        assertEquals(Scard.E_INVALID_HANDLE, call(Calls.context(Scard.IOCTL_ISVALIDCONTEXT, ctx + 1)).rc());
        assertEquals(Scard.S_SUCCESS, call(Calls.context(Scard.IOCTL_ISVALIDCONTEXT, ctx)).rc());
        long card = connect(ctx);
        long other = establish();
        assertEquals(Scard.E_INVALID_HANDLE, call(Calls.status(other, card)).rc(), "a card of another context");
        assertEquals(Scard.E_INVALID_HANDLE, call(Calls.status(ctx, card ^ 1)).rc());
    }

    @Test
    void onlyPickedReadersAreListed() throws Exception {
        long ctx = establish();
        Answer a = call(Calls.listReaders(ctx));
        assertEquals(Scard.S_SUCCESS, a.rc());
        assertEquals(PICKED + "\0\0", new String(Arrays.copyOfRange(a.body(), 12, 12 + ScardNdr.le32(a.body(), 0)),
                StandardCharsets.UTF_16LE));
        pcsc.readers.remove(PICKED);
        assertEquals(Scard.E_NO_READERS_AVAILABLE, call(Calls.listReaders(ctx)).rc(),
                "the same answer as no reader at all");
    }

    @Test
    void anUnpickedReaderIsUnknown_evenByName_andNobodyIsAsked() throws Exception {
        long ctx = establish();
        assertEquals(Scard.E_UNKNOWN_READER, call(Calls.connect(ctx, OTHER, Scard.SHARE_SHARED)).rc());
        assertEquals(0, consentAsked.get());
        assertFalse(pcsc.calls.contains("connect " + OTHER));
    }

    @Test
    void consentIsAskedOncePerConnection_andDenialHolds() throws Exception {
        allowCards = false;
        long ctx = establish();
        assertEquals(Scard.E_NO_SMARTCARD, call(Calls.connect(ctx, PICKED, Scard.SHARE_SHARED)).rc());
        assertEquals(Scard.E_NO_SMARTCARD, call(Calls.connect(ctx, PICKED, Scard.SHARE_SHARED)).rc());
        assertEquals(1, consentAsked.get());
        assertFalse(pcsc.calls.contains("connect " + PICKED), "no card is touched before consent");
        service.allow();
        connect(ctx);
        assertEquals(1, inUse);
    }

    @Test
    void exclusiveBecomesShared_directIsRefused() throws Exception {
        long ctx = establish();
        assertEquals(Scard.S_SUCCESS, call(Calls.connect(ctx, PICKED, Scard.SHARE_EXCLUSIVE)).rc());
        assertEquals(List.of(Scard.SHARE_SHARED), pcsc.shareModes);
        assertEquals(Scard.E_SHARING_VIOLATION, call(Calls.connect(ctx, PICKED, Scard.SHARE_DIRECT)).rc());
    }

    @Test
    void statusReportsTheNameTheServerConnectedWith() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        Answer a = call(Calls.status(ctx, card));
        int len = ScardNdr.le32(a.body(), 0);
        assertEquals(PICKED + "\0\0", new String(Arrays.copyOfRange(a.body(), 56, 56 + len), StandardCharsets.UTF_16LE));
    }

    @Test
    void afterOneTryLeftTheNextPinNeedsTheUser() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        byte[] verify = HexFormat.of().parseHex("002000800831313131313131ff");
        pcsc.card = apdu -> new byte[]{0x63, (byte) 0xC1};
        assertResponse(call(Calls.transmit(ctx, card, verify)), "63c1");
        assertEquals(0, lastTryAsked.get());

        int sent = pcsc.transmitted.size();
        assertResponse(call(Calls.transmit(ctx, card, verify)), "6982");
        assertEquals(1, lastTryAsked.get());
        assertEquals(sent, pcsc.transmitted.size(), "refused: the card never saw it");

        // A query for the counter (VERIFY without data) can't use a try: not asked.
        assertResponse(call(Calls.transmit(ctx, card, HexFormat.of().parseHex("00200080"))), "63c1");
        assertEquals(1, lastTryAsked.get());

        allowLastTry = true;
        pcsc.card = apdu -> new byte[]{0x69, (byte) 0x83};
        assertResponse(call(Calls.transmit(ctx, card, verify)), "6983");
        assertEquals(List.of(PICKED), blocked);
    }

    @Test
    void aCorrectPinClearsTheGuard() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        byte[] verify = HexFormat.of().parseHex("002000800831323334353637ff");
        pcsc.card = apdu -> new byte[]{0x63, (byte) 0xC1};
        call(Calls.transmit(ctx, card, verify));
        allowLastTry = true;
        pcsc.card = apdu -> new byte[]{(byte) 0x90, 0};
        call(Calls.transmit(ctx, card, verify));
        allowLastTry = false;
        assertResponse(call(Calls.transmit(ctx, card, verify)), "9000");
        assertEquals(1, lastTryAsked.get());
    }

    @Test
    void onlyPinPadControlCodesFromTheFeatureListPass() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        int verifyDirect = 0x42330006;
        assertEquals(Scard.E_UNSUPPORTED_FEATURE, call(Calls.control(ctx, card, verifyDirect)).rc(), "not announced yet");
        // The reader offers verify-direct (06) and a firmware feature (0x15): only the first survives.
        pcsc.features = HexFormat.of().parseHex("06044233000615044233001f");
        Answer f = call(Calls.control(ctx, card, ScardPolicy.GET_FEATURE_REQUEST));
        assertEquals(Scard.S_SUCCESS, f.rc());
        assertArrayEquals(HexFormat.of().parseHex("060442330006"), Arrays.copyOfRange(f.body(), 12, 18));
        assertEquals(6, ScardNdr.le32(f.body(), 0));
        assertEquals(Scard.S_SUCCESS, call(Calls.control(ctx, card, verifyDirect)).rc());
        assertEquals(Scard.E_UNSUPPORTED_FEATURE, call(Calls.control(ctx, card, 0x4233001F)).rc());
        assertFalse(pcsc.calls.contains("control 4233001f"));
    }

    @Test
    void theReaderSerialIsNotGiven_namesAreJterms() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        assertEquals(Scard.E_UNSUPPORTED_FEATURE,
                call(Calls.getAttrib(ctx, card, ScardPolicy.ATTR_VENDOR_IFD_SERIAL_NO)).rc());
        Answer name = call(Calls.getAttrib(ctx, card, ScardPolicy.ATTR_DEVICE_FRIENDLY_NAME_W));
        int len = ScardNdr.le32(name.body(), 0);
        assertEquals(PICKED + "\0", new String(Arrays.copyOfRange(name.body(), 12, 12 + len), StandardCharsets.UTF_16LE));
        assertEquals(Scard.S_SUCCESS, call(Calls.getAttrib(ctx, card, ScardPolicy.ATTR_ATR_STRING)).rc());
        assertTrue(pcsc.calls.contains(String.format("attrib %08x", ScardPolicy.ATTR_ATR_STRING)));
        assertFalse(pcsc.calls.contains(String.format("attrib %08x", ScardPolicy.ATTR_VENDOR_IFD_SERIAL_NO)));
    }

    @Test
    void theCacheIsPerConnection_withFreshness() throws Exception {
        long ctx = establish();
        byte[] id = new byte[16];
        id[0] = 9;
        assertEquals(Scard.W_CACHE_ITEM_NOT_FOUND, call(Calls.readCache(ctx, id, "cardcf", 1)).rc());
        assertEquals(Scard.S_SUCCESS, call(Calls.writeCache(ctx, id, "cardcf", 1, new byte[]{1, 2, 3})).rc());
        Answer hit = call(Calls.readCache(establish(), id, "cardcf", 1));
        assertEquals(Scard.S_SUCCESS, hit.rc(), "shared by the connection's contexts");
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOfRange(hit.body(), 12, 15));
        assertEquals(Scard.W_CACHE_ITEM_STALE, call(Calls.readCache(ctx, id, "cardcf", 2)).rc());
        for (int i = 0; i < ScardService.MAX_CACHE_ENTRIES + 5; i++) {
            call(Calls.writeCache(ctx, id, "item" + i, 1, new byte[]{1}));
        }
        assertEquals(Scard.W_CACHE_ITEM_NOT_FOUND, call(Calls.readCache(ctx, id, "item0", 1)).rc(), "capped");
    }

    @Test
    void anUnpickedReaderInAStatusChangeLooksAbsent() throws Exception {
        long ctx = establish();
        Answer a = call(Calls.statusChange(ctx, 1000, List.of(new Calls.State(OTHER, 0))));
        assertEquals(Scard.S_SUCCESS, a.rc());
        assertEquals(Scard.STATE_UNKNOWN | Scard.STATE_CHANGED, ScardNdr.le32(a.body(), 12 + 4));
        // Already known as unknown: nothing to report, so it waits out the timeout.
        Answer b = call(Calls.statusChange(ctx, 100, List.of(new Calls.State(OTHER, Scard.STATE_UNKNOWN))));
        assertEquals(Scard.E_TIMEOUT, b.rc());
    }

    @Test
    void aReaderUnpluggedBetweenListingAndCallIsReportedUnknown() throws Exception {
        long ctx = establish();
        // Gone after jterm listed it: PC/SC fails the whole call, which must not reach the server.
        pcsc.beforeStatusChange = () -> pcsc.readers.remove(PICKED);
        Answer a = call(Calls.statusChange(ctx, 1000, List.of(new Calls.State(PICKED, Scard.STATE_PRESENT))));
        assertEquals(Scard.S_SUCCESS, a.rc());
        assertEquals(Scard.STATE_UNKNOWN | Scard.STATE_CHANGED, ScardNdr.le32(a.body(), 12 + 4));
    }

    @Test
    void aReaderThatStaysListedButUnknownEndsTheRetries() throws Exception {
        long ctx = establish();
        AtomicInteger tries = new AtomicInteger();
        pcsc.beforeStatusChange = tries::incrementAndGet;
        // A system that keeps listing the reader but calls it unknown every time.
        pcsc.listed = List.of(PICKED);
        pcsc.readers.remove(PICKED);
        Answer a = call(Calls.statusChange(ctx, 1000, List.of(new Calls.State(PICKED, Scard.STATE_PRESENT))));
        assertEquals(Scard.E_UNKNOWN_READER, a.rc());
        assertEquals(ScardService.MAX_VANISHED_RETRIES + 1, tries.get());
    }

    @Test
    void thePnpReaderCountsVisibleReadersOnly() throws Exception {
        long ctx = establish();
        Answer a = call(Calls.statusChange(ctx, 1000, List.of(new Calls.State(Scard.PNP_NOTIFICATION, 0))));
        assertEquals(1, ScardNdr.le32(a.body(), 12 + 4) >>> 16, "one picked reader, not two");
        // Told one; an unpicked reader appearing must not wake it.
        CompletableFuture<ScardNdr.Reply> f = new CompletableFuture<>();
        service.submit(Calls.statusChange(ctx, 600, List.of(new Calls.State(Scard.PNP_NOTIFICATION, 1 << 16))),
                f::complete);
        Thread.sleep(100);
        pcsc.readers.add("Another Unpicked Reader 02 00");
        ScardNdr.Reply r = f.get(5, TimeUnit.SECONDS);
        assertEquals(Scard.E_TIMEOUT, ScardNdr.le32(r.output(), 20));
    }

    @Test
    void withoutPnpSupportReadersArePolled() throws Exception {
        pcsc.pnp = false;
        long ctx = establish();
        CompletableFuture<ScardNdr.Reply> f = new CompletableFuture<>();
        service.submit(Calls.statusChange(ctx, 10_000, List.of(new Calls.State(Scard.PNP_NOTIFICATION, 1 << 16))),
                f::complete);
        Thread.sleep(200);
        assertFalse(f.isDone());
        pcsc.readers.add("Picked Reader 01 00"); // a second picked reader arrives
        ScardNdr.Reply r = f.get(5, TimeUnit.SECONDS);
        assertEquals(Scard.S_SUCCESS, ScardNdr.le32(r.output(), 20));
        assertEquals(2, ScardNdr.le32(ScardService.body(r), 12 + 4) >>> 16);
    }

    @Test
    void cancelEndsAWaitingStatusChange() throws Exception {
        long ctx = establish();
        CompletableFuture<ScardNdr.Reply> f = new CompletableFuture<>();
        service.submit(Calls.statusChange(ctx, Scard.INFINITE, List.of(new Calls.State(PICKED, Scard.STATE_PRESENT))),
                f::complete);
        Thread.sleep(150);
        assertFalse(f.isDone());
        assertEquals(Scard.S_SUCCESS, call(Calls.context(Scard.IOCTL_CANCEL, ctx)).rc(), "not queued behind the wait");
        assertEquals(Scard.E_CANCELLED, ScardNdr.le32(f.get(5, TimeUnit.SECONDS).output(), 20));
    }

    @Test
    void contextsAndCardsAreCapped() throws Exception {
        for (int i = 0; i < ScardService.MAX_CONTEXTS; i++) {
            establish();
        }
        assertEquals(Scard.E_NO_MEMORY, call(Calls.establish()).rc());
    }

    @Test
    void blockResetsEveryCard_andRefusesNewOnes() throws Exception {
        long ctx = establish();
        long card = connect(ctx);
        service.block();
        assertEquals(List.of(Scard.RESET_CARD), pcsc.disconnects);
        assertEquals(0, inUse);
        assertEquals(Scard.E_INVALID_HANDLE, call(Calls.status(ctx, card)).rc());
        assertEquals(Scard.E_NO_SMARTCARD, call(Calls.connect(ctx, PICKED, Scard.SHARE_SHARED)).rc());
        assertEquals(1, consentAsked.get(), "blocking is the answer: no new question");
    }

    @Test
    void closeResetsCardsAndReleasesContexts() throws Exception {
        long ctx = establish();
        connect(ctx);
        connect(ctx);
        service.close();
        assertEquals(List.of(Scard.RESET_CARD, Scard.RESET_CARD), pcsc.disconnects);
        assertEquals(1, pcsc.released.size());
        assertEquals(Scard.E_SERVICE_STOPPED, call(Calls.establish()).rc());
    }

    @Test
    void releaseContextDisconnectsItsCards() throws Exception {
        long ctx = establish();
        connect(ctx);
        assertEquals(Scard.S_SUCCESS, call(Calls.context(Scard.IOCTL_RELEASECONTEXT, ctx)).rc());
        assertEquals(Scard.E_INVALID_HANDLE, call(Calls.context(Scard.IOCTL_ISVALIDCONTEXT, ctx)).rc());
        waitFor(() -> pcsc.disconnects.equals(List.of(Scard.RESET_CARD)) && pcsc.released.size() == 1);
    }

    @Test
    void releaseIsAnsweredAtOnce_evenDuringAnEndlessWait() throws Exception {
        long ctx = establish();
        CompletableFuture<ScardNdr.Reply> wait = new CompletableFuture<>();
        service.submit(Calls.statusChange(ctx, Scard.INFINITE, List.of(new Calls.State(PICKED, Scard.STATE_PRESENT))),
                wait::complete);
        Thread.sleep(100);
        assertEquals(Scard.S_SUCCESS, call(Calls.context(Scard.IOCTL_ISVALIDCONTEXT, ctx)).rc(), "not queued");
        assertEquals(Scard.S_SUCCESS, call(Calls.context(Scard.IOCTL_RELEASECONTEXT, ctx)).rc());
        assertEquals(Scard.E_CANCELLED, ScardNdr.le32(wait.get(5, TimeUnit.SECONDS).output(), 20));
        waitFor(() -> pcsc.released.size() == 1);
    }

    @Test
    void aWaitingBeginTransactionDoesNotHoldUpTheContext() throws Exception {
        long ctx = establish();
        long a = connect(ctx);
        long b = connect(ctx);
        pcsc.beginGate = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<ScardNdr.Reply> begin = new CompletableFuture<>();
        service.submit(Calls.disposition(Scard.IOCTL_BEGINTRANSACTION, ctx, b, 0), begin::complete);
        Thread.sleep(100);
        // The handle holding the card can still end its transaction and read its status.
        assertEquals(Scard.S_SUCCESS, call(Calls.disposition(Scard.IOCTL_ENDTRANSACTION, ctx, a, 0)).rc());
        assertEquals(Scard.S_SUCCESS, call(Calls.status(ctx, a)).rc());
        assertFalse(begin.isDone());
        pcsc.beginGate.countDown();
        assertEquals(Scard.S_SUCCESS, ScardNdr.le32(begin.get(5, TimeUnit.SECONDS).output(), 20));
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void aMalformedCallFailsTheRequest() throws Exception {
        byte[] bad = Calls.establish();
        ScardNdr.put32(bad, 4, 999);
        assertEquals(Scard.STATUS_UNSUCCESSFUL, call(bad).ioStatus());
    }

    @Test
    void refusedCallsSayUnsupported() throws Exception {
        byte[] in = new byte[32];
        ScardNdr.put32(in, 0, 1024);
        ScardNdr.put32(in, 8, Scard.IOCTL_INTRODUCEREADERW);
        assertEquals(Scard.E_UNSUPPORTED_FEATURE, call(in).rc());
    }

    private static void assertResponse(Answer a, String hex) {
        assertEquals(Scard.S_SUCCESS, a.rc());
        byte[] b = a.body();
        // receive-PCI pointer, cbRecvLength, buffer pointer, count, the bytes
        int len = ScardNdr.le32(b, 4);
        assertEquals(hex, HexFormat.of().formatHex(Arrays.copyOfRange(b, 16, 16 + len)));
    }

    private static long le64(byte[] b, int o) {
        long x = 0;
        for (int i = 7; i >= 0; i--) {
            x = (x << 8) | (b[o + i] & 0xFF);
        }
        return x;
    }
}
