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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two pieces of local-shell directory handling: seeding {@code %PROMPT%} so a {@code cmd.exe} on
 * Windows reports its directory at all (there is no readable equivalent of {@code /proc/<pid>/cwd}
 * there), and displaying the user's own home as {@code ~}.
 */
class LocalSessionPromptTest {

    @Test
    void seedsAPromptWhenWindowsHasNone() {
        Map<String, String> env = new HashMap<>(Map.of("TERM", "xterm-256color"));

        LocalSession.seedWindowsPrompt(env, true);

        assertEquals(LocalSession.WINDOWS_CWD_PROMPT, env.get("PROMPT"));
    }

    @Test
    void theSeededPromptReportsTheDirectoryAndStillRendersNormally() {
        // $e]9;9;$p<ST> is the report; $p$g is cmd's ordinary "C:\path>" prompt.
        assertEquals("$e]9;9;$p$e\\$p$g", LocalSession.WINDOWS_CWD_PROMPT);
    }

    @Test
    void aPromptTheUserAlreadySetIsLeftAlone() {
        for (String key : new String[]{"PROMPT", "prompt", "Prompt"}) {
            Map<String, String> env = new HashMap<>();
            env.put(key, "$g");

            LocalSession.seedWindowsPrompt(env, true);

            // Windows environment names are case-insensitive but this map is not, so every key has
            // to be checked — containsKey("PROMPT") alone would overwrite an inherited "Prompt".
            assertEquals("$g", env.get(key), "clobbered " + key);
            assertEquals(1, env.size(), "added a second prompt alongside " + key);
        }
    }

    @Test
    void homeIsShownAsATildeForLocalPaths() {
        String home = System.getProperty("user.home");
        assumeTrue(home != null && home.startsWith("/"), "POSIX home only");

        assertEquals("~", LocalSession.shortenHome(home));
        assertEquals("~/git/jterm", LocalSession.shortenHome(home + "/git/jterm"));
        assertEquals("/var/log", LocalSession.shortenHome("/var/log"));
        assertNull(LocalSession.shortenHome(null));
    }

    @Test
    void aSiblingDirectoryWithTheHomeAsAPrefixIsNotMangled() {
        String home = System.getProperty("user.home");
        assumeTrue(home != null && home.startsWith("/"), "POSIX home only");

        // A plain startsWith would turn /home/markus into "~us".
        assertEquals(home + "us", LocalSession.shortenHome(home + "us"));
    }

    @Test
    void neverTouchesTheEnvironmentOffWindows() {
        Map<String, String> env = new HashMap<>();

        LocalSession.seedWindowsPrompt(env, false);

        assertTrue(env.isEmpty());
        assertFalse(env.containsKey("PROMPT"));
    }
}
