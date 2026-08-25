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

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays a {@link Macro} into a terminal connector. Runs on a daemon background thread
 * because steps sleep (SLEEP, per-keystroke delays) and must stay off the EDT. Writes go
 * through whichever connector is handed in — callers pass a pane's broadcasting connector to
 * respect broadcast mode, or a session's raw connector for run-on-connect.
 */
public final class MacroRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MacroRunner.class);

    private MacroRunner() {
    }

    /**
     * Starts replaying {@code macro} into {@code connector} on a background thread.
     *
     * <p>The macro must already be plaintext. A sealed macro has no readable steps
     * ({@link Macro#isSealed()}), so this refuses it rather than replaying nothing: callers resolve
     * it through {@code MacroCrypto} — which needs the vault unlocked, hence a UI decision — and
     * then use {@link #run(String, java.util.List, TtyConnector)}.</p>
     */
    public static void run(Macro macro, TtyConnector connector) {
        if (macro == null) {
            return;
        }
        if (macro.isSealed()) {
            LOG.warn("refusing to replay \"{}\": its steps are still encrypted", macro.getName());
            return;
        }
        run(macro.getName(), macro.getSteps(), connector);
    }

    /**
     * Starts replaying already-resolved {@code steps} on a background thread. Used by the paths that
     * decrypt a macro on the EDT first (the Macros menu, the hotkey dispatcher, run-on-connect) so
     * the replay never has to reach back into the vault.
     */
    public static void run(String name, List<MacroStep> steps, TtyConnector connector) {
        if (steps == null || steps.isEmpty() || connector == null) {
            return;
        }
        List<MacroStep> snapshot = List.copyOf(steps);
        MacroSink sink = connector::write;
        Thread thread = new Thread(() -> replay(snapshot, sink), "macro-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void replay(List<MacroStep> steps, MacroSink sink) {
        try {
            for (MacroStep step : steps) {
                step.execute(sink);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A broken connector (closed session) ends the run; nothing actionable here.
            LOG.debug("macro replay ended on a broken connector", e);
        }
    }
}
