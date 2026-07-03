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
package com.katmoda.jterm.broadcast;

import com.jediterm.terminal.TtyConnector;

/**
 * A pane that can participate in input broadcast. Registered with a {@link PaneBroadcastBus} while
 * it occupies a grid cell so the bus can fan keystrokes out to it. Defined in this package (rather
 * than the bus reaching into {@code ui.pane}) so broadcast fan-out stays independent of the UI.
 */
public interface BroadcastTarget {

    /** The real (unwrapped) connector, used as the broadcast source/target identity. */
    TtyConnector realConnector();

    /** Whether this pane currently participates in broadcast (its per-pane checkbox is ticked). */
    boolean isBroadcastChecked();
}
