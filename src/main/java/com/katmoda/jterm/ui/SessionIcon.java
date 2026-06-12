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
            String id = (ssh.iconId() != null && !ssh.iconId().isBlank()) ? ssh.iconId() : "builtin/server";
            return IconLibrary.get().icon(id, size);
        }
        if (session instanceof LocalSession local && local.iconId() != null) {
            return IconLibrary.get().icon(local.iconId(), size);
        }
        // Plain shell: a light glyph reads on the dark theme's strip, and vice-versa.
        String name = ThemeManager.get().isDark() ? "icons/terminal-light.svg" : "icons/terminal-dark.svg";
        return new FlatSVGIcon(name, size, size);
    }
}
