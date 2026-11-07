/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.rdp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The jterm side of the {@code jterm-rdp-helper} wire protocol — the single source of truth is
 * {@code native/rdp-helper/src/protocol.h}; keep the two in step.
 *
 * <p>Every message is {@code u32 length (type + payload), u8 type, payload}, little-endian;
 * strings are {@code u16 length + UTF-8}. {@link #encode} builds outbound messages; {@link #decode}
 * turns an inbound payload into an {@link Inbound} record. Swing-free and I/O-free so it is
 * unit-tested directly.</p>
 */
public final class RdpProtocol {

    public static final int VERSION = 1;

    // jterm -> helper
    static final int HELLO = 0x01;
    static final int CONNECT = 0x02;
    static final int KEY = 0x03;
    static final int UNICODE = 0x04;
    static final int MOUSE = 0x05;
    static final int CERT_REPLY = 0x06;
    static final int RESIZE = 0x07;
    static final int BYE = 0x0F;

    public static final int KEY_FORCE_EXTENDED = 0x01;
    public static final int KEY_FORCE_NOT_EXTENDED = 0x02;
    public static final int KEY_REPEAT = 0x04;

    // helper -> jterm
    static final int HELLO_REPLY = 0x81;
    static final int CONNECTED = 0x82;
    static final int FRAME = 0x83;
    static final int DESKTOP_RESIZED = 0x84;
    static final int POINTER_NEW = 0x85;
    static final int POINTER_FREE = 0x86;
    static final int POINTER_SET = 0x87;
    static final int POINTER_HIDE = 0x88;
    static final int POINTER_DEFAULT = 0x89;
    static final int CERT_REQUEST = 0x8A;
    static final int DISCONNECTED = 0x8F;

    /** Certificate flags (FreeRDP {@code VERIFY_CERT_FLAG_*}). */
    public static final int CERT_FLAG_CHANGED = 0x40;
    public static final int CERT_FLAG_MISMATCH = 0x80;

    /** Mouse flags (FreeRDP {@code PTR_FLAGS_*}). */
    public static final int PTR_MOVE = 0x0800;
    public static final int PTR_DOWN = 0x8000;
    public static final int PTR_BUTTON1 = 0x1000; // left
    public static final int PTR_BUTTON2 = 0x2000; // right
    public static final int PTR_BUTTON3 = 0x4000; // middle
    public static final int PTR_WHEEL = 0x0200;
    public static final int PTR_HWHEEL = 0x0400;
    /** One wheel notch away from / towards the user, as the 9-bit rotation field. */
    public static final int WHEEL_UP = 0x078;
    public static final int WHEEL_DOWN = 0x188;

    /** Largest inbound message accepted: a full 8192x8192 BGRX frame plus header. */
    static final int MAX_INBOUND = 1 + 8 + 8192 * 8192 * 4;

    private RdpProtocol() {
    }

    // ------------------------------------------------------------------------------- outbound

    /** Builder for one outbound message. */
    static final class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        private final int type;

        Writer(int type) {
            this.type = type;
        }

        Writer u8(int v) {
            buf.write(v & 0xFF);
            return this;
        }

        Writer u16(int v) {
            buf.write(v & 0xFF);
            buf.write((v >>> 8) & 0xFF);
            return this;
        }

        Writer str(String s) {
            byte[] b = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
            int n = Math.min(b.length, 0xFFFF);
            u16(n);
            buf.write(b, 0, n);
            return this;
        }

        /** The framed message. The payload copy is scrubbed, since CONNECT carries a password. */
        byte[] build() {
            byte[] payload = buf.toByteArray();
            ByteBuffer out = ByteBuffer.allocate(5 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(1 + payload.length).put((byte) type).put(payload);
            Arrays.fill(payload, (byte) 0);
            return out.array();
        }
    }

    static byte[] hello() {
        return new Writer(HELLO).u16(VERSION).build();
    }

    /**
     * CONNECT. {@code password} is copied into the message and the caller should zero its own array.
     * {@code pins} is a comma-separated {@code sha256:aa:bb:...} list (empty for none).
     */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent) {
        String pw = password != null ? new String(password) : "";
        return new Writer(CONNECT).str(host).u16(port).str(user).str(domain).str(pw).str(pins)
                .u16(width).u16(height).u16(scalePercent).build();
    }

    static byte[] key(boolean down, int windowsVk, int flags) {
        return new Writer(KEY).u8(down ? 1 : 0).u16(windowsVk).u8(flags).build();
    }

    static byte[] unicode(boolean down, char utf16Unit) {
        return new Writer(UNICODE).u8(down ? 1 : 0).u16(utf16Unit).build();
    }

    static byte[] mouse(int flags, int x, int y) {
        return new Writer(MOUSE).u16(flags).u16(clampU16(x)).u16(clampU16(y)).build();
    }

    static byte[] resize(int width, int height, int scalePercent) {
        return new Writer(RESIZE).u16(width).u16(height).u16(scalePercent).build();
    }

    static byte[] certReply(boolean accept) {
        return new Writer(CERT_REPLY).u8(accept ? 1 : 0).build();
    }

    static byte[] bye() {
        return new Writer(BYE).build();
    }

    private static int clampU16(int v) {
        return Math.max(0, Math.min(0xFFFF, v));
    }

    // -------------------------------------------------------------------------------- inbound

    /** A decoded helper message. */
    public sealed interface Inbound {
    }

    public record HelloReply(int version, String freerdpVersion) implements Inbound {
    }

    public record Connected(int width, int height, boolean unicodeInput) implements Inbound {
    }

    /**
     * A dirty rectangle. {@code pixels} is a little-endian view over BGRX pixel data (read as ints:
     * {@code 0x00RRGGBB}), row-major, {@code width} ints per row. It aliases the reader's buffer and
     * is only valid during the callback.
     */
    public record Frame(int x, int y, int width, int height, ByteBuffer pixels) implements Inbound {
    }

    public record DesktopResized(int width, int height) implements Inbound {
    }

    /** A cursor shape: {@code argb} is {@code width*height} ints, {@code 0xAARRGGBB}. */
    public record PointerNew(int id, int width, int height, int hotX, int hotY, int[] argb)
            implements Inbound {
    }

    public record PointerFree(int id) implements Inbound {
    }

    public record PointerSet(int id) implements Inbound {
    }

    public record PointerHide() implements Inbound {
    }

    public record PointerDefault() implements Inbound {
    }

    /** The server's certificate failed CA/hostname validation and isn't pinned: ask the user. */
    public record CertRequest(String host, int port, String commonName, String subject,
                              String issuer, String sha256Fingerprint, int flags) implements Inbound {

        public boolean nameMismatch() {
            return (flags & CERT_FLAG_MISMATCH) != 0;
        }
    }

    /** The last message: FreeRDP's error code (0 for a clean disconnect) and message. */
    public record Disconnected(long code, String message) implements Inbound {

        /**
         * Whether the server rejected the credentials (FreeRDP connect-class codes
         * AUTHENTICATION_FAILED, LOGON_FAILURE, WRONG_PASSWORD, NO_OR_MISSING_CREDENTIALS) — the case
         * where re-prompting for the password is the right response.
         */
        public boolean credentialsRejected() {
            return code == 0x00020009L || code == 0x00020014L || code == 0x00020015L
                    || code == 0x0002001BL;
        }
    }

    public record Unknown(int type, int length) implements Inbound {
    }

    /**
     * Decodes one message body. {@code body} is little-endian and positioned at the first payload
     * byte (after the type).
     *
     * @throws java.nio.BufferUnderflowException on a truncated message
     */
    static Inbound decode(int type, ByteBuffer body) {
        body.order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case HELLO_REPLY -> new HelloReply(u16(body), str(body));
            case CONNECTED -> new Connected(u16(body), u16(body), body.get() != 0);
            case FRAME -> {
                int x = u16(body), y = u16(body), w = u16(body), h = u16(body);
                int bytes = w * h * 4;
                if (body.remaining() < bytes) {
                    throw new java.nio.BufferUnderflowException();
                }
                ByteBuffer pixels = body.slice(body.position(), bytes).order(ByteOrder.LITTLE_ENDIAN);
                yield new Frame(x, y, w, h, pixels);
            }
            case DESKTOP_RESIZED -> new DesktopResized(u16(body), u16(body));
            case POINTER_NEW -> {
                int id = body.getInt();
                int w = u16(body), h = u16(body), hx = u16(body), hy = u16(body);
                int[] argb = new int[w * h];
                body.asIntBuffer().get(argb);
                yield new PointerNew(id, w, h, hx, hy, argb);
            }
            case POINTER_FREE -> new PointerFree(body.getInt());
            case POINTER_SET -> new PointerSet(body.getInt());
            case POINTER_HIDE -> new PointerHide();
            case POINTER_DEFAULT -> new PointerDefault();
            case CERT_REQUEST -> new CertRequest(str(body), u16(body), str(body), str(body),
                    str(body), str(body), body.getInt());
            case DISCONNECTED -> new Disconnected(Integer.toUnsignedLong(body.getInt()), str(body));
            default -> new Unknown(type, body.remaining());
        };
    }

    private static int u16(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }

    private static String str(ByteBuffer b) {
        int n = u16(b);
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }
}
