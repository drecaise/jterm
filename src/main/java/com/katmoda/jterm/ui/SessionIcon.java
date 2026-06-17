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
import com.katmoda.jterm.terminal.local.LocalSession;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.Icon;

/**
 * Resolves the display icon for a session, shared by the tab strip and the per-pane title bar so
 * the two never drift: an SSH session uses its saved icon (or the generic server glyph), a WSL
 * distro uses its custom local icon, and a plain shell uses a theme-contrasting terminal glyph.
 */
public final class SessionIcon {

    private SessionIcon() {
    }

    public static Icon forSession(TerminalSession session, int size) {
        if (session instanceof SshSession ssh) {
            return forIconId(ssh.iconId(), size);
        }
        if (session instanceof LocalSession local && local.iconId() != null) {
            return IconLibrary.get().icon(local.iconId(), size);
        }
        // Plain shell: a light glyph reads on the dark theme's strip, and vice-versa.
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
