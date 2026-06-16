package com.katmoda.jterm.app;

import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.ssh.SshSession;

import javax.swing.Icon;
import java.util.function.Consumer;

/**
 * The app-level services a {@link com.katmoda.jterm.ui.tabs.TabPane} needs but doesn't own: SSH
 * connection (credential/vault resolution lives in {@link MainWindow}), session-icon/tab-color
 * lookup, and the shared {@link Keymap}. Implemented by {@link MainWindow}; both the main window's
 * and every detached window's {@code TabPane} share the one instance, so reconnects and drops route
 * through the same vault and the same keymap.
 */
public interface TerminalServices {

    /** Connect an SSH session off the EDT, then hand the live session to {@code onConnected} on it. */
    void connectAsync(SshSessionConfig cfg, Consumer<SshSession> onConnected);

    /** A 16px icon for a session icon id (falls back to the built-in server icon). */
    Icon iconFor(String iconId);

    /** The effective tab color (session → folder → global) as a {@code #RRGGBB} string, or null. */
    String effectiveTabColorHex(SshSessionConfig cfg);

    /** The shared, user-configurable keymap (for shortcut accelerators / tooltips). */
    Keymap keymap();
}
