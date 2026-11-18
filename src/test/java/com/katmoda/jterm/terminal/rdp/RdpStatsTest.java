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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpStatsTest {

    private static final String LINE = "jterm-rdp-helper: stats ms=1000 frames=30 rects=30 kib=432000 "
            + "write_us=123000 write_max_us=9000 h264=0 avc444=60 progressive=0 other=2";

    @Test
    void readsTheHelpersLine() {
        RdpStats s = RdpStats.parseHelperLine(LINE);
        assertNotNull(s);
        assertEquals(30, s.frames());
        assertEquals(432000, s.kib());
        assertEquals(123000, s.writeMicros());
        assertEquals("H.264 4:4:4", s.codec());
        assertEquals(30.0, s.fps(), 0.001);
    }

    @Test
    void otherLinesAndBrokenOnesAreNotStatistics() {
        assertNull(RdpStats.parseHelperLine(null));
        assertNull(RdpStats.parseHelperLine("jterm-rdp-helper: H.264 decoder available"));
        assertNull(RdpStats.parseHelperLine("jterm-rdp-helper: stats frames=abc"));
        assertNull(RdpStats.parseHelperLine("jterm-rdp-helper: stats ms=1000"));
    }

    @Test
    void aNewerHelpersExtraKeysAreIgnoredAndMissingOnesAreZero() {
        RdpStats s = RdpStats.parseHelperLine("x jterm-rdp-helper: stats frames=5 ms=500 decode_us=7 h264=5");
        assertNotNull(s);
        assertEquals(10.0, s.fps(), 0.001);
        assertEquals("H.264", s.codec());
        assertEquals(0, s.kib());
    }

    @Test
    void namesTheCodecMostCommandsUsed() {
        assertEquals("RemoteFX progressive", new RdpStats(1000, 1, 0, 0, 0, 1, 0, 9, 2).codec());
        assertEquals("bitmap codecs", new RdpStats(1000, 1, 0, 0, 0, 0, 0, 0, 2).codec());
        assertEquals("no codec data", new RdpStats(1000, 1, 0, 0, 0, 0, 0, 0, 0).codec());
    }

    @Test
    void summaryGivesPerFrameTimesWhateverTheLocale() {
        RdpStats s = RdpStats.parseHelperLine(LINE);
        String text = s.summary(new RdpStats.Local(30, 36_000_000L, 30, 30_000_000L, 3_000_000L, true));
        assertEquals("30 fps · H.264 4:4:4 · 442 MB/s · pipe 4.1 ms (max 9) · copy 1.2 ms · "
                + "paint 1.0 ms (max 3), 30/s, scaled", text);
        assertTrue(s.summary(null).endsWith("(max 9)"));
    }

    @Test
    void anIntervalWithNothingInItDoesNotDivideByZero() {
        RdpStats s = new RdpStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, s.fps());
        assertNotNull(s.summary(new RdpStats.Local(0, 0, 0, 0, 0, false)));
    }
}
