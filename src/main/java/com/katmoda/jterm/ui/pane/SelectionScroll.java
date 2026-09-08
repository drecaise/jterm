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
package com.katmoda.jterm.ui.pane;

/**
 * Where a selection's rows land after the terminal scrolls — the arithmetic that lets a highlight
 * stay on its own text instead of being dropped.
 *
 * <p>Terminal rows are numbered relative to the top of the screen: {@code 0} is the first screen
 * row, {@code -1} the newest scrollback line. A scroll renumbers rows, which is why JediTerm's
 * {@code TerminalPanel.scrollArea} simply clears the selection rather than tracking it. When a
 * scroll region moves by {@code dy}, the content inside it moves to {@code y + dy}, and — when the
 * region starts at the top of the screen and moves up — the lines pushed off the top are appended
 * to history, so every scrollback row is renumbered by the same {@code dy}. Shifting the selection
 * by that amount therefore keeps it on the very characters it was on. JediTerm does the same thing
 * on resize ({@code TerminalTextBufferResize} → {@code selection.shiftY(-screenLinesToMove)}).</p>
 *
 * <p>The rule is deliberately conservative: it shifts only when the mapping is exact, and clears
 * otherwise. A selection whose two ends would move by <em>different</em> amounts (one inside a
 * partial {@code CSI r} scroll region and one below it) is distorted rather than moved, and a
 * selection pushed out of the buffer is gone. Guessing in either case would leave a highlight
 * sitting on text the user never chose — which a later copy would then pick up.</p>
 *
 * <p>Pure and Swing-free so it can be unit-tested headlessly.</p>
 */
final class SelectionScroll {

    private SelectionScroll() {
    }

    /** The selection's rows after a scroll. */
    record Rows(int startY, int endY) {
    }

    /**
     * The rows a selection occupies once a scroll has been applied, or {@code null} if it cannot be
     * tracked and should be cleared.
     *
     * @param startY       the selection's start row before the scroll
     * @param endY         the selection's end row before the scroll (may be above {@code startY})
     * @param regionTop    first screen row of the scroll region, 0-based
     * @param regionBottom last screen row of the scroll region, 0-based and inclusive
     * @param dy           rows the region's content moves by; negative moves it up
     * @param toHistory    whether the rows leaving the top of the region are appended to the
     *                     scrollback, which renumbers every row already in it
     * @param screenHeight number of screen rows
     * @param historyLines scrollback rows addressable above the screen <em>after</em> the scroll
     */
    static Rows shiftForScroll(int startY, int endY, int regionTop, int regionBottom,
                               int dy, boolean toHistory, int screenHeight, int historyLines) {
        if (dy == 0) {
            return new Rows(startY, endY);
        }
        int startShift = shiftOf(startY, regionTop, regionBottom, dy, toHistory);
        int endShift = shiftOf(endY, regionTop, regionBottom, dy, toHistory);
        if (startShift != endShift) {
            // The ends move by different amounts, so the run between them is no longer the text
            // that was selected. Nothing sensible to keep.
            return null;
        }
        int newStart = startY + startShift;
        int newEnd = endY + endShift;
        if (Math.min(newStart, newEnd) < -historyLines || Math.max(newStart, newEnd) >= screenHeight) {
            // Scrolled out of the buffer: off the top of the scrollback, or off the bottom of the
            // screen. Clearing beats keeping the reachable part — a truncated highlight would copy
            // less than it appears to.
            return null;
        }
        return new Rows(newStart, newEnd);
    }

    /**
     * How far the content at row {@code y} moves. Rows inside the scroll region move by {@code dy};
     * rows outside it stay put. Scrollback rows are the subtle case: they are renumbered only when
     * the scrolled-off rows are appended to history, which the caller has to state rather than
     * infer. {@code TerminalTextBuffer.scrollArea} appends whenever the region starts at row 1, but
     * a {@code CSI M} delete-line reaches {@code TerminalTextBuffer.deleteLines} directly and
     * discards its rows even at row 1 — inferring from the region alone would renumber a scrollback
     * that never moved.
     */
    private static int shiftOf(int y, int regionTop, int regionBottom, int dy, boolean toHistory) {
        if (y < 0) {
            return toHistory ? dy : 0;
        }
        return y >= regionTop && y <= regionBottom ? dy : 0;
    }
}
