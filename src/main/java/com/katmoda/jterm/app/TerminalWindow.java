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

import com.katmoda.jterm.ui.tabs.TabPane;

import javax.swing.JFrame;

/**
 * A top-level window that hosts a tab strip of terminal grids: the single {@link MainWindow} or a
 * {@link DetachedWindow}. The {@link WindowManager} tracks these so tabs can move between windows and
 * the global shortcut dispatcher can target whichever window is focused.
 */
public interface TerminalWindow {

    /** The Swing frame backing this window. */
    JFrame frame();

    /** This window's tab strip. */
    TabPane tabPane();

    /** True only for the primary window (the re-attach target; never auto-closes when emptied). */
    boolean isMain();
}
