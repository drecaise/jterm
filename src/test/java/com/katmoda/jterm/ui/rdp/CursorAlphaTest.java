package com.katmoda.jterm.ui.rdp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CursorAlphaTest {

    @Test
    void dropsTranslucentShadowAndKeepsOpaqueArrow() {
        int[] px = {
                0xFFFFFFFF, // arrow body: white, opaque
                0xFF000000, // arrow outline: black, opaque
                0x40000000, // pointer shadow: translucent black
                0x7F000000, // just below half
                0x80123456, // half: kept, colour unchanged
                0x00000000, // transparent
        };
        CursorAlpha.binarize(px);
        assertArrayEquals(new int[] {0xFFFFFFFF, 0xFF000000, 0, 0, 0xFF123456, 0}, px);
    }
}
