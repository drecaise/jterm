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
package com.katmoda.jterm.terminal.rdp.audio;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GainTest {

    private static byte[] s16(int... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[2 * i] = (byte) samples[i];
            b[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return b;
    }

    private static int at(byte[] b, int i) {
        return (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
    }

    @Test
    void unityLeavesBytesIdentical() {
        byte[] b = s16(-32768, 32767, 1, -1, 1234, -4321);
        byte[] copy = b.clone();
        Gain.apply(b, 0, b.length, 2, Gain.UNITY, Gain.UNITY);
        assertArrayEquals(copy, b);
        Gain.apply(b, 0, b.length, 1, Gain.UNITY, 0);
        assertArrayEquals(copy, b);
        Gain.apply(b, 0, b.length, 2, 100000, 70000);
        assertArrayEquals(copy, b);
    }

    @Test
    void zeroIsSilence() {
        byte[] b = s16(-32768, 32767, 5, -5);
        Gain.apply(b, 0, b.length, 2, 0, 0);
        assertArrayEquals(new byte[8], b);
        b = s16(-32768, 32767);
        Gain.apply(b, 0, b.length, 1, -5, 0);
        assertArrayEquals(new byte[4], b);
    }

    @Test
    void stereoScalesPerChannel() {
        byte[] b = s16(1000, 1000, -1000, -1000);
        Gain.apply(b, 0, b.length, 2, Gain.UNITY, 0);
        assertEquals(1000, at(b, 0));
        assertEquals(0, at(b, 1));
        assertEquals(-1000, at(b, 2));
        assertEquals(0, at(b, 3));
    }

    @Test
    void monoUsesLeftOnly() {
        byte[] b = s16(1000, -1000, 1000);
        Gain.apply(b, 0, b.length, 1, 0x8000, 0);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(at(b, i)) > 400 && Math.abs(at(b, i)) <= 500, "" + at(b, i));
        }
    }

    @Test
    void neverExceedsInputMagnitude() {
        for (int level = 0; level <= Gain.UNITY; level += 251) {
            byte[] b = s16(-32768, 32767, -32768, 32767);
            Gain.apply(b, 0, b.length, 2, level, level);
            assertTrue(at(b, 0) >= -32768 && at(b, 0) <= 0);
            assertTrue(at(b, 1) >= 0 && at(b, 1) <= 32767);
            assertTrue(at(b, 2) >= -32768 && at(b, 2) <= 0);
            assertTrue(at(b, 3) >= 0 && at(b, 3) <= 32767);
        }
    }

    @Test
    void halfLevelRoughlyHalves() {
        byte[] b = s16(20000, -20000);
        Gain.apply(b, 0, b.length, 2, 0x7FFF, 0x7FFF);
        assertEquals(10000, at(b, 0), 1);
        assertEquals(-10000, at(b, 1), 1);
    }

    @Test
    void offsetAndPartialFrameRespected() {
        byte[] b = s16(500, 500, 500, 500, 500);
        // Process bytes 2..(2+6): 6 bytes is one whole stereo frame (4) plus a stray 2.
        Gain.apply(b, 2, 6, 2, 0, 0);
        assertEquals(500, at(b, 0));
        assertEquals(0, at(b, 1));
        assertEquals(0, at(b, 2));
        assertEquals(500, at(b, 3));
        assertEquals(500, at(b, 4));
    }
}
