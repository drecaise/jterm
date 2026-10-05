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
package com.katmoda.jterm.ui.grid;

import com.katmoda.jterm.terminal.TerminalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Counts the asynchronous opens (SSH connects, mostly) still in flight for one {@link PaneGrid},
 * which is what lets the grid answer two questions it otherwise cannot:
 *
 * <ul>
 *   <li><b>Did the last open just fail on a grid that holds nothing?</b> Then the tab was only ever
 *       there for that session, and {@code onAbandoned} closes it. Both conditions matter — while a
 *       connect (or its modal error dialog, which still pumps events) is up, the user can fill the
 *       empty cell or drop a second session on it, and closing then would take a live session or an
 *       in-flight connect with it.</li>
 *   <li><b>Has the grid gone away while a connect was still running?</b> Then the session that
 *       arrives is closed rather than placed. Placing it into a removed grid would leave an
 *       authenticated connection alive with no pane, and so no way to close it.</li>
 * </ul>
 *
 * <p>Swing-free so it can be unit-tested, but EDT-confined in use: every callback it hands out is
 * run on the EDT, so the counter needs no synchronisation.</p>
 */
final class PendingOpens {

    private static final Logger LOG = LoggerFactory.getLogger(PendingOpens.class);

    private final BooleanSupplier hasContent;
    private final Runnable onAbandoned;
    private int pending;
    private boolean disposed;

    PendingOpens(BooleanSupplier hasContent, Runnable onAbandoned) {
        this.hasContent = hasContent;
        this.onAbandoned = onAbandoned;
    }

    /** Registers one open as in flight. Exactly one of the ticket's callbacks should then run. */
    Ticket begin() {
        pending++;
        return new Ticket();
    }

    /** The grid is closing: nothing may be placed in it any more, and it must not close itself again. */
    void dispose() {
        disposed = true;
    }

    /** One in-flight open. Settles once, whichever callback runs first and however often. */
    final class Ticket {

        private boolean settled;

        /** The success callback: places the session, or closes it if the grid is already gone. */
        Consumer<TerminalSession> onReady(Consumer<TerminalSession> place) {
            return session -> {
                settle();
                if (session == null) {
                    return;
                }
                if (disposed) {
                    LOG.debug("closing a session that connected after its tab was closed");
                    session.close();
                    return;
                }
                place.accept(session);
            };
        }

        /** The failure callback: closes the grid's tab if this was the last thing it was waiting for. */
        Runnable onError() {
            return () -> {
                if (settle() && !disposed && pending == 0 && !hasContent.getAsBoolean()) {
                    onAbandoned.run();
                }
            };
        }

        private boolean settle() {
            if (settled) {
                return false;
            }
            settled = true;
            pending--;
            return true;
        }
    }
}
