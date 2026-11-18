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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencySteeringTest {

    private static final long MS = 1_000_000L;

    /** Feeds a sawtooth (waves of {@code wave} ms, low point {@code low}) for {@code ms}, 5 ms apart. */
    private static long feed(LatencySteering s, long from, int ms, int low, int wave) {
        long cut = 0;
        for (int t = 0; t <= ms; t += 5) {
            cut += s.observe(low + (wave - t % wave) % wave, (from + t) * MS);
        }
        return cut;
    }

    @Test
    void surplusAboveTheLowPointIsReportedOncePerWindow() {
        LatencySteering s = new LatencySteering();
        assertEquals(0, s.observe(300, 0));
        assertEquals(0, s.observe(140, 500 * MS));
        assertEquals(140 - LatencySteering.START_MARGIN_MS, s.observe(310, 1000 * MS),
                "the low point counts, not the latest value");
        assertEquals(140, s.lastLowMs());
        assertEquals(0, s.observe(300, 1500 * MS), "a new window started");
    }

    @Test
    void aSmallSurplusIsLeftAlone() {
        LatencySteering s = new LatencySteering();
        int low = LatencySteering.START_MARGIN_MS + LatencySteering.HYSTERESIS_MS;
        assertEquals(0, feed(s, 0, 3000, low, 186));
        assertEquals(low + 1 - LatencySteering.START_MARGIN_MS, feed(new LatencySteering(), 0, 1000, low + 1, 186));
    }

    @Test
    void underrunsRaiseTheMarginAndQuietTimeLowersItBackToTheStart() {
        LatencySteering s = new LatencySteering();
        s.underrun(0);
        s.underrun(10 * MS);
        assertEquals(LatencySteering.START_MARGIN_MS + 2 * LatencySteering.UNDERRUN_STEP_MS, s.marginMs());
        s.observe(500, (LatencySteering.RELAX_AFTER_MS + 9) * MS);
        assertEquals(LatencySteering.START_MARGIN_MS + 2 * LatencySteering.UNDERRUN_STEP_MS, s.marginMs());
        s.observe(500, (LatencySteering.RELAX_AFTER_MS + 10) * MS);
        assertEquals(LatencySteering.START_MARGIN_MS + 2 * LatencySteering.UNDERRUN_STEP_MS
                - LatencySteering.RELAX_STEP_MS, s.marginMs());
        for (int i = 2; i < 20; i++) {
            s.observe(500, (i * LatencySteering.RELAX_AFTER_MS + 10) * MS);
        }
        assertEquals(LatencySteering.START_MARGIN_MS, s.marginMs(), "never below the start");
        for (int i = 0; i < 50; i++) {
            s.underrun(i);
        }
        assertEquals(LatencySteering.MAX_MARGIN_MS, s.marginMs());
    }

    @Test
    void anUnderrunDiscardsTheRunningWindow() {
        LatencySteering s = new LatencySteering();
        s.observe(10, 0);
        s.underrun(100 * MS);
        assertEquals(0, s.observe(400, 200 * MS));
        assertEquals(400 - LatencySteering.START_MARGIN_MS - LatencySteering.UNDERRUN_STEP_MS,
                s.observe(400, 1200 * MS), "the low point from before the underrun no longer counts");
    }
}
