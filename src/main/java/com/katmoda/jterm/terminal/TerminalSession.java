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

    /** Stable human-readable name for this session (an SSH session's name, a WSL distro, "local"). */
    String title();

    /**
     * This session's live working directory, or {@code null} when it cannot be read. Only a
     * directly-spawned local shell on Linux can answer from the OS ({@code /proc/<pid>/cwd}); for
     * every other kind the directory is learned from escape sequences the shell emits and is tracked
     * by the pane, not here. Kept separate from {@link #title()} so a label can show both.
     */
    default String workingDirectory() {
        return null;
    }

    /** Per-session terminal/font settings; defaults to {@link TerminalProfile#DEFAULT}. */
    default TerminalProfile profile() {
        return TerminalProfile.DEFAULT;
    }

    /**
     * This session's output-highlighting override id, or {@code null} to inherit the global default.
     * See {@code com.katmoda.jterm.highlight.HighlightListResolver}. Local sessions inherit; SSH
     * sessions carry their saved override.
     */
    default String highlightListOverrideId() {
        return null;
    }

    /**
     * Icon-library id for this session's tab/pane, or {@code null} to use the type-default glyph.
     * SSH sessions carry their saved icon; local shells return {@code null} except for special
     * kinds (e.g. a WSL distro).
     */
    default String iconId() {
        return null;
    }

    /**
     * Custom tab color as {@code "#RRGGBB"}, or {@code null} for the theme default. Currently only
     * SSH sessions carry one; local shells inherit the default.
     */
    default String tabColorHex() {
        return null;
    }

    /** Whether the underlying process/channel is still running. */
    boolean isAlive();

    /** Terminate the back-end and release resources. */
    void close();
}
