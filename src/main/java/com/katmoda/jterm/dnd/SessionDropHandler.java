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
