package com.katmoda.jterm.terminal;

import com.jediterm.terminal.TtyConnector;

/**
 * A live terminal back-end that a {@code TerminalPane} drives through a JediTerm
 * {@link TtyConnector}. Phase 1 has two implementations: a local pty-backed shell
 * ({@code LocalSession}) and an SSH channel ({@code SshSession}).
 */
public interface TerminalSession {

    /** The connector JediTerm reads/writes through. */
    TtyConnector connector();

    /** Human-readable label (session name or working directory). */
    String title();

    /** Per-session terminal/font settings; defaults to {@link TerminalProfile#DEFAULT}. */
    default TerminalProfile profile() {
        return TerminalProfile.DEFAULT;
    }

    /** Whether the underlying process/channel is still running. */
    boolean isAlive();

    /** Terminate the back-end and release resources. */
    void close();
}
