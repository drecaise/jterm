package com.katmoda.jterm.terminal.rdp.smartcard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the server's smart-card calls (MS-RDPESC over an {@code IRP_MJ_DEVICE_CONTROL}) and
 * encodes the replies. Pure and Swing-free: the bytes are hostile, so every length is checked
 * before use and everything is capped; a call that doesn't decode is answered as a failed request
 * ({@link #malformed()}), never thrown to the caller. Layouts follow MS-RDPESC as Windows sends it
 * (checked against captured calls) and as FreeRDP 3.33 reads it.
 *
 * <p>Contexts and card handles travel as opaque byte strings that the server echoes back; here
 * they are longs (0 for an empty one). jterm hands out its own 8-byte values ({@link ScardService}),
 * so a value of any other length never matches.
 */
public final class ScardNdr {

    private ScardNdr() {
    }

    /** Longest string a call may carry, in characters (reader names, cache names). */
    static final int MAX_STRING = 1024;
    /** Most reader states in one status-change call (FreeRDP's limit, MS-RDPESC's 11). */
    static final int MAX_READER_STATES = 11;
    /** Largest transmit or control buffer (MS-RDPESC: 66 560 bytes). */
    static final int MAX_BUFFER = 66560;
    /** Largest cache item and multi-string. */
    static final int MAX_DATA = 65536;
    /** Most ATR masks in one locate-by-ATR call. */
    static final int MAX_ATR_MASKS = 64;
    /** Extra protocol bytes of an SCARD_IO_REQUEST. */
    static final int MAX_PCI_EXTRA = 1024;
    /** Size of an ATR field in a reader state. */
    static final int ATR_FIELD = 36;

    // ---- requests ----

    /** One reader in a status-change or locate call. {@code reader} is "" when the server sent none. */
    public record ReaderState(String reader, int currentState, int eventState, byte[] atr) {
    }

    /** An ATR and its mask (LocateCardsByATR); both {@code atr.length} bytes long. */
    public record AtrMask(byte[] atr, byte[] mask) {
    }

    /** The protocol control information of a transmit: protocol and the extra bytes after it. */
    public record Pci(int protocol, byte[] extra) {
    }

    /** A decoded call. {@code ioctl()} is the code it came with. */
    public sealed interface Call {
        int ioctl();

        /** Whether strings in this call and its return are UTF-16 (the W form) rather than ANSI. */
        default boolean unicode() {
            return isUnicode(ioctl());
        }
    }

    public record EstablishContext(int ioctl, int scope) implements Call {
    }

    /** ReleaseContext, IsValidContext, Cancel. */
    public record ContextCall(int ioctl, long context) implements Call {
    }

    public record ListReaderGroups(int ioctl, long context) implements Call {
    }

    public record ListReaders(int ioctl, long context) implements Call {
    }

    public record GetStatusChange(int ioctl, long context, int timeout, List<ReaderState> states) implements Call {
    }

    public record LocateCardsByAtr(int ioctl, long context, List<AtrMask> masks, List<ReaderState> states)
            implements Call {
    }

    public record Connect(int ioctl, long context, String reader, int shareMode, int protocols) implements Call {
    }

    public record Reconnect(int ioctl, long context, long card, int shareMode, int protocols, int initialization)
            implements Call {
    }

    /** Disconnect, BeginTransaction, EndTransaction. */
    public record CardDisposition(int ioctl, long context, long card, int disposition) implements Call {
    }

    public record State(int ioctl, long context, long card) implements Call {
    }

    public record Status(int ioctl, long context, long card, boolean namesNull) implements Call {
    }

    /** {@code recvPci} null when the server passed none. */
    public record Transmit(int ioctl, long context, long card, Pci sendPci, byte[] send, Pci recvPci,
                           boolean recvNull, int recvLength) implements Call {
    }

    public record Control(int ioctl, long context, long card, int code, byte[] in, boolean outNull, int outLength)
            implements Call {
    }

    public record GetAttrib(int ioctl, long context, long card, int attrId, boolean attrNull, int attrLength)
            implements Call {
    }

    /** {@code cardId} is 16 bytes, or null when the server sent none. */
    public record ReadCache(int ioctl, long context, String name, byte[] cardId, int freshness, boolean dataNull,
                            int dataLength) implements Call {
    }

    public record WriteCache(int ioctl, long context, String name, byte[] cardId, int freshness, byte[] data)
            implements Call {
    }

    public record GetTransmitCount(int ioctl, long context, long card) implements Call {
    }

    /** GetReaderIcon, GetDeviceTypeId. */
    public record ContextAndReader(int ioctl, long context, String reader) implements Call {
    }

    public record AccessStartedEvent(int ioctl) implements Call {
    }

    /** A call jterm refuses whatever it says (S3): it is not even decoded. */
    public record Refused(int ioctl) implements Call {
    }

    /** A request as it arrived: the server's output buffer limit and the decoded call. */
    public record Request(int outputBufferLength, Call call) {
    }

    /**
     * Decodes the bytes of a device-control request after the 24-byte device I/O header (what the
     * helper forwards): output length, input length, IOCTL code, 20 bytes of padding, the call.
     */
    public static Request decode(byte[] input) throws NdrReader.Malformed {
        NdrReader r = new NdrReader(input, 0, input.length);
        int outLen = r.u32();
        int inLen = r.u32();
        int ioctl = r.u32();
        r.skip(20);
        if (inLen != r.remaining()) {
            throw new NdrReader.Malformed("input length " + inLen + " but " + r.remaining() + " bytes");
        }
        if (ioctl == Scard.IOCTL_ACCESSSTARTEDEVENT || ioctl == Scard.IOCTL_RELEASESTARTEDEVENT) {
            return new Request(outLen, ioctl == Scard.IOCTL_ACCESSSTARTEDEVENT
                    ? new AccessStartedEvent(ioctl) : new Refused(ioctl));
        }
        Call refused = refusedOrNull(ioctl);
        if (refused != null) {
            return new Request(outLen, refused);
        }
        // Common type header: version 1, little-endian, length 8, filler 0xCCCCCCCC.
        if (r.u8() != 1 || r.u8() != 0x10 || r.u16() != 8 || r.u32() != 0xCCCCCCCC) {
            throw new NdrReader.Malformed("common type header");
        }
        int objectLength = r.u32();
        if (r.u32() != 0 || objectLength < 0 || objectLength > r.remaining()) {
            throw new NdrReader.Malformed("private type header");
        }
        NdrReader body = new NdrReader(input, r.position(), objectLength);
        return new Request(outLen, decodeCall(ioctl, body));
    }

    private static Call refusedOrNull(int ioctl) {
        return switch (ioctl) {
            case Scard.IOCTL_INTRODUCEREADERGROUPA, Scard.IOCTL_INTRODUCEREADERGROUPW,
                 Scard.IOCTL_FORGETREADERGROUPA, Scard.IOCTL_FORGETREADERGROUPW,
                 Scard.IOCTL_INTRODUCEREADERA, Scard.IOCTL_INTRODUCEREADERW,
                 Scard.IOCTL_FORGETREADERA, Scard.IOCTL_FORGETREADERW,
                 Scard.IOCTL_ADDREADERTOGROUPA, Scard.IOCTL_ADDREADERTOGROUPW,
                 Scard.IOCTL_REMOVEREADERFROMGROUPA, Scard.IOCTL_REMOVEREADERFROMGROUPW,
                 Scard.IOCTL_SETATTRIB, Scard.IOCTL_LOCATECARDSA, Scard.IOCTL_LOCATECARDSW -> new Refused(ioctl);
            default -> null;
        };
    }

    /** The W forms (and the calls that only exist as W). The A/W codes don't differ by one bit. */
    static boolean isUnicode(int ioctl) {
        return switch (ioctl) {
            case Scard.IOCTL_LISTREADERGROUPSW, Scard.IOCTL_LISTREADERSW, Scard.IOCTL_GETSTATUSCHANGEW,
                 Scard.IOCTL_CONNECTW, Scard.IOCTL_STATUSW, Scard.IOCTL_LOCATECARDSBYATRW,
                 Scard.IOCTL_READCACHEW, Scard.IOCTL_WRITECACHEW, Scard.IOCTL_GETREADERICON,
                 Scard.IOCTL_GETDEVICETYPEID -> true;
            default -> false;
        };
    }

    private static Call decodeCall(int ioctl, NdrReader r) throws NdrReader.Malformed {
        boolean w = isUnicode(ioctl);
        switch (ioctl) {
            case Scard.IOCTL_ESTABLISHCONTEXT:
                return new EstablishContext(ioctl, r.u32());
            case Scard.IOCTL_RELEASECONTEXT, Scard.IOCTL_ISVALIDCONTEXT, Scard.IOCTL_CANCEL: {
                Handle ctx = handleHeader(r);
                return new ContextCall(ioctl, handleRef(r, ctx));
            }
            case Scard.IOCTL_LISTREADERGROUPSA, Scard.IOCTL_LISTREADERGROUPSW: {
                Handle ctx = handleHeader(r);
                r.u32(); // fmszGroupsIsNULL
                r.u32(); // cchGroups
                return new ListReaderGroups(ioctl, handleRef(r, ctx));
            }
            case Scard.IOCTL_LISTREADERSA, Scard.IOCTL_LISTREADERSW: {
                Handle ctx = handleHeader(r);
                int cBytes = r.u32();
                if (cBytes < 0 || cBytes > MAX_DATA || (w && (cBytes & 1) != 0)) {
                    throw new NdrReader.Malformed("groups length");
                }
                boolean groups = r.pointer(true);
                r.u32(); // fmszReadersIsNULL: ignored, the whole list is returned (as FreeRDP does)
                r.u32(); // cchReaders
                long context = handleRef(r, ctx);
                if (groups) {
                    r.conformant(cBytes, 1, MAX_DATA); // the groups: only the default one exists here
                }
                return new ListReaders(ioctl, context);
            }
            case Scard.IOCTL_GETSTATUSCHANGEA, Scard.IOCTL_GETSTATUSCHANGEW: {
                Handle ctx = handleHeader(r);
                int timeout = r.u32();
                int count = r.u32();
                if (count < 0 || count > MAX_READER_STATES) {
                    throw new NdrReader.Malformed("reader states " + count);
                }
                boolean states = r.pointer(true);
                long context = handleRef(r, ctx);
                if (!states) {
                    throw new NdrReader.Malformed("no reader states");
                }
                return new GetStatusChange(ioctl, context, timeout, readerStates(r, count, w));
            }
            case Scard.IOCTL_LOCATECARDSBYATRA, Scard.IOCTL_LOCATECARDSBYATRW: {
                Handle ctx = handleHeader(r);
                int atrs = r.u32();
                if (atrs < 0 || atrs > MAX_ATR_MASKS) {
                    throw new NdrReader.Malformed("ATR masks " + atrs);
                }
                boolean masksPtr = r.pointer(true);
                int count = r.u32();
                if (count < 0 || count > MAX_READER_STATES) {
                    throw new NdrReader.Malformed("reader states " + count);
                }
                boolean statesPtr = r.pointer(true);
                long context = handleRef(r, ctx);
                if (masksPtr != (atrs != 0)) {
                    throw new NdrReader.Malformed("ATR mask pointer");
                }
                List<AtrMask> masks = new ArrayList<>();
                if (masksPtr) {
                    // LocateCards_ATRMask: cbAtr, rgbAtr[36], rgbMask[36]
                    byte[] raw = r.conformant(atrs, 4 + 2 * ATR_FIELD, MAX_ATR_MASKS);
                    for (int i = 0; i < atrs; i++) {
                        int o = i * (4 + 2 * ATR_FIELD);
                        int len = le32(raw, o);
                        if (len < 0 || len > ATR_FIELD) {
                            throw new NdrReader.Malformed("ATR mask length");
                        }
                        masks.add(new AtrMask(Arrays.copyOfRange(raw, o + 4, o + 4 + len),
                                Arrays.copyOfRange(raw, o + 4 + ATR_FIELD, o + 4 + ATR_FIELD + len)));
                    }
                }
                List<ReaderState> states = statesPtr ? readerStates(r, count, w) : List.of();
                return new LocateCardsByAtr(ioctl, context, masks, states);
            }
            case Scard.IOCTL_CONNECTA, Scard.IOCTL_CONNECTW: {
                r.pointer(false); // szReader
                Handle ctx = handleHeader(r);
                int share = r.u32();
                int protocols = r.u32();
                String reader = r.string(w, MAX_STRING);
                return new Connect(ioctl, handleRef(r, ctx), reader, share, protocols);
            }
            case Scard.IOCTL_RECONNECT: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                int share = r.u32();
                int protocols = r.u32();
                int init = r.u32();
                long context = handleRef(r, ctx);
                return new Reconnect(ioctl, context, handleRef(r, card), share, protocols, init);
            }
            case Scard.IOCTL_DISCONNECT, Scard.IOCTL_BEGINTRANSACTION, Scard.IOCTL_ENDTRANSACTION: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                int disposition = r.u32();
                long context = handleRef(r, ctx);
                return new CardDisposition(ioctl, context, handleRef(r, card), disposition);
            }
            case Scard.IOCTL_STATE: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                r.u32(); // fpbAtrIsNULL
                r.u32(); // cbAtrLen
                long context = handleRef(r, ctx);
                return new State(ioctl, context, handleRef(r, card));
            }
            case Scard.IOCTL_STATUSA, Scard.IOCTL_STATUSW: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                boolean namesNull = r.u32() != 0;
                r.u32(); // cchReaderLen
                r.u32(); // cbAtrLen: unused (MS-RDPESC 2.2.2.18)
                long context = handleRef(r, ctx);
                return new Status(ioctl, context, handleRef(r, card), namesNull);
            }
            case Scard.IOCTL_TRANSMIT: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                int sendProtocol = r.u32();
                int sendExtraLen = r.u32();
                boolean sendExtraPtr = r.pointer(true);
                int sendLen = r.u32();
                boolean sendPtr = r.pointer(true);
                boolean recvPciPtr = r.pointer(true);
                boolean recvNull = r.u32() != 0;
                int recvLen = r.u32();
                if (sendExtraLen < 0 || sendExtraLen > MAX_PCI_EXTRA || sendLen < 0 || sendLen > MAX_BUFFER) {
                    throw new NdrReader.Malformed("transmit lengths");
                }
                if (sendExtraLen != 0 && !sendExtraPtr) {
                    throw new NdrReader.Malformed("send PCI extra bytes");
                }
                long context = handleRef(r, ctx);
                long hcard = handleRef(r, card);
                byte[] sendExtra = sendExtraPtr ? r.conformant(sendExtraLen, 1, MAX_PCI_EXTRA) : new byte[0];
                byte[] send = sendPtr ? r.conformant(sendLen, 1, MAX_BUFFER) : new byte[0];
                Pci recvPci = null;
                if (recvPciPtr) {
                    int protocol = r.u32();
                    int extraLen = r.u32();
                    boolean extraPtr = r.pointer(true);
                    if (extraLen < 0 || extraLen > MAX_PCI_EXTRA || (extraLen != 0 && !extraPtr)) {
                        throw new NdrReader.Malformed("receive PCI");
                    }
                    recvPci = new Pci(protocol, extraPtr ? r.conformant(extraLen, 1, MAX_PCI_EXTRA) : new byte[0]);
                }
                return new Transmit(ioctl, context, hcard, new Pci(sendProtocol, sendExtra), send, recvPci,
                        recvNull, recvLen);
            }
            case Scard.IOCTL_CONTROL: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                int code = r.u32();
                int inLen = r.u32();
                if (inLen < 0 || inLen > MAX_BUFFER) {
                    throw new NdrReader.Malformed("control length");
                }
                boolean inPtr = r.pointer(true);
                boolean outNull = r.u32() != 0;
                int outLen = r.u32();
                long context = handleRef(r, ctx);
                long hcard = handleRef(r, card);
                byte[] in = inPtr ? r.conformant(inLen, 1, MAX_BUFFER) : new byte[0];
                return new Control(ioctl, context, hcard, code, in, outNull, outLen);
            }
            case Scard.IOCTL_GETATTRIB: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                int attr = r.u32();
                boolean attrNull = r.u32() != 0;
                int attrLen = r.u32();
                long context = handleRef(r, ctx);
                return new GetAttrib(ioctl, context, handleRef(r, card), attr, attrNull, attrLen);
            }
            case Scard.IOCTL_READCACHEA, Scard.IOCTL_READCACHEW: {
                boolean namePtr = r.pointer(true);
                Handle ctx = handleHeader(r);
                boolean idPtr = r.pointer(true);
                int freshness = r.u32();
                boolean dataNull = r.u32() != 0;
                int dataLen = r.u32();
                String name = namePtr ? r.string(w, MAX_STRING) : "";
                long context = handleRef(r, ctx);
                byte[] id = idPtr ? r.bytes(16) : null;
                return new ReadCache(ioctl, context, name, id, freshness, dataNull, dataLen);
            }
            case Scard.IOCTL_WRITECACHEA, Scard.IOCTL_WRITECACHEW: {
                boolean namePtr = r.pointer(true);
                Handle ctx = handleHeader(r);
                boolean idPtr = r.pointer(true);
                int freshness = r.u32();
                int dataLen = r.u32();
                if (dataLen < 0 || dataLen > MAX_DATA) {
                    throw new NdrReader.Malformed("cache data length");
                }
                boolean dataPtr = r.pointer(true);
                String name = namePtr ? r.string(w, MAX_STRING) : "";
                long context = handleRef(r, ctx);
                byte[] id = idPtr ? r.bytes(16) : null;
                byte[] data = dataPtr ? r.conformant(dataLen, 1, MAX_DATA) : new byte[0];
                return new WriteCache(ioctl, context, name, id, freshness, data);
            }
            case Scard.IOCTL_GETTRANSMITCOUNT: {
                Handle ctx = handleHeader(r);
                Handle card = handleHeader(r);
                long context = handleRef(r, ctx);
                return new GetTransmitCount(ioctl, context, handleRef(r, card));
            }
            case Scard.IOCTL_GETREADERICON, Scard.IOCTL_GETDEVICETYPEID: {
                // Unicode only (MS-RDPESC has no A form of these).
                Handle ctx = handleHeader(r);
                r.pointer(false);
                long context = handleRef(r, ctx);
                return new ContextAndReader(ioctl, context, r.string(true, MAX_STRING));
            }
            default:
                throw new NdrReader.Malformed("unknown IOCTL " + Scard.ioctlName(ioctl));
        }
    }

    /** The fixed part of a REDIR_SCARDCONTEXT / REDIR_SCARDHANDLE: length and pointer. */
    private record Handle(int length, boolean present) {
    }

    private static Handle handleHeader(NdrReader r) throws NdrReader.Malformed {
        int len = r.u32();
        if (len != 0 && len != 4 && len != 8) {
            throw new NdrReader.Malformed("handle length " + len);
        }
        boolean present = r.pointer(true);
        if (present != (len != 0)) {
            throw new NdrReader.Malformed("handle pointer");
        }
        return new Handle(len, present);
    }

    private static long handleRef(NdrReader r, Handle h) throws NdrReader.Malformed {
        if (!h.present()) {
            return 0;
        }
        if (r.u32() != h.length()) {
            throw new NdrReader.Malformed("handle length mismatch");
        }
        byte[] v = r.bytes(h.length());
        long x = 0;
        for (int i = v.length - 1; i >= 0; i--) {
            x = (x << 8) | (v[i] & 0xFF);
        }
        // A 4-byte value is kept apart from every 8-byte one jterm hands out (those have bit 63 set).
        return x;
    }

    private static List<ReaderState> readerStates(NdrReader r, int count, boolean unicode) throws NdrReader.Malformed {
        int arrayCount = r.u32();
        if (arrayCount != count) {
            throw new NdrReader.Malformed("reader state count");
        }
        boolean[] named = new boolean[count];
        int[] current = new int[count];
        int[] event = new int[count];
        byte[][] atr = new byte[count][];
        for (int i = 0; i < count; i++) {
            named[i] = r.pointer(true);
            current[i] = r.u32();
            event[i] = r.u32();
            int cbAtr = Math.min(Math.max(r.u32(), 0), ATR_FIELD);
            byte[] field = r.bytes(ATR_FIELD);
            atr[i] = Arrays.copyOf(field, cbAtr);
        }
        List<ReaderState> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = named[i] ? r.string(unicode, MAX_STRING) : "";
            out.add(new ReaderState(name, current[i], event[i], atr[i]));
        }
        return out;
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    // ---- replies ----

    /** What goes back for the IRP: its NTSTATUS and the bytes after it (OutputBufferLength first). */
    public record Reply(int ioStatus, byte[] output) {
    }

    /** The answer to a request that didn't decode (FreeRDP answers the same). */
    public static Reply malformed() {
        return new Reply(Scard.STATUS_UNSUCCESSFUL, new byte[4]);
    }

    /**
     * Frames a return structure: common and private type headers, the return code, {@code body},
     * padded to a multiple of 8 (MS-RPCE 2.2.6.3; not for the started-event calls, as FreeRDP
     * writes them). When that exceeds the server's output buffer the IRP fails with
     * STATUS_BUFFER_TOO_SMALL and the server retries with a larger one (MS-RDPESC 3.2.5.2).
     */
    public static Reply reply(int ioctl, int outputBufferLength, int result, byte[] body) {
        boolean pad = ioctl != Scard.IOCTL_ACCESSSTARTEDEVENT && ioctl != Scard.IOCTL_RELEASESTARTEDEVENT;
        int unpadded = 8 + 8 + 4 + body.length;
        int ndrLength = pad ? (unpadded + 7) & ~7 : unpadded;
        boolean tooSmall = Integer.compareUnsigned(ndrLength, outputBufferLength) > 0;
        NdrWriter w = new NdrWriter();
        w.u32(tooSmall ? 0 : ndrLength);
        w.bytes(new byte[]{1, 0x10, 8, 0, (byte) 0xCC, (byte) 0xCC, (byte) 0xCC, (byte) 0xCC});
        w.u32(tooSmall ? 0 : ndrLength - 16).u32(0);
        if (tooSmall) {
            w.u32(Scard.STATUS_BUFFER_TOO_SMALL);
            return new Reply(Scard.STATUS_BUFFER_TOO_SMALL, w.toByteArray());
        }
        w.u32(result).bytes(body).zeros(ndrLength - unpadded);
        int ioStatus = (result & 0xC0000000) == 0xC0000000 ? result : Scard.STATUS_SUCCESS;
        return new Reply(ioStatus, w.toByteArray());
    }

    /** No body: a Long_Return (and every return whose failure carries none, as FreeRDP writes it). */
    public static byte[] empty() {
        return new byte[0];
    }

    /** EstablishContext's return: the new context. */
    public static byte[] contextReturn(long context) {
        NdrWriter w = new NdrWriter();
        w.u32(8).pointer(true);
        w.u32(8).bytes(le64(context));
        return w.toByteArray();
    }

    /** ListReaders / ListReaderGroups: a multi-string ({@code msz}, already encoded and terminated). */
    public static byte[] multiStringReturn(byte[] msz) {
        NdrWriter w = new NdrWriter();
        w.u32(msz.length).pointer(msz.length > 0).conformant(msz, msz.length);
        return w.toByteArray();
    }

    /** One reader's state as a status-change or locate call returns it. */
    public record ReaderStateOut(int currentState, int eventState, byte[] atr) {
    }

    public static byte[] readerStatesReturn(List<ReaderStateOut> states) {
        byte[] raw = new byte[states.size() * (12 + ATR_FIELD)];
        for (int i = 0; i < states.size(); i++) {
            ReaderStateOut s = states.get(i);
            int o = i * (12 + ATR_FIELD);
            put32(raw, o, s.currentState());
            put32(raw, o + 4, s.eventState());
            int n = Math.min(s.atr().length, ATR_FIELD);
            put32(raw, o + 8, n);
            System.arraycopy(s.atr(), 0, raw, o + 12, n);
        }
        NdrWriter w = new NdrWriter();
        w.u32(states.size()).pointer(!states.isEmpty()).conformant(raw, states.size());
        return w.toByteArray();
    }

    public static byte[] connectReturn(long context, long card, int activeProtocol) {
        NdrWriter w = new NdrWriter();
        w.u32(8).pointer(true);
        w.u32(8).pointer(true);
        w.u32(activeProtocol);
        w.u32(8).bytes(le64(context));
        w.u32(8).bytes(le64(card));
        return w.toByteArray();
    }

    /** Reconnect, GetTransmitCount, GetDeviceTypeId: one u32. */
    public static byte[] u32Return(int value) {
        return new NdrWriter().u32(value).toByteArray();
    }

    public static byte[] stateReturn(int state, int protocol, byte[] atr) {
        NdrWriter w = new NdrWriter();
        w.u32(state).u32(protocol).u32(atr.length).pointer(atr.length > 0).conformant(atr, atr.length);
        return w.toByteArray();
    }

    /** Status: reader names (encoded multi-string, may be empty), state, protocol, ATR (≤ 32). */
    public static byte[] statusReturn(byte[] names, int state, int protocol, byte[] atr) {
        int n = Math.min(atr.length, 32);
        NdrWriter w = new NdrWriter();
        w.u32(names.length).pointer(names.length > 0);
        w.u32(state).u32(protocol).bytes(Arrays.copyOf(atr, 32)).u32(n);
        w.conformant(names, names.length);
        return w.toByteArray();
    }

    /** Transmit: the receive PCI if the call passed one, and the response. */
    public static byte[] transmitReturn(Pci recvPci, byte[] response) {
        NdrWriter w = new NdrWriter();
        w.pointer(recvPci != null);
        w.u32(response.length).pointer(response.length > 0);
        if (recvPci != null) {
            w.u32(recvPci.protocol()).u32(recvPci.extra().length).pointer(recvPci.extra().length > 0);
            w.conformant(recvPci.extra(), recvPci.extra().length);
        }
        w.conformant(response, response.length);
        return w.toByteArray();
    }

    /** Control, GetAttrib, ReadCache, GetReaderIcon: a byte buffer. */
    public static byte[] bufferReturn(byte[] data) {
        NdrWriter w = new NdrWriter();
        w.u32(data.length).pointer(data.length > 0).conformant(data, data.length);
        return w.toByteArray();
    }

    // ---- helpers ----

    /** Encodes reader names as a multi-string (each NUL-terminated, a final NUL; empty list: nothing). */
    public static byte[] multiString(List<String> names, boolean unicode) {
        if (names.isEmpty()) {
            // An empty list is a lone terminator, as Windows returns it for "no readers" too.
            return unicode ? new byte[2] : new byte[1];
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            sb.append(n).append('\0');
        }
        sb.append('\0');
        return sb.toString().getBytes(unicode ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
    }

    static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
}
