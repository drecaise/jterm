/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.pane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Rows are numbered from the top of the screen: 0 is the first screen row, -1 the newest
 * scrollback line. These use a 24-row screen throughout.
 */
class SelectionScrollTest {

    private static final int HEIGHT = 24;
    private static final int LAST_ROW = HEIGHT - 1;
    /** A scroll region covering the whole screen — what a shell prompt scrolling produces. */
    private static final int FULL_BOTTOM = LAST_ROW;

    private static SelectionScroll.Rows scroll(int startY, int endY, int dy, int historyLines) {
        // A full-screen scroll upwards is the case where rows are appended to the scrollback.
        return SelectionScroll.shiftForScroll(startY, endY, 0, FULL_BOTTOM, dy, dy < 0, HEIGHT, historyLines);
    }

    private static void assertRows(int expectedStart, int expectedEnd, SelectionScroll.Rows actual) {
        assertEquals(new SelectionScroll.Rows(expectedStart, expectedEnd), actual);
    }

    @Test
    void fullScreenScrollUpMovesTheSelectionWithItsText() {
        assertRows(4, 6, scroll(5, 7, -1, 10));
    }

    @Test
    void aSelectionAlreadyInScrollbackIsRenumberedToo() {
        // The lines pushed off the screen are appended to history, so every scrollback row moves.
        assertRows(-5, -3, scroll(-3, -1, -2, 12));
    }

    @Test
    void aSelectionSpanningScrollbackAndScreenMovesAsOne() {
        assertRows(-3, 2, scroll(-2, 3, -1, 12));
    }

    @Test
    void endpointsInEitherOrderAreBothMoved() {
        // Dragging upwards leaves end above start; the run is the same either way.
        assertRows(6, 4, scroll(7, 5, -1, 10));
    }

    @Test
    void scrollingPastTheTopOfTheScrollbackClearsIt() {
        // Only 10 lines of history to land in, and the selection would need row -11.
        assertNull(scroll(-9, -8, -2, 10));
    }

    @Test
    void scrollingDownWithRoomLeftMovesTheSelection() {
        assertRows(7, 8, scroll(5, 6, 2, 0));
    }

    @Test
    void scrollingDownPastTheLastRowClearsIt() {
        // Content pushed off the bottom of the screen is discarded, not kept in history.
        assertNull(scroll(22, LAST_ROW, 1, 0));
    }

    @Test
    void aZeroRowScrollChangesNothing() {
        assertRows(5, 7, scroll(5, 7, 0, 10));
    }

    @Test
    void aSelectionInsideAPartialRegionMovesWithIt() {
        // CSI r region covering rows 4..9 only.
        assertRows(5, 7, SelectionScroll.shiftForScroll(6, 8, 4, 9, -1, false, HEIGHT, 10));
    }

    @Test
    void aSelectionStraddlingAPartialRegionIsCleared() {
        // Row 8 moves, row 12 doesn't — the run between them is no longer what was selected.
        assertNull(SelectionScroll.shiftForScroll(8, 12, 4, 9, -1, false, HEIGHT, 10));
    }

    @Test
    void aSelectionBelowAPartialRegionIsLeftWhereItIs() {
        assertRows(12, 14, SelectionScroll.shiftForScroll(12, 14, 4, 9, -1, false, HEIGHT, 10));
    }

    @Test
    void scrollbackIsUntouchedWhenTheRegionDoesNotStartAtTheTop() {
        // Nothing is appended to history unless the region starts at screen row 0, so scrollback
        // keeps its numbering.
        assertRows(-3, -2, SelectionScroll.shiftForScroll(-3, -2, 4, 9, -1, false, HEIGHT, 10));
    }

    @Test
    void aTopAnchoredPartialRegionStillClearsAStraddlingSelection() {
        // Rows 0..9 move up, rows 10+ stay: the two ends disagree.
        assertNull(SelectionScroll.shiftForScroll(5, 12, 0, 9, -1, true, HEIGHT, 10));
    }

    @Test
    void aDeleteLineAtTheTopLeavesScrollbackNumberingAlone() {
        // CSI M reaches TerminalTextBuffer.deleteLines directly and discards its rows instead of
        // appending them, so scrollback does not move even though the region starts at row 0.
        assertRows(-3, -2, SelectionScroll.shiftForScroll(-3, -2, 0, 22, -1, false, HEIGHT, 10));
    }

    @Test
    void anInsertLineMovesTheSelectionDownWithTheContent() {
        // What vim's CTRL-Y sends: CSI L at the top of a region that stops above the status line.
        assertRows(4, 5, SelectionScroll.shiftForScroll(3, 4, 0, 22, 1, false, HEIGHT, 10));
    }

    @Test
    void anInsertLinePushingTheSelectionOffTheRegionClearsIt() {
        // Row 22 is the last row of the region; content there is pushed out and discarded.
        assertNull(SelectionScroll.shiftForScroll(21, 22, 0, 22, 2, false, HEIGHT, 10));
    }

    @Test
    void aTopAnchoredPartialRegionRenumbersScrollbackWithIt() {
        // This region does append to history, so a scrollback end moves by the same amount.
        assertRows(-3, 4, SelectionScroll.shiftForScroll(-2, 5, 0, 9, -1, true, HEIGHT, 12));
    }
}
