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
