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
package com.katmoda.jterm.keymap;

/**
 * The set of bindable actions. Each has a stable id used as the JSON key in
 * {@code keymap.json} and a default {@link javax.swing.KeyStroke} string
 * (see {@link javax.swing.KeyStroke#getKeyStroke(String)}).
 */
public enum TermAction {
    NEW_TAB("new-tab", "New Tab", "control T"),
    CLOSE_TAB("close-tab", "Close Tab", "control W"),
    SPLIT_COLUMN("split-column", "Split Column", "control RIGHT"),
    SPLIT_ROW("split-row", "Split Row", "control DOWN"),
    CLOSE_PANE("close-pane", "Close Pane", "control UP"),
    OPEN_LOCAL("open-local", "Open Local Shell", "control shift T"),
    OPEN_SFTP("open-sftp", "Open SFTP Browser", "control F"),
    OPEN_TUNNELS("open-tunnels", "Tunneling…", "control shift P"),
    TOGGLE_BROADCAST("toggle-broadcast", "Toggle Broadcast", "control shift B"),
    TOGGLE_THEME("toggle-theme", "Toggle Light/Dark", "control shift L"),
    DUPLICATE_SESSION("duplicate-session", "Duplicate Session", "control shift D"),
    DUPLICATE_PANE_SPLIT("duplicate-pane-split", "Duplicate Pane to Split", "control alt D"),
    DUPLICATE_PANE_TAB("duplicate-pane-tab", "Duplicate Pane to Tab", "control alt shift D"),
    MOVE_SESSION_UP("move-session-up", "Move Session Up", "control shift UP"),
    MOVE_SESSION_DOWN("move-session-down", "Move Session Down", "control shift DOWN"),
    MOVE_TAB_LEFT("move-tab-left", "Move Tab Left", "control shift LEFT"),
    MOVE_TAB_RIGHT("move-tab-right", "Move Tab Right", "control shift RIGHT"),
    DUPLICATE_TAB("duplicate-tab", "Duplicate Tab", "control shift K"),
    DETACH_TAB("detach-tab", "Detach Tab to New Window", "control shift O"),
    ATTACH_TAB("attach-tab", "Attach Tab to Main Window", "control shift I"),
    FONT_INCREASE("font-increase", "Increase Font Size", "control ADD"),
    FONT_DECREASE("font-decrease", "Decrease Font Size", "control SUBTRACT"),
    FONT_RESET("font-reset", "Reset Font Size", "control NUMPAD0");

    private final String id;
    private final String label;
    private final String defaultStroke;

    TermAction(String id, String label, String defaultStroke) {
        this.id = id;
        this.label = label;
        this.defaultStroke = defaultStroke;
    }

    public String id() {
        return id;
    }

    /** Human-readable name shown in menus and the shortcut editor. */
    public String label() {
        return label;
    }

    public String defaultStroke() {
        return defaultStroke;
    }
}
