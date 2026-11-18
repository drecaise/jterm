package com.katmoda.jterm.terminal.rdp.smartcard;

import java.nio.charset.StandardCharsets;

/**
 * Reads the MS-RPCE type serialisation (version 1, little-endian) that MS-RDPESC calls use, from
 * bytes the server sent. Every length is checked against what is present before anything is
 * allocated; anything inconsistent throws {@link Malformed}. Pointers are the referent ids Windows
 * (and FreeRDP) write, {@code 0x00020000 + 4 * n} in order of appearance, or 0 for a null pointer
 * where one is allowed; a pointee follows its structure in the same order.
 */
final class NdrReader {

    /** The call doesn't decode. Answered as a failed request, never thrown further. */
    static final class Malformed extends Exception {
        Malformed(String why) {
            super(why, null, false, false);
        }
    }

    private final byte[] b;
    private final int end;
    private int pos;
    private int pointerIndex;

    NdrReader(byte[] b, int off, int len) {
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IllegalArgumentException("range");
        }
        this.b = b;
        this.pos = off;
        this.end = off + len;
    }

    int position() {
        return pos;
    }

    int remaining() {
        return end - pos;
    }

    void need(int n) throws Malformed {
        if (n < 0 || n > end - pos) {
            throw new Malformed("truncated: need " + n + ", have " + (end - pos));
        }
    }

    int u32() throws Malformed {
        need(4);
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
        pos += 4;
        return v;
    }

    int u8() throws Malformed {
        need(1);
        return b[pos++] & 0xFF;
    }

    int u16() throws Malformed {
        need(2);
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8;
        pos += 2;
        return v;
    }

    void skip(int n) throws Malformed {
        need(n);
        pos += n;
    }

    byte[] bytes(int n) throws Malformed {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(b, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Skips the padding after {@code len} bytes of data up to a multiple of 4 (as FreeRDP aligns). */
    void padAfter(int len) throws Malformed {
        int pad = (4 - (len & 3)) & 3;
        skip(pad);
    }

    /**
     * A pointer. Returns true for the next expected referent id, false for a null pointer when
     * {@code nullable}; anything else is malformed.
     */
    boolean pointer(boolean nullable) throws Malformed {
        int v = u32();
        if (v == 0x00020000 + pointerIndex * 4) {
            pointerIndex++;
            return true;
        }
        if (v == 0 && nullable) {
            return false;
        }
        throw new Malformed("unexpected pointer 0x" + Integer.toHexString(v));
    }

    /**
     * A conformant array of {@code elementSize}-byte elements: a u32 count, the elements, padding.
     * {@code expectedCount} (or -1 for any) must match the count; at most {@code maxCount} elements.
     */
    byte[] conformant(int expectedCount, int elementSize, int maxCount) throws Malformed {
        int count = u32();
        if (count < 0 || count > maxCount) {
            throw new Malformed("array of " + Integer.toUnsignedString(count));
        }
        if (expectedCount >= 0 && count != expectedCount) {
            throw new Malformed("array count " + count + " where the structure said " + expectedCount);
        }
        int len = count * elementSize;
        byte[] out = bytes(len);
        padAfter(len);
        return out;
    }

    /**
     * A conformant varying string ([string] in the IDL): u32 maximum, u32 offset, u32 actual count,
     * the characters, padding. Returns the text up to its first NUL.
     */
    String string(boolean unicode, int maxChars) throws Malformed {
        int max = u32();
        int offset = u32();
        int actual = u32();
        if (offset != 0 || actual != max || actual < 0 || actual > maxChars) {
            throw new Malformed("string header " + max + "/" + offset + "/" + actual);
        }
        int len = unicode ? actual * 2 : actual;
        byte[] raw = bytes(len);
        padAfter(len);
        String s = unicode ? new String(raw, StandardCharsets.UTF_16LE) : new String(raw, StandardCharsets.ISO_8859_1);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }
}
