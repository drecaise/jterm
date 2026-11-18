package com.katmoda.jterm.terminal.rdp.smartcard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Against calls captured from Windows 11 and the replies FreeRDP gave them (fixture file). */
class ScardNdrTest {

    record Exchange(byte[] in, int ioStatus, byte[] out) {
        int ioctl() {
            return ScardNdr.le32(in, 8);
        }

        /** The reply's return code and body, after OutputBufferLength and the two headers. */
        int result() {
            return ScardNdr.le32(out, 20);
        }

        byte[] body() {
            return Arrays.copyOfRange(out, 24, out.length);
        }

        int outputBufferLength() {
            return ScardNdr.le32(in, 0);
        }
    }

    static List<Exchange> fixtures() throws IOException {
        List<Exchange> list = new ArrayList<>();
        try (InputStream s = ScardNdrTest.class.getResourceAsStream("windows11-certutil.txt")) {
            assertNotNull(s);
            String[] lines = new String(s.readAllBytes(), StandardCharsets.US_ASCII).split("\n");
            byte[] in = null;
            for (String line : lines) {
                if (line.startsWith("IN ")) {
                    in = HexFormat.of().parseHex(line.substring(3).strip());
                } else if (line.startsWith("OUT ")) {
                    String[] p = line.strip().split(" ");
                    list.add(new Exchange(in, Integer.parseUnsignedInt(p[1], 16),
                            p.length > 2 ? HexFormat.of().parseHex(p[2]) : new byte[0]));
                }
            }
        }
        assertFalse(list.isEmpty());
        return list;
    }

    static List<Exchange> of(int ioctl) throws IOException {
        return fixtures().stream().filter(e -> e.ioctl() == ioctl).toList();
    }

    @Test
    void everyCapturedCallDecodesToItsKind() throws Exception {
        for (Exchange e : fixtures()) {
            ScardNdr.Request r = ScardNdr.decode(e.in());
            ScardNdr.Call c = r.call();
            assertEquals(e.ioctl(), c.ioctl());
            Class<?> want = switch (e.ioctl()) {
                case Scard.IOCTL_ESTABLISHCONTEXT -> ScardNdr.EstablishContext.class;
                case Scard.IOCTL_RELEASECONTEXT, Scard.IOCTL_ISVALIDCONTEXT, Scard.IOCTL_CANCEL -> ScardNdr.ContextCall.class;
                case Scard.IOCTL_LISTREADERSW -> ScardNdr.ListReaders.class;
                case Scard.IOCTL_GETSTATUSCHANGEW -> ScardNdr.GetStatusChange.class;
                case Scard.IOCTL_CONNECTW -> ScardNdr.Connect.class;
                case Scard.IOCTL_DISCONNECT, Scard.IOCTL_BEGINTRANSACTION, Scard.IOCTL_ENDTRANSACTION -> ScardNdr.CardDisposition.class;
                case Scard.IOCTL_STATUSW -> ScardNdr.Status.class;
                case Scard.IOCTL_TRANSMIT -> ScardNdr.Transmit.class;
                case Scard.IOCTL_READCACHEW -> ScardNdr.ReadCache.class;
                case Scard.IOCTL_WRITECACHEW -> ScardNdr.WriteCache.class;
                case Scard.IOCTL_GETDEVICETYPEID -> ScardNdr.ContextAndReader.class;
                case Scard.IOCTL_ACCESSSTARTEDEVENT -> ScardNdr.AccessStartedEvent.class;
                default -> null;
            };
            assertNotNull(want, "no expectation for " + Scard.ioctlName(e.ioctl()));
            assertInstanceOf(want, c, Scard.ioctlName(e.ioctl()));
            assertTrue(c.unicode() || e.ioctl() == Scard.IOCTL_ESTABLISHCONTEXT || !Scard.ioctlName(e.ioctl()).isEmpty());
        }
    }

    @Test
    void readerNamesAndTheirStatesComeThrough() throws Exception {
        ScardNdr.Connect connect = (ScardNdr.Connect) ScardNdr.decode(of(Scard.IOCTL_CONNECTW).get(0).in()).call();
        assertEquals("Virtual PCD 00 00", connect.reader());
        assertEquals(Scard.SHARE_SHARED, connect.shareMode());

        boolean sawReader = false;
        for (Exchange e : of(Scard.IOCTL_GETSTATUSCHANGEW)) {
            ScardNdr.GetStatusChange g = (ScardNdr.GetStatusChange) ScardNdr.decode(e.in()).call();
            assertFalse(g.states().isEmpty());
            for (ScardNdr.ReaderState s : g.states()) {
                assertTrue(s.reader().equals("Virtual PCD 00 00") || s.reader().equals(Scard.PNP_NOTIFICATION),
                        s.reader());
                sawReader |= s.reader().startsWith("Virtual");
            }
        }
        assertTrue(sawReader);

        ScardNdr.ContextAndReader type = (ScardNdr.ContextAndReader) ScardNdr.decode(
                of(Scard.IOCTL_GETDEVICETYPEID).get(0).in()).call();
        assertEquals("Virtual PCD 00 00", type.reader());
    }

