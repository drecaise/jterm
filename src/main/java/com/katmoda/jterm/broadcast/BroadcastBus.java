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
