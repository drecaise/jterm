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
package com.katmoda.jterm.app;

import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.tabs.TabPane;

import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all terminal windows (the one {@link MainWindow} plus any {@link DetachedWindow}s).
 *
 * <p>It lets a tab move between windows (drag a tab onto another window, or detach a tab into a new
 * window) and lets the single global shortcut dispatcher target whichever window currently has
 * focus. Everything lives in one JVM, so tabs carry their <em>live</em> grids/sessions when they
 * move — nothing is reconnected or serialized.</p>
 */
public final class WindowManager {

    private static final WindowManager INSTANCE = new WindowManager();

    /** Cascade offset for each new detached window opened via shortcut/menu (not drag). */
    private static final int CASCADE = 40;

    private MainWindow main;
    private final List<TerminalWindow> windows = new ArrayList<>();
    private int cascade = 0;

    private WindowManager() {
    }

    public static WindowManager get() {
        return INSTANCE;
    }

    /** Registers the primary window (also added to the general registry). */
    void registerMain(MainWindow window) {
        this.main = window;
        register(window);
    }

    void register(TerminalWindow window) {
        if (!windows.contains(window)) {
            windows.add(window);
        }
    }

    void unregister(TerminalWindow window) {
        windows.remove(window);
    }

    /**
     * Closes a detached window that has no tabs left (its grids were moved elsewhere, so there are
     * no sessions to dispose). No-op for the main window, which never auto-closes.
     */
    public void closeDetached(TerminalWindow window) {
        if (window == null || window.isMain()) {
            return;
        }
        unregister(window);
        window.frame().dispose();
    }

    public MainWindow mainWindow() {
        return main;
    }

    /** A snapshot of all open terminal windows (main + detached). */
    public List<TerminalWindow> windows() {
        return new ArrayList<>(windows);
    }

    /** The tab strip of the focused terminal window, falling back to the main window. */
    public TabPane focusedTabPane() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        for (TerminalWindow w : windows) {
            if (w.frame() == active) {
                return w.tabPane();
            }
        }
        return main != null ? main.tabPane() : null;
    }

    /** The focused terminal window, or the main window as a fallback. */
    public TerminalWindow focusedWindow() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        for (TerminalWindow w : windows) {
            if (w.frame() == active) {
                return w;
            }
        }
        return main;
    }

    /** The tab strip whose grid currently holds {@code content}, searched across all windows. */
    public TabPane hostContaining(GridContent content) {
        for (TerminalWindow w : windows) {
            if (w.tabPane().gridContaining(content) != null) {
                return w.tabPane();
            }
        }
        return null;
    }

    /** The tab strip that currently shows {@code grid} as a tab, searched across all windows. */
    public TabPane hostContaining(PaneGrid grid) {
        for (TerminalWindow w : windows) {
            if (w.tabPane().containsGrid(grid)) {
                return w.tabPane();
            }
        }
        return null;
    }

    /** True if {@code screenPoint} falls inside any terminal window (so a tab drop isn't "outside"). */
    public boolean isInsideAnyWindow(Point screenPoint) {
        for (TerminalWindow w : windows) {
            if (w.frame().isShowing()) {
                Rectangle b = w.frame().getBounds();
                if (b.contains(screenPoint)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Moves {@code grid} (with its live sessions) out of its current window into a brand-new detached
     * window placed at {@code screenLocation} (or cascaded when null). No-op if the grid is the sole
     * tab of an already-detached window (there'd be nothing to gain). The live sessions and scrollback
     * are preserved — only the hosting container changes.
     */
    public void detachToNewWindow(PaneGrid grid, Point screenLocation) {
        TabPane source = hostContaining(grid);
        if (source == null) {
            return;
        }
        if (!source.owner().isMain() && source.realTabCount() <= 1) {
            return;
        }
        source.detachGridForMove(grid);
        DetachedWindow window = new DetachedWindow();
        Point location = screenLocation;
        if (location == null) {
            cascade = (cascade + CASCADE) % 240;
            Point base = main != null ? main.frame().getLocation() : new Point(80, 80);
            location = new Point(base.x + 60 + cascade, base.y + 60 + cascade);
        }
        window.showAt(location);
        window.tabPane().adoptGrid(grid);
    }
}
