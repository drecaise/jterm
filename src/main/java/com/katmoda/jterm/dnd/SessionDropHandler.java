package com.katmoda.jterm.dnd;

import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.ui.pane.TerminalPane;

/**
 * Invoked when a session is dropped on a pane. Implemented by the main window, which
 * connects the SSH session off the EDT and then asks the grid to split and open it.
 */
@FunctionalInterface
public interface SessionDropHandler {
    void onDrop(TerminalPane target, DropRegion region, SshSessionConfig config);
}
