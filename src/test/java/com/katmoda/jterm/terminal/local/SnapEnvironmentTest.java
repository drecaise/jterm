/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.local;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the Snap runtime plumbing that must not follow a local shell out of the snap. */
class SnapEnvironmentTest {

    private static Map<String, String> snapEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("SNAP", "/snap/jterm/42");
        env.put("SNAP_NAME", "jterm");
        env.put("SNAP_REVISION", "42");
        env.put("SNAP_REAL_HOME", "/home/u");
        env.put("HOME", "/home/u/snap/jterm/42");
        env.put("LD_LIBRARY_PATH", "/snap/jterm/42/usr/lib");
        env.put("LOCPATH", "/snap/jterm/42/usr/lib/locale");
        env.put("JAVA_HOME", "/snap/jterm/42/usr/lib/jvm/java-21-openjdk");
        env.put("PATH", "/snap/jterm/42/usr/bin:/snap/jterm/42/bin:/usr/bin:/bin");
        env.put("XDG_DATA_DIRS", "/snap/jterm/42/usr/share:/usr/share");
        env.put("TERM", "xterm-256color");
        return env;
    }

    @Test
    void leavesANonSnapEnvironmentAlone() {
        Map<String, String> env = Map.of("PATH", "/usr/bin", "HOME", "/home/u");

        assertSame(env, SnapEnvironment.sanitize(env));
    }

    @Test
    void dropsSnapVarsAndRuntimePlumbing() {
        Map<String, String> out = SnapEnvironment.sanitize(snapEnv());

        assertFalse(out.containsKey("SNAP"));
        assertFalse(out.containsKey("SNAP_NAME"));
        assertFalse(out.containsKey("SNAP_REVISION"));
        assertFalse(out.containsKey("SNAP_REAL_HOME"));
        assertFalse(out.containsKey("LD_LIBRARY_PATH"));
        assertFalse(out.containsKey("LOCPATH"));
        assertFalse(out.containsKey("JAVA_HOME"));
        assertEquals("xterm-256color", out.get("TERM"), "unrelated vars survive");
    }

    @Test
    void stripsSnapEntriesFromPathLikeVars() {
        Map<String, String> out = SnapEnvironment.sanitize(snapEnv());

        assertEquals("/usr/bin:/bin", out.get("PATH"));
        assertEquals("/usr/share", out.get("XDG_DATA_DIRS"));
    }

    @Test
    void restoresTheRealHome() {
        assertEquals("/home/u", SnapEnvironment.sanitize(snapEnv()).get("HOME"));
    }

    /** A var that merely starts with the letters SNAP is the user's, not snapd's. */
    @Test
    void keepsUserVarsThatOnlyLookLikeSnapVars() {
        Map<String, String> env = snapEnv();
        env.put("SNAPSHOT_DIR", "/data/snapshots");

        assertEquals("/data/snapshots", SnapEnvironment.sanitize(env).get("SNAPSHOT_DIR"));
    }

    @Test
    void removesAPathLikeVarThatWasEntirelySnap() {
        Map<String, String> env = snapEnv();
        env.put("XDG_DATA_DIRS", "/snap/jterm/42/usr/share");

        assertFalse(SnapEnvironment.sanitize(env).containsKey("XDG_DATA_DIRS"));
    }

    @Test
    void doesNotMutateTheInputMap() {
        Map<String, String> env = snapEnv();

        SnapEnvironment.sanitize(env);

        assertTrue(env.containsKey("SNAP"));
        assertEquals("/snap/jterm/42/usr/bin:/snap/jterm/42/bin:/usr/bin:/bin", env.get("PATH"));
    }
}
