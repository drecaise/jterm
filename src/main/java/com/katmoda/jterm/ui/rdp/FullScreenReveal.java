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

import java.awt.Point;
import java.awt.Rectangle;

/**
 * When a full-screen RDP desktop's bar is shown. Pure (time and pointer are passed in), so the
 * timing rules are unit-tested; {@link FullScreenBar} feeds it from a timer.
 *
 * <p>While hidden the bar takes no pixels and no events from the remote desktop: it appears only
 * after the pointer has rested {@link #DWELL_MS} on the top edge within the middle third, so a quick
 * flick to a remote title bar doesn't open it. Shown, it hides {@link #HIDE_MS} after the pointer
 * leaves it, unless pinned. {@link #flash} shows it for a while on entry so the user learns where
 * it is (and sees the shared-folders indicator).</p>
 */
final class FullScreenReveal {

    static final long DWELL_MS = 500;
    static final long HIDE_MS = 1000;
    /** How close to the top counts as "at the edge": a pointer can stop at row 0 or row 1. */
    static final int EDGE_PX = 2;

    private boolean pinned;
    private boolean shown;
    private long dwellSince = -1;
    private long leftAt = -1;
    private long flashUntil = -1;

    void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    boolean pinned() {
        return pinned;
    }

    /** Show until {@code until} (ms, same clock as {@link #update}) even without the pointer. */
    void flash(long until) {
        flashUntil = until;
        shown = true;
        leftAt = -1;
    }

    /**
     * Advances the state.
     *
     * @param now     milliseconds, any monotonic clock
     * @param pointer the pointer in the window's coordinates, or {@code null} when it is elsewhere
     * @param width   the window's width
     * @param bar     the bar's bounds in the same coordinates
     * @return whether the bar should be shown
     */
    boolean update(long now, Point pointer, int width, Rectangle bar) {
        if (pinned) {
            shown = true;
            dwellSince = -1;
            leftAt = -1;
            return true;
        }
        if (shown) {
            boolean over = pointer != null && bar.contains(pointer);
            if (over || now < flashUntil) {
                leftAt = -1;
                return true;
            }
            if (leftAt < 0) {
                leftAt = now;
            }
            if (now - leftAt >= HIDE_MS) {
                shown = false;
                leftAt = -1;
                dwellSince = -1;
            }
            return shown;
        }
        boolean atEdge = pointer != null && pointer.y <= EDGE_PX
                && pointer.x >= width / 3 && pointer.x < width - width / 3;
        if (!atEdge) {
            dwellSince = -1;
            return false;
        }
        if (dwellSince < 0) {
            dwellSince = now;
        }
        if (now - dwellSince >= DWELL_MS) {
            shown = true;
            dwellSince = -1;
        }
        return shown;
    }
}
