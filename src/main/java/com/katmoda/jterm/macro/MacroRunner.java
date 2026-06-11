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
