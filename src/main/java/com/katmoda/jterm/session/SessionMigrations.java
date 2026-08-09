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
package com.katmoda.jterm.session;

/**
 * One-shot upgrades applied to a loaded {@code sessions.json} tree, keyed off
 * {@link FolderNode#getSchemaVersion()} on the root so each runs exactly once.
 *
 * <p>Run from {@link SessionStore}'s constructor, before anything reads the tree.</p>
 */
final class SessionMigrations {

    /** Schema version written by this build. */
    static final int CURRENT_VERSION = 1;

    /** The size the pre-v1 session dialog forced onto every session (its spinner's seed value). */
    private static final int LEGACY_FORCED_FONT_SIZE = 14;

    private SessionMigrations() {
    }

    /**
     * Brings {@code root} up to {@link #CURRENT_VERSION}, returning whether anything changed (so the
     * caller can skip a needless rewrite). The version stamp itself is the caller's job — it must be
     * written even when nothing changed, or the migration would run again on the next launch.
     */
    static boolean migrate(FolderNode root) {
        int from = root.getSchemaVersion() == null ? 0 : root.getSchemaVersion();
        boolean changed = false;
        if (from < 1) {
            changed = clearForcedFontSize(root);
        }
        return changed;
    }

    /**
     * v0 → v1: terminal font size gained an "inherit the application default" state ({@code 0}).
     * Before that the session dialog's spinner could only write a concrete number, so every saved
     * session came out pinned to {@value #LEGACY_FORCED_FONT_SIZE} whether the user chose it or not
     * — and then ignored Preferences → Terminal Settings → Font Size forever after.
     *
     * <p>Only that exact value is cleared: any other size can only have come from someone typing it,
     * so it stays. A size of {@value #LEGACY_FORCED_FONT_SIZE} chosen deliberately *after* this
     * migration also survives, because the version stamp stops it running twice.</p>
     */
    private static boolean clearForcedFontSize(FolderNode root) {
        boolean changed = false;
        for (SshSessionConfig cfg : SessionStore.collectSshSessions(root)) {
            if (cfg.getFontSize() == LEGACY_FORCED_FONT_SIZE) {
                cfg.setFontSize(0);
                changed = true;
            }
        }
        return changed;
    }
}
