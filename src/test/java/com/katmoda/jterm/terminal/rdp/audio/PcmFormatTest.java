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

class PcmFormatTest {

    @Test
    void everyListedCombinationIsAccepted() {
        for (int rate : PcmFormat.RATES) {
            for (int ch = 1; ch <= 2; ch++) {
                PcmFormat f = PcmFormat.of(rate, ch).orElseThrow();
                assertEquals(rate, f.sampleRate());
                assertEquals(ch, f.channels());
            }
        }
    }

    @Test
    void neighboursAreRefused() {
        for (int rate : new int[] {7999, 8001, 44099, 96000, 0, -1}) {
            assertTrue(PcmFormat.of(rate, 2).isEmpty(), "rate " + rate);
            assertThrows(IllegalArgumentException.class, () -> new PcmFormat(rate, 2));
        }
        for (int ch : new int[] {0, 3, -1}) {
            assertTrue(PcmFormat.of(44100, ch).isEmpty(), "channels " + ch);
            assertThrows(IllegalArgumentException.class, () -> new PcmFormat(44100, ch));
        }
    }

    @Test
    void arithmetic() {
        PcmFormat s = new PcmFormat(44100, 2);
        assertEquals(4, s.frameBytes());
        assertEquals(176400, s.bytesPerSecond());
        assertEquals(17640, s.bytesForMillis(100));
        assertEquals(100, s.millisForBytes(17640));
        assertEquals(99, s.millisForBytes(17639));
        assertEquals(0, s.bytesForMillis(0));
        // 1 ms of 44.1 kHz = 44.1 frames, rounded down to 44
        assertEquals(44 * 4, s.bytesForMillis(1));
        assertEquals(8, s.wholeFrames(11));
        assertEquals(0, s.wholeFrames(3));
        assertTrue(s.isWholeFrames(12));
        assertFalse(s.isWholeFrames(13));
        PcmFormat m = new PcmFormat(8000, 1);
        assertEquals(2, m.frameBytes());
        assertEquals(16000, m.bytesPerSecond());
        assertEquals(10, m.wholeFrames(11));
    }

    @Test
    void describe() {
        assertEquals("44.1 kHz stereo", new PcmFormat(44100, 2).describe());
        assertEquals("48 kHz mono", new PcmFormat(48000, 1).describe());
        assertEquals("8 kHz mono", new PcmFormat(8000, 1).describe());
        assertEquals("11.025 kHz stereo", new PcmFormat(11025, 2).describe());
        assertEquals("22.05 kHz mono", new PcmFormat(22050, 1).describe());
    }
}
