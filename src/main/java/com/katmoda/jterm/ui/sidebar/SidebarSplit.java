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
package com.katmoda.jterm.ui.sidebar;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.function.Consumer;

/**
 * The main window's horizontal split, with a closeable left (sidebar) side.
 *
 * <p>Closing <em>hides</em> the sidebar component rather than merely zeroing the divider: an
 * invisible component drops out of the focus-traversal cycle, so the sidebar's Quick Connect field
 * can't quietly swallow keystrokes while the sidebar isn't on screen.</p>
 *
 * <p>The sidebar can also be closed by dragging the divider to the left edge. That is detected on
 * the divider's own mouse-release — not through a {@link JSplitPane#DIVIDER_LOCATION_PROPERTY}
 * listener — so window resizes and programmatic {@code setDividerLocation} calls can never trigger
 * a spurious close. One-touch expand buttons are deliberately not enabled: they keep their own
 * collapsed state inside {@link BasicSplitPaneUI}, which would compete with this class as a second
 * source of truth.</p>
 */
public final class SidebarSplit extends JSplitPane {

    /** A drag released left of this many pixels closes the sidebar. */
    private static final int COLLAPSE_THRESHOLD = 60;

    /** Narrowest width worth remembering, so reopening never yields a useless sliver. */
    private static final int MIN_EXPANDED_WIDTH = 120;

    private final Component sidebar;
    /** The look-and-feel's divider size, restored on expand (it is zeroed while closed). */
    private final int defaultDividerSize;

    /** Width to reopen at; refreshed from the live divider location whenever the sidebar closes. */
    private int expandedWidth;
    private boolean sidebarVisible;
    private Consumer<Boolean> onVisibilityChanged;

    /**
     * @param sidebar       the closeable left component
     * @param main          the right component, which takes the whole width while the sidebar is closed
     * @param expandedWidth the sidebar width to open at, in pixels
     * @param visible       whether the sidebar starts open
     */
    public SidebarSplit(Component sidebar, Component main, int expandedWidth, boolean visible) {
        super(HORIZONTAL_SPLIT, sidebar, main);
        this.sidebar = sidebar;
        this.expandedWidth = Math.max(UIScale.scale(MIN_EXPANDED_WIDTH), expandedWidth);
        this.defaultDividerSize = getDividerSize();
        // The sidebar keeps its width when the window resizes (a left-anchored split), and its
        // minimum size must not clamp the divider before it reaches the edge.
        setResizeWeight(0.0);
        sidebar.setMinimumSize(new Dimension(0, 0));
        this.sidebarVisible = true;
        applyState();
        if (!visible) {
            setSidebarVisible(false);
        }
    }

    /**
     * Re-registers the divider drag listener. Installing a look-and-feel builds a fresh divider, so
     * without this a light/dark toggle would silently drop drag-to-close. Also covers the initial
     * install, which {@link JSplitPane}'s own constructor triggers.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        installDividerDragListener();
    }

    /** Registers the single callback notified whenever the sidebar opens or closes. */
    public void onVisibilityChanged(Consumer<Boolean> listener) {
        this.onVisibilityChanged = listener;
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    /** Opens or closes the sidebar; a no-op if it is already in that state. */
    public void setSidebarVisible(boolean visible) {
        if (visible == sidebarVisible) {
            return;
        }
        if (!visible) {
            // Capture the width to reopen at before the divider is driven to zero.
            expandedWidth = expandedWidth();
        }
        sidebarVisible = visible;
        applyState();
        if (onVisibilityChanged != null) {
            onVisibilityChanged.accept(visible);
        }
    }

    /** Flips the sidebar open/closed and returns its new state. */
    public boolean toggleSidebar() {
        setSidebarVisible(!sidebarVisible);
        return sidebarVisible;
    }

    /**
     * The width to reopen the sidebar at — the live divider location while it is open, or the
     * remembered width while it is closed. A live location below {@link #MIN_EXPANDED_WIDTH} is
     * ignored, which also keeps the value sane before the split has ever been laid out.
     */
    public int expandedWidth() {
        if (sidebarVisible) {
            int location = getDividerLocation();
            if (location >= UIScale.scale(MIN_EXPANDED_WIDTH)) {
                return location;
            }
        }
        return expandedWidth;
    }

    private void applyState() {
        sidebar.setVisible(sidebarVisible);
        setDividerSize(sidebarVisible ? defaultDividerSize : 0);
        setDividerLocation(sidebarVisible ? expandedWidth : 0);
        revalidate();
        repaint();
    }

    private void installDividerDragListener() {
        if (!(getUI() instanceof BasicSplitPaneUI ui)) {
            return;
        }
        BasicSplitPaneDivider divider = ui.getDivider();
        for (MouseListener existing : divider.getMouseListeners()) {
            if (existing instanceof EdgeDragCloser) {
                return;
            }
        }
        divider.addMouseListener(new EdgeDragCloser());
    }

    /** Closes the sidebar when the user drags the divider to (or near) the left edge. */
    final class EdgeDragCloser extends MouseAdapter {
        @Override
        public void mouseReleased(MouseEvent e) {
            if (sidebarVisible && getDividerLocation() < UIScale.scale(COLLAPSE_THRESHOLD)) {
                setSidebarVisible(false);
            }
        }
    }
}
