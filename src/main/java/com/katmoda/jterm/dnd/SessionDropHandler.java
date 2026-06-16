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
package com.katmoda.jterm.dnd;

import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;

import java.util.function.BiConsumer;

/**
 * Connects a dropped SSH session off the EDT (handling auth prompts) and hands the live session
 * plus its restart factory back to {@code placer} on the EDT. The grid decides placement — split a
 * pane or fill an empty cell — so connect concerns stay in the main window and layout in the grid.
 */
@FunctionalInterface
public interface SessionDropHandler {
    void connect(SshSessionConfig config, BiConsumer<TerminalSession, SessionFactory> placer);
}
