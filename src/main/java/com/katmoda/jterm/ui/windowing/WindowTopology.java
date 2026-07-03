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
package com.katmoda.jterm.ui.windowing;

import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.tabs.TabPane;

import java.awt.Point;

/**
 * The view onto the set of open terminal windows that UI code (a {@link TabPane} and its drag-and-drop
 * machinery) needs in order to move panes and tabs between windows: find which window hosts a given
 * pane or tab, detach a tab into a brand-new window, reach the main window's tab strip to re-attach,
 * close an emptied detached window, and decide whether a drop landed inside any window.
 *
 * <p>Implemented by the app-level window registry; injected into each {@link TabPane} so the UI layer
 * never depends on the concrete app package.</p>
 */
public interface WindowTopology {

    /** The tab strip whose grid currently holds {@code content}, searched across all windows. */
    TabPane hostContaining(GridContent content);

    /** The tab strip that currently shows {@code grid} as a tab, searched across all windows. */
    TabPane hostContaining(PaneGrid grid);

    /**
     * Moves {@code grid} (with its live sessions) out of its current window into a brand-new detached
     * window placed at {@code screenLocation} (or cascaded when null). No-op if the grid is the sole
     * tab of an already-detached window (there'd be nothing to gain). The live sessions and scrollback
     * are preserved — only the hosting container changes.
     */
    void detachToNewWindow(PaneGrid grid, Point screenLocation);

    /** The main window's tab strip (the re-attach target for tabs in detached windows). */
    TabPane mainTabPane();

    /**
     * Closes a detached window that has no tabs left (its grids were moved elsewhere, so there are
     * no sessions to dispose). No-op for the main window, which never auto-closes.
     */
    void closeDetached(TerminalWindow window);

    /** True if {@code screenPoint} falls inside any terminal window (so a tab drop isn't "outside"). */
    boolean isInsideAnyWindow(Point screenPoint);
}
