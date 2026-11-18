package com.katmoda.jterm.terminal.rdp.smartcard;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds requests the way Windows sends them (the layouts ScardNdrTest checks against captures). */
final class Calls {

    private Calls() {
    }

    static byte[] request(int ioctl, byte[] body) {
        NdrWriter w = new NdrWriter();
        w.u32(4096).u32(16 + body.length).u32(ioctl).zeros(20);
        w.bytes(new byte[]{1, 0x10, 8, 0, (byte) 0xCC, (byte) 0xCC, (byte) 0xCC, (byte) 0xCC});
        w.u32(body.length).u32(0).bytes(body);
        return w.toByteArray();
    }

    private static void handleHeader(NdrWriter w) {
        w.u32(8).pointer(true);
    }

    private static void handleRef(NdrWriter w, long v) {
        w.u32(8).bytes(ScardNdr.le64(v));
    }

    private static void string(NdrWriter w, String s) {
        byte[] b = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        int chars = b.length / 2;
        w.u32(chars).u32(0).u32(chars).bytes(b).zeros((4 - (b.length & 3)) & 3);
    }

    static byte[] establish() {
        return request(Scard.IOCTL_ESTABLISHCONTEXT, new NdrWriter().u32(2).toByteArray());
    }

    static byte[] context(int ioctl, long ctx) {
        NdrWriter w = new NdrWriter();
        handleHeader(w);
        handleRef(w, ctx);
        return request(ioctl, w.toByteArray());
    }

    static byte[] listReaders(long ctx) {
        NdrWriter w = new NdrWriter();
        handleHeader(w);
        w.u32(0).pointer(false).u32(0).u32(0xFFFFFFFF);
        handleRef(w, ctx);
        return request(Scard.IOCTL_LISTREADERSW, w.toByteArray());
    }

    record State(String reader, int current) {
    }

    static byte[] statusChange(long ctx, int timeout, List<State> states) {
        NdrWriter w = new NdrWriter();
        handleHeader(w);
        w.u32(timeout).u32(states.size()).pointer(true);
        handleRef(w, ctx);
        w.u32(states.size());
        for (State s : states) {
            w.pointer(true).u32(s.current()).u32(0).u32(0).zeros(36);
        }
        for (State s : states) {
            string(w, s.reader());
        }
        return request(Scard.IOCTL_GETSTATUSCHANGEW, w.toByteArray());
    }

    static byte[] connect(long ctx, String reader, int share) {
        NdrWriter w = new NdrWriter();
        w.pointer(true);
        handleHeader(w);
        w.u32(share).u32(Scard.PROTOCOL_T0 | Scard.PROTOCOL_T1);
        string(w, reader);
        handleRef(w, ctx);
        return request(Scard.IOCTL_CONNECTW, w.toByteArray());
    }

    private static NdrWriter cardCall(long ctx, long card) {
        NdrWriter w = new NdrWriter();
        handleHeader(w);
        handleHeader(w);
        return w;
    }

    static byte[] disposition(int ioctl, long ctx, long card, int disposition) {
        NdrWriter w = cardCall(ctx, card);
        w.u32(disposition);
        handleRef(w, ctx);
        handleRef(w, card);
        return request(ioctl, w.toByteArray());
    }

    static byte[] status(long ctx, long card) {
        NdrWriter w = cardCall(ctx, card);
        w.u32(0).u32(0xFFFFFFFF).u32(32);
        handleRef(w, ctx);
        handleRef(w, card);
        return request(Scard.IOCTL_STATUSW, w.toByteArray());
    }

    static byte[] transmit(long ctx, long card, byte[] apdu) {
        NdrWriter w = cardCall(ctx, card);
        w.u32(Scard.PROTOCOL_T1).u32(0).pointer(false);
        w.u32(apdu.length).pointer(true);
        w.pointer(false).u32(0).u32(258);
        handleRef(w, ctx);
        handleRef(w, card);
        w.conformant(apdu, apdu.length);
        return request(Scard.IOCTL_TRANSMIT, w.toByteArray());
    }

    static byte[] control(long ctx, long card, int code) {
        NdrWriter w = cardCall(ctx, card);
        w.u32(code).u32(0).pointer(false).u32(0).u32(1024);
        handleRef(w, ctx);
        handleRef(w, card);
        return request(Scard.IOCTL_CONTROL, w.toByteArray());
    }

    static byte[] getAttrib(long ctx, long card, int attr) {
        NdrWriter w = cardCall(ctx, card);
        w.u32(attr).u32(0).u32(0xFFFFFFFF);
        handleRef(w, ctx);
        handleRef(w, card);
        return request(Scard.IOCTL_GETATTRIB, w.toByteArray());
    }

    static byte[] readCache(long ctx, byte[] cardId, String name, int freshness) {
        NdrWriter w = new NdrWriter();
        w.pointer(true);
        handleHeader(w);
        w.pointer(true).u32(freshness).u32(0).u32(0xFFFFFFFF);
        string(w, name);
        handleRef(w, ctx);
        w.bytes(cardId);
        return request(Scard.IOCTL_READCACHEW, w.toByteArray());
    }

    static byte[] writeCache(long ctx, byte[] cardId, String name, int freshness, byte[] data) {
        NdrWriter w = new NdrWriter();
        w.pointer(true);
        handleHeader(w);
        w.pointer(true).u32(freshness).u32(data.length).pointer(data.length > 0);
        string(w, name);
        handleRef(w, ctx);
        w.bytes(cardId);
        w.conformant(data, data.length);
        return request(Scard.IOCTL_WRITECACHEW, w.toByteArray());
    }
}
