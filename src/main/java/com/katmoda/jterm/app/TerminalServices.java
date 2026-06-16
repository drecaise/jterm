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
