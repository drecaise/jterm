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
package com.katmoda.jterm.macro;

import com.jediterm.terminal.TtyConnector;

/**
 * Replays a {@link Macro} into a terminal connector. Runs on a daemon background thread
 * because steps sleep (SLEEP, per-keystroke delays) and must stay off the EDT. Writes go
 * through whichever connector is handed in — callers pass a pane's broadcasting connector to
 * respect broadcast mode, or a session's raw connector for run-on-connect.
 */
public final class MacroRunner {

    private MacroRunner() {
    }

    /** Starts replaying {@code macro} into {@code connector} on a background thread. */
    public static void run(Macro macro, TtyConnector connector) {
        if (macro == null || connector == null || macro.getSteps().isEmpty()) {
            return;
        }
        MacroSink sink = connector::write;
        Thread thread = new Thread(() -> replay(macro, sink), "macro-" + macro.getName());
        thread.setDaemon(true);
        thread.start();
    }

    private static void replay(Macro macro, MacroSink sink) {
        try {
            for (MacroStep step : macro.getSteps()) {
                step.execute(sink);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // A broken connector (closed session) ends the run; nothing actionable here.
        }
    }
}
