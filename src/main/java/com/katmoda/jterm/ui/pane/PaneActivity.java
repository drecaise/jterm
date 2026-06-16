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
 * A terminal pane's background-activity state, surfaced on its tab when the tab is not in
 * front: {@link #NONE} (seen / nothing new), {@link #NEW_OUTPUT} (unread output), and
 * {@link #DISCONNECTED} (its session ended). Selecting the tab resets every pane to
 * {@link #NONE}; see {@code PaneGrid.setForeground}.
 */
public enum PaneActivity {
    NONE, NEW_OUTPUT, DISCONNECTED
}
