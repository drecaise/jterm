package com.katmoda.jterm.terminal.rdp.smartcard;

import java.io.ByteArrayOutputStream;

/** Writes MS-RDPESC return structures in the layout {@link NdrReader} reads (and FreeRDP writes). */
final class NdrWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
    private int pointerIndex;

    NdrWriter u32(int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
        return this;
    }

    NdrWriter bytes(byte[] b) {
        out.write(b, 0, b.length);
        return this;
    }

    NdrWriter zeros(int n) {
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
        return this;
    }

    /** A pointer to something non-empty gets the next referent id; an empty one is null. */
    NdrWriter pointer(boolean present) {
        if (present) {
            u32(0x00020000 + pointerIndex * 4);
            pointerIndex++;
        } else {
            u32(0);
        }
        return this;
    }

    /**
     * The pointee of a conformant array: count, data, padding to 4. An empty array writes nothing,
     * matching the null pointer written for it.
     */
    NdrWriter conformant(byte[] data, int count) {
        if (count == 0) {
            return this;
        }
        u32(count);
        bytes(data);
        return zeros((4 - (data.length & 3)) & 3);
    }

    int size() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
