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
package com.katmoda.jterm.ui.rdp;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullScreenRevealTest {

    private static final int W = 1920;
    private static final Rectangle BAR = new Rectangle(660, 0, 600, 40);
    private static final Point TOP_MIDDLE = new Point(960, 0);

    private final FullScreenReveal r = new FullScreenReveal();

    @Test
    void restingOnTheTopEdgeInTheMiddleThirdShowsItAfterTheDwell() {
        assertFalse(r.update(0, TOP_MIDDLE, W, BAR));
        assertFalse(r.update(FullScreenReveal.DWELL_MS - 1, TOP_MIDDLE, W, BAR));
        assertTrue(r.update(FullScreenReveal.DWELL_MS, TOP_MIDDLE, W, BAR));
    }

    @Test
    void aFlickToTheEdgeDoesNotShowIt() {
        assertFalse(r.update(0, TOP_MIDDLE, W, BAR));
        assertFalse(r.update(300, new Point(960, 200), W, BAR)); // left the edge: dwell restarts
        assertFalse(r.update(400, TOP_MIDDLE, W, BAR));
        assertFalse(r.update(800, TOP_MIDDLE, W, BAR));
        assertTrue(r.update(900, TOP_MIDDLE, W, BAR));
    }

    @Test
    void theTopCornersDoNotShowIt() {
        // Remote title bars, the Start button of a top taskbar, a close button: left and right thirds.
        for (Point p : new Point[]{new Point(10, 0), new Point(W / 3 - 1, 0), new Point(W - W / 3, 0), new Point(W - 1, 0)}) {
            FullScreenReveal fresh = new FullScreenReveal();
            fresh.update(0, p, W, BAR);
            assertFalse(fresh.update(5_000, p, W, BAR), p.toString());
        }
    }

    @Test
    void belowTheEdgeDoesNotShowIt() {
        Point p = new Point(960, FullScreenReveal.EDGE_PX + 1);
        r.update(0, p, W, BAR);
        assertFalse(r.update(5_000, p, W, BAR));
    }

    @Test
    void itHidesASecondAfterThePointerLeavesTheBar() {
        show();
        Point away = new Point(960, 600);
        assertTrue(r.update(1_000, new Point(700, 20), W, BAR)); // over the bar
        assertTrue(r.update(1_100, away, W, BAR));
        assertTrue(r.update(1_100 + FullScreenReveal.HIDE_MS - 1, away, W, BAR));
        assertFalse(r.update(1_100 + FullScreenReveal.HIDE_MS, away, W, BAR));
    }

    @Test
    void comingBackWithinTheSecondKeepsItShown() {
        show();
        Point away = new Point(960, 600);
        r.update(1_000, away, W, BAR);
        assertTrue(r.update(1_900, new Point(700, 20), W, BAR));
        assertTrue(r.update(2_500, away, W, BAR));
        assertTrue(r.update(3_499, away, W, BAR)); // the second counts from leaving again
        assertFalse(r.update(3_500, away, W, BAR));
    }

    @Test
    void thePointerOutsideTheWindowCountsAsAway() {
        show();
        r.update(1_000, null, W, BAR);
        assertFalse(r.update(1_000 + FullScreenReveal.HIDE_MS, null, W, BAR));
    }

    @Test
    void pinnedStaysShownAndUnpinningHidesItAfterTheDelay() {
        r.setPinned(true);
        assertTrue(r.update(0, null, W, BAR));
        assertTrue(r.update(60_000, null, W, BAR));
        r.setPinned(false);
        assertTrue(r.update(60_001, null, W, BAR));
        assertFalse(r.update(60_001 + FullScreenReveal.HIDE_MS, null, W, BAR));
    }

    @Test
    void aFlashShowsItUntilItEndsThenItHidesAfterTheDelay() {
        r.flash(3_000);
        Point away = new Point(960, 600);
        assertTrue(r.update(0, away, W, BAR));
        assertTrue(r.update(2_999, away, W, BAR));
        assertTrue(r.update(3_000, away, W, BAR));
        assertFalse(r.update(3_000 + FullScreenReveal.HIDE_MS, away, W, BAR));
    }

    private void show() {
        r.update(0, TOP_MIDDLE, W, BAR);
        assertTrue(r.update(FullScreenReveal.DWELL_MS, TOP_MIDDLE, W, BAR));
    }
}
