/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.sidebar;

import com.formdev.flatlaf.util.UIScale;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open/close bookkeeping for the sidebar split. Swing components construct fine headlessly (only
 * windows need a display), so the state machine and the remembered width are testable here; the
 * drag gesture and the actual painting are not.
 */
class SidebarSplitTest {

    private static final int WIDTH = 240;

    private final JPanel sidebar = new JPanel();
    private final JPanel main = new JPanel();

    private SidebarSplit split(boolean visible) {
        return new SidebarSplit(sidebar, main, WIDTH, visible);
    }

    @Test
    void opensAtTheGivenWidth() {
        SidebarSplit split = split(true);

        assertTrue(split.isSidebarVisible());
        assertTrue(sidebar.isVisible());
        assertEquals(WIDTH, split.expandedWidth());
        assertTrue(split.getDividerSize() > 0, "an open sidebar keeps a draggable divider");
    }

    @Test
    void startsClosedWhenAskedButRemembersTheWidth() {
        SidebarSplit split = split(false);

        assertFalse(split.isSidebarVisible());
        assertFalse(sidebar.isVisible(), "closing hides the sidebar, keeping it out of focus traversal");
        assertEquals(0, split.getDividerSize(), "no divider to drag while closed");
        assertEquals(WIDTH, split.expandedWidth(), "the width to reopen at survives being closed");
    }

    @Test
    void closingRemembersTheCurrentWidthAndReopeningRestoresIt() {
        SidebarSplit split = split(true);
        split.setDividerLocation(310);

        assertFalse(split.toggleSidebar());
        assertEquals(310, split.expandedWidth());

        assertTrue(split.toggleSidebar());
        assertTrue(sidebar.isVisible());
        assertEquals(310, split.getDividerLocation());
    }

    @Test
    void aSliverWidthIsNotRemembered() {
        SidebarSplit split = split(true);
        // What a drag that stopped just short of the collapse threshold would leave behind.
        split.setDividerLocation(UIScale.scale(20));

        assertEquals(WIDTH, split.expandedWidth(), "too narrow to reopen at — keep the last good width");

        split.setSidebarVisible(false);
        split.setSidebarVisible(true);
        assertEquals(WIDTH, split.getDividerLocation());
    }

    @Test
    void aSeededWidthBelowTheMinimumIsWidenedOnOpen() {
        SidebarSplit split = new SidebarSplit(sidebar, main, 5, true);

        assertTrue(split.expandedWidth() >= UIScale.scale(120));
    }

    /** A light/dark toggle reinstalls the look-and-feel, which builds a fresh divider. */
    @Test
    void dragToCloseSurvivesALookAndFeelChange() {
        SidebarSplit split = split(true);
        assertEquals(1, edgeDragClosers(split), "installed once up front");

        split.updateUI();

        assertEquals(1, edgeDragClosers(split), "re-registered on the new divider, exactly once");
    }

    private static long edgeDragClosers(SidebarSplit split) {
        BasicSplitPaneUI ui = (BasicSplitPaneUI) split.getUI();
        return Stream.of(ui.getDivider().getMouseListeners())
                .filter(l -> l instanceof SidebarSplit.EdgeDragCloser)
                .count();
    }

    @Test
    void theVisibilityCallbackFiresOncePerRealChange() {
        SidebarSplit split = split(true);
        List<Boolean> seen = new ArrayList<>();
        split.onVisibilityChanged(seen::add);

        split.setSidebarVisible(false);
        split.setSidebarVisible(false);
        split.toggleSidebar();

        assertEquals(List.of(false, true), seen);
    }
}