    @Test
    void cacheCallsCarryTheCardIdNameAndData() throws Exception {
        ScardNdr.ReadCache read = (ScardNdr.ReadCache) ScardNdr.decode(of(Scard.IOCTL_READCACHEW).get(0).in()).call();
        assertEquals(16, read.cardId().length);
        assertFalse(read.name().isEmpty());
        ScardNdr.WriteCache write = (ScardNdr.WriteCache) ScardNdr.decode(of(Scard.IOCTL_WRITECACHEW).get(0).in()).call();
        assertEquals(16, write.cardId().length);
        assertTrue(write.data().length > 0);
    }

    @Test
    void thePinVerifyIsATransmitWithTheApduInside() throws Exception {
        Exchange verify = fixtures().stream()
                .filter(e -> e.ioctl() == Scard.IOCTL_TRANSMIT)
                .filter(e -> {
                    try {
                        byte[] apdu = ((ScardNdr.Transmit) ScardNdr.decode(e.in()).call()).send();
                        return apdu.length > 4 && apdu[1] == 0x20;
                    } catch (NdrReader.Malformed m) {
                        return false;
                    }
                }).findFirst().orElseThrow();
        ScardNdr.Transmit t = (ScardNdr.Transmit) ScardNdr.decode(verify.in()).call();
        assertArrayEquals(HexFormat.of().parseHex("0020008008313233343536ffff"), t.send());
        assertEquals(Scard.PROTOCOL_T1, t.sendPci().protocol());
    }

    // ---- replies, byte for byte against FreeRDP's ----

    private static void assertReply(Exchange e, byte[] body) {
        ScardNdr.Reply r = ScardNdr.reply(e.ioctl(), e.outputBufferLength(), e.result(), body);
        assertEquals(e.ioStatus(), r.ioStatus(), Scard.ioctlName(e.ioctl()));
        assertArrayEquals(e.out(), r.output(), Scard.ioctlName(e.ioctl()) + " reply");
    }

    @Test
    void repliesWithoutABodyMatch() throws Exception {
        for (int ioctl : new int[]{Scard.IOCTL_ACCESSSTARTEDEVENT, Scard.IOCTL_RELEASECONTEXT, Scard.IOCTL_ISVALIDCONTEXT,
                Scard.IOCTL_BEGINTRANSACTION, Scard.IOCTL_ENDTRANSACTION, Scard.IOCTL_DISCONNECT, Scard.IOCTL_CANCEL,
                Scard.IOCTL_WRITECACHEW}) {
            for (Exchange e : of(ioctl)) {
                assertReply(e, ScardNdr.empty());
            }
        }
    }

    @Test
    void establishAndConnectRepliesMatch() throws Exception {
        for (Exchange e : of(Scard.IOCTL_ESTABLISHCONTEXT)) {
            long ctx = le64(e.body(), 12);
            assertReply(e, ScardNdr.contextReturn(ctx));
        }
        for (Exchange e : of(Scard.IOCTL_CONNECTW)) {
            byte[] b = e.body();
            long ctx = le64(b, 24);
            long card = le64(b, 36);
            assertReply(e, ScardNdr.connectReturn(ctx, card, ScardNdr.le32(b, 16)));
        }
    }

    @Test
    void listReadersAndStatusRepliesMatch() throws Exception {
        for (Exchange e : of(Scard.IOCTL_LISTREADERSW)) {
            assertReply(e, ScardNdr.multiStringReturn(ScardNdr.multiString(List.of("Virtual PCD 00 00"), true)));
        }
        for (Exchange e : of(Scard.IOCTL_STATUSW)) {
            byte[] b = e.body();
            int namesLen = ScardNdr.le32(b, 0);
            int state = ScardNdr.le32(b, 8);
            int protocol = ScardNdr.le32(b, 12);
            byte[] atr = Arrays.copyOfRange(b, 16, 16 + ScardNdr.le32(b, 48));
            byte[] names = namesLen == 0 ? new byte[0] : Arrays.copyOfRange(b, 56, 56 + namesLen);
            assertReply(e, ScardNdr.statusReturn(names, state, protocol, atr));
            if (namesLen > 0) {
                assertArrayEquals(ScardNdr.multiString(List.of("Virtual PCD 00 00"), true), names);
            }
        }
    }

