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
 * Receives input written by one pane and fans it out to the other panes that are
 * currently participating in broadcast. Implemented by {@code PaneGrid}.
 */
public interface BroadcastBus {

    /**
     * @param source the real connector the input was typed into (excluded from fan-out)
     * @param data   the terminal-encoded bytes JediTerm produced for the keystroke
     */
    void broadcast(TtyConnector source, byte[] data);
}
