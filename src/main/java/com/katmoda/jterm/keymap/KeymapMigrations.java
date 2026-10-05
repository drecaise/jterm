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

import javax.swing.KeyStroke;
import java.util.Map;

/**
 * One-shot upgrades applied to a loaded {@code keymap.json}, keyed off a {@value #VERSION_KEY}
 * entry so each runs exactly once.
 *
 * <p>The file is a flat {@code action id → stroke} map and is written with the defaults on first
 * run, so changing a compiled-in default reaches nobody who has already launched the app: their
 * file pins the old one. The version lives in the same map rather than in a wrapper object, which
 * keeps the file readable by older builds (they look entries up by action id and ignore the rest).</p>
 */
final class KeymapMigrations {

    /** Schema version written by this build. */
    static final int CURRENT_VERSION = 1;

    /** The map key holding the schema version; not an action id. */
    static final String VERSION_KEY = "schemaVersion";

    /** Open SFTP's default before v1. */
    private static final String LEGACY_SFTP_STROKE = "control F";

    private KeymapMigrations() {
    }

    /**
     * Brings {@code raw} up to {@link #CURRENT_VERSION} and stamps it, returning whether the map
     * changed and so needs writing back. The stamp is written even when no binding moved, or the
     * migration would run again on the next launch.
     */
    static boolean migrate(Map<String, String> raw) {
        int from = versionOf(raw);
        if (from >= CURRENT_VERSION) {
            return false;
        }
        if (from < 1) {
            moveSftpOffCtrlF(raw);
        }
        raw.put(VERSION_KEY, Integer.toString(CURRENT_VERSION));
        return true;
    }

    /**
     * v0 → v1: Open SFTP moved from Ctrl+F to Ctrl+Shift+F, because the global dispatcher claiming
     * Ctrl+F left the terminal's own search (JediTerm's find, also Ctrl+F) unreachable.
     *
     * <p>Only a binding still on the old default moves. Strokes are compared parsed, not as text:
     * the first-run file spells it {@code "control F"} while a save from the shortcut editor writes
     * {@code KeyStroke.toString()} ({@code "ctrl pressed F"}). It stays put when another action
     * already holds Ctrl+Shift+F — two actions on one stroke would make one of them unreachable,
     * and that binding was the user's choice. A deliberate rebind back to Ctrl+F *after* this
     * migration also survives, because the version stamp stops it running twice.</p>
     */
    private static void moveSftpOffCtrlF(Map<String, String> raw) {
        String sftpId = TermAction.OPEN_SFTP.id();
        String current = raw.get(sftpId);
        if (current == null) {
            return; // not pinned in the file: it already resolves to the compiled-in default
        }
        if (!KeyStroke.getKeyStroke(LEGACY_SFTP_STROKE).equals(KeyStroke.getKeyStroke(current))) {
            return;
        }
        KeyStroke target = KeyStroke.getKeyStroke(TermAction.OPEN_SFTP.defaultStroke());
        for (TermAction action : TermAction.values()) {
            String stroke = raw.getOrDefault(action.id(), action.defaultStroke());
            if (action != TermAction.OPEN_SFTP && target.equals(KeyStroke.getKeyStroke(stroke))) {
                return;
            }
        }
        raw.put(sftpId, TermAction.OPEN_SFTP.defaultStroke());
    }

    private static int versionOf(Map<String, String> raw) {
        String value = raw.get(VERSION_KEY);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