    @Test
    void statusChangeRepliesMatch() throws Exception {
        for (Exchange e : of(Scard.IOCTL_GETSTATUSCHANGEW)) {
            byte[] b = e.body();
            int n = ScardNdr.le32(b, 0);
            List<ScardNdr.ReaderStateOut> states = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int o = 12 + i * 48;
                states.add(new ScardNdr.ReaderStateOut(ScardNdr.le32(b, o), ScardNdr.le32(b, o + 4),
                        Arrays.copyOfRange(b, o + 12, o + 12 + ScardNdr.le32(b, o + 8))));
            }
            assertReply(e, ScardNdr.readerStatesReturn(states));
        }
    }

    @Test
    void transmitAndCacheRepliesMatch() throws Exception {
        for (Exchange e : of(Scard.IOCTL_TRANSMIT)) {
            ScardNdr.Transmit t = (ScardNdr.Transmit) ScardNdr.decode(e.in()).call();
            byte[] b = e.body();
            int respLen = ScardNdr.le32(b, 4);
            byte[] resp = Arrays.copyOfRange(b, b.length - pad4(respLen), b.length - pad4(respLen) + respLen);
            assertReply(e, ScardNdr.transmitReturn(t.recvPci(), resp));
        }
        for (Exchange e : of(Scard.IOCTL_READCACHEW)) {
            byte[] b = e.body();
            int len = ScardNdr.le32(b, 0);
            assertReply(e, ScardNdr.bufferReturn(len == 0 ? new byte[0] : Arrays.copyOfRange(b, 12, 12 + len)));
        }
        for (Exchange e : of(Scard.IOCTL_GETDEVICETYPEID)) {
            assertReply(e, ScardNdr.u32Return(Scard.READER_TYPE_USB));
        }
    }

    @Test
    void aReplyTooLargeForTheServersBufferAsksForABiggerOne() {
        ScardNdr.Reply r = ScardNdr.reply(Scard.IOCTL_TRANSMIT, 16, Scard.S_SUCCESS, new byte[40]);
        assertEquals(Scard.STATUS_BUFFER_TOO_SMALL, r.ioStatus());
        assertEquals(0, ScardNdr.le32(r.output(), 0));
        assertEquals(Scard.STATUS_BUFFER_TOO_SMALL, ScardNdr.le32(r.output(), 20));
    }

    // ---- hostile input ----

    @Test
    void truncatedCallsAreMalformed_neverAnotherException() throws Exception {
        for (Exchange e : fixtures()) {
            for (int cut = 0; cut < e.in().length; cut += Math.max(1, e.in().length / 23)) {
                byte[] in = Arrays.copyOf(e.in(), cut);
                // Keep the declared input length consistent, so the body itself is what's short.
                if (cut >= 8) {
                    ScardNdr.put32(in, 4, Math.max(0, cut - 32));
                }
                try {
                    ScardNdr.decode(in);
                } catch (NdrReader.Malformed expected) {
                    // fine
                } catch (RuntimeException r) {
                    fail(Scard.ioctlName(e.ioctl()) + " cut at " + cut + ": " + r);
                }
            }
        }
    }

    @Test
    void garbledCallsAreMalformed_neverAnotherException() throws Exception {
        Random rnd = new Random(42);
        for (Exchange e : fixtures()) {
            for (int i = 0; i < 200; i++) {
                byte[] in = e.in().clone();
                int flips = 1 + rnd.nextInt(4);
                for (int f = 0; f < flips; f++) {
                    int at = 32 + rnd.nextInt(Math.max(1, in.length - 32));
                    if (at < in.length) {
                        in[at] = (byte) rnd.nextInt(256);
                    }
                }
                try {
                    ScardNdr.decode(in);
                } catch (NdrReader.Malformed expected) {
                    // fine
                } catch (RuntimeException r) {
                    fail(Scard.ioctlName(e.ioctl()) + ": " + r);
                }
            }
        }
    }

    @Test
    void hugeCountsAreRefusedBeforeAnythingIsAllocated() throws Exception {
        Exchange e = of(Scard.IOCTL_GETSTATUSCHANGEW).get(0);
        byte[] in = e.in().clone();
        // cReaders sits after the context header (8 bytes) and the timeout, in the body at 32 + 16.
        ScardNdr.put32(in, 32 + 16 + 8 + 4, 0x7FFFFFFF);
        assertThrows(NdrReader.Malformed.class, () -> ScardNdr.decode(in));
    }

    @Test
    void refusedCallsAreNotEvenDecoded() throws Exception {
        byte[] in = new byte[32];
        ScardNdr.put32(in, 8, Scard.IOCTL_INTRODUCEREADERW);
        assertInstanceOf(ScardNdr.Refused.class, ScardNdr.decode(in).call());
        ScardNdr.put32(in, 8, Scard.IOCTL_SETATTRIB);
        assertInstanceOf(ScardNdr.Refused.class, ScardNdr.decode(in).call());
        ScardNdr.put32(in, 8, 0x00090200);
        ScardNdr.put32(in, 4, 0);
        assertThrows(NdrReader.Malformed.class, () -> ScardNdr.decode(in), "unknown IOCTL");
    }

    private static int pad4(int n) {
        return (n + 3) & ~3;
    }

    private static long le64(byte[] b, int o) {
        long x = 0;
        for (int i = 7; i >= 0; i--) {
            x = (x << 8) | (b[o + i] & 0xFF);
        }
        return x;
    }
}
