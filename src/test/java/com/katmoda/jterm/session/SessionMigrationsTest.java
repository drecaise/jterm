/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.session;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.terminal.TerminalProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the v0 → v1 migration that frees saved sessions from the font size the old dialog forced
 * on them, plus the inheritance contract it hands them back to.
 */
class SessionMigrationsTest {

    private static SshSessionConfig session(String name, int fontSize) {
        SshSessionConfig cfg = new SshSessionConfig();
        cfg.setName(name);
        cfg.setFontSize(fontSize);
        return cfg;
    }

    /** root → [forced 14, deliberate 20, already inheriting 0] and sub/ → [forced 14]. */
    private static FolderNode tree() {
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(session("forced", 14));
        root.getChildren().add(session("deliberate", 20));
        root.getChildren().add(session("inheriting", 0));
        FolderNode sub = new FolderNode("sub");
        sub.getChildren().add(session("nested-forced", 14));
        root.getChildren().add(sub);
        return root;
    }

    private static int fontSizeOf(FolderNode root, String name) {
        return SessionStore.collectSshSessions(root).stream()
                .filter(cfg -> name.equals(cfg.getName()))
                .findFirst().orElseThrow().getFontSize();
    }

    @Test
    void clearsTheForcedFontSizeAtEveryDepth() {
        FolderNode root = tree();
        assertTrue(SessionMigrations.migrate(root));
        assertEquals(0, fontSizeOf(root, "forced"));
        assertEquals(0, fontSizeOf(root, "nested-forced"));
    }

    @Test
    void keepsDeliberateAndAlreadyInheritingSizes() {
        FolderNode root = tree();
        SessionMigrations.migrate(root);
        assertEquals(20, fontSizeOf(root, "deliberate"));
        assertEquals(0, fontSizeOf(root, "inheriting"));
    }

    @Test
    void isASingleShotOnceTheVersionIsStamped() {
        FolderNode root = tree();
        root.setSchemaVersion(SessionMigrations.CURRENT_VERSION);
        assertFalse(SessionMigrations.migrate(root));
        assertEquals(14, fontSizeOf(root, "forced"), "a 14 chosen after the migration must survive");
    }

    @Test
    void reportsNoChangeWhenNothingWasForced() {
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(session("deliberate", 20));
        assertFalse(SessionMigrations.migrate(root));
    }

    @Test
    void aClearedSizeResolvesToTheApplicationDefault() {
        AppSettings settings = AppSettings.get();
        TerminalProfile profile = settings.resolve("", "", "", 0);
        assertEquals(settings.getDefaultFontSize(), profile.fontSize());
    }
}
