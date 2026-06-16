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
package com.katmoda.jterm.terminal;

import java.util.function.Consumer;

/**
 * Recreates a {@link TerminalSession} of the same kind as the one a pane originally held, so a
 * stopped pane can be restarted in place. Local sessions are produced synchronously; SSH
 * sessions connect asynchronously and deliver the result later.
 *
 * <p>The factory must deliver the new session to {@code onReady} on the EDT (or not at all, if
 * creation fails — failures are reported by the factory itself, e.g. the SSH error dialog).</p>
 */
@FunctionalInterface
public interface SessionFactory {

    /** Build a fresh session and hand it to {@code onReady} when ready. */
    void create(Consumer<TerminalSession> onReady);
}
