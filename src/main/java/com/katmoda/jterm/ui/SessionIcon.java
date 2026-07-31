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
package com.katmoda.jterm.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.Icon;

/**
 * Resolves the display icon for a session, shared by the tab strip and the per-pane title bar so
 * the two never drift: an SSH session uses its saved icon (or the generic server glyph), a WSL
 * distro uses its custom local icon, and a plain shell uses a theme-contrasting terminal glyph.
 *
 * <p>The no-size overloads use {@link #DEFAULT_SIZE}, the one definition of "an inline icon" for
 * tabs, pane title bars and the SFTP connection bar.</p>
 */
public final class SessionIcon {

    /**
     * The standard inline icon size, in unscaled pixels — the application UI scale is applied
     * downstream (by {@code IconLibrary} and {@code FlatSVGIcon}), so this stays a design constant.
     */
    public static final int DEFAULT_SIZE = 16;

    private SessionIcon() {
    }

    /** The session's icon at {@link #DEFAULT_SIZE}. */
    public static Icon forSession(TerminalSession session) {
        return forSession(session, DEFAULT_SIZE);
    }

    /** The icon for a saved SSH icon id at {@link #DEFAULT_SIZE}. */
    public static Icon forIconId(String iconId) {
        return forIconId(iconId, DEFAULT_SIZE);
    }

    public static Icon forSession(TerminalSession session, int size) {
        String iconId = session.iconId();
        if (iconId != null && !iconId.isBlank()) {
            return IconLibrary.get().icon(iconId, size);
        }
        // No custom icon: SSH falls back to the generic server glyph; a plain local shell gets a
        // theme-contrasting terminal glyph (a light glyph reads on the dark strip, and vice-versa).
        if (session instanceof SshSession) {
            return forIconId(null, size);
        }
        String name = ThemeManager.get().isDark() ? "icons/terminal-light.svg" : "icons/terminal-dark.svg";
        return new FlatSVGIcon(name, size, size);
    }

    /**
     * The icon for a saved SSH icon id, falling back to the generic server glyph when the id is
     * null/blank. Shared by {@link #forSession} and the SFTP browser's connection bar so the two
     * resolve SSH icons identically.
     */
    public static Icon forIconId(String iconId, int size) {
        String id = (iconId != null && !iconId.isBlank()) ? iconId : "builtin/server";
        return IconLibrary.get().icon(id, size);
    }
}
