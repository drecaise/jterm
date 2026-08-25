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
package com.katmoda.jterm.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests of the two bits of {@link UpdateChecker} that handle untrusted response data. */
class UpdateCheckerTest {

    private static final String GENUINE =
            "https://github.com/drecaise/jterm/releases/tag/v1.9.0";

    @Test
    void acceptsAGenuineReleaseUrl() {
        assertEquals(GENUINE, UpdateChecker.safeReleaseUrl(GENUINE));
        assertEquals(GENUINE, UpdateChecker.safeReleaseUrl("  " + GENUINE + "  "));
        assertEquals("https://github.com/drecaise/jterm/releases/download/v1.9.0/jterm.jar",
                UpdateChecker.safeReleaseUrl(
                        "https://github.com/drecaise/jterm/releases/download/v1.9.0/jterm.jar"));
    }

    @Test
    void rejectsNonHttpsSchemes() {
        assertRejected("http://github.com/drecaise/jterm/releases/tag/v1.9.0");
        assertRejected("javascript:alert(1)");
        assertRejected("file:///etc/passwd");
        assertRejected("ftp://github.com/drecaise/jterm/releases/tag/v1.9.0");
    }

    @Test
    void rejectsLookalikeHosts() {
        assertRejected("https://github.com.evil.tld/drecaise/jterm/releases/tag/v1.9.0");
        assertRejected("https://raw.githubusercontent.com/drecaise/jterm/releases/tag/v1.9.0");
        assertRejected("https://evil.tld/drecaise/jterm/releases/tag/v1.9.0");
        // Userinfo trick: the real host is evil.tld, not github.com.
        assertRejected("https://github.com@evil.tld/drecaise/jterm/releases/tag/v1.9.0");
    }

    @Test
    void rejectsOtherRepositoriesAndPaths() {
        assertRejected("https://github.com/someone/else/releases/tag/v1.9.0");
        assertRejected("https://github.com/drecaise/jterm-evil/releases/tag/v1.9.0");
        assertRejected("https://github.com/drecaise/jterm/issues/1");
        assertRejected("https://github.com/");
    }

    @Test
    void rejectsMissingAndMalformedInput() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
        assertRejected("not a url at all");
        assertRejected("https://git hub.com/drecaise/jterm/releases/tag/v1");
    }

    @Test
    void hostComparisonIsCaseInsensitive() {
        assertEquals("https://GitHub.COM/drecaise/jterm/releases/tag/v1.9.0",
                UpdateChecker.safeReleaseUrl("https://GitHub.COM/drecaise/jterm/releases/tag/v1.9.0"));
    }

    @Test
    void trimNotesNormalisesAndTruncates() {
        assertEquals("", UpdateChecker.trimNotes(null));
        assertEquals("", UpdateChecker.trimNotes("   \n  "));
        assertEquals("one\ntwo", UpdateChecker.trimNotes("\n one\r\ntwo \n"));

        String long_ = "x".repeat(UpdateChecker.MAX_NOTES_CHARS + 500);
        String trimmed = UpdateChecker.trimNotes(long_);
        assertTrue(trimmed.length() < long_.length());
        assertTrue(trimmed.startsWith("x".repeat(100)));
        assertTrue(trimmed.endsWith("see the release page for the rest."));
    }

    private static void assertRejected(String url) {
        assertEquals(UpdateChecker.RELEASES_PAGE, UpdateChecker.safeReleaseUrl(url),
                "should have fallen back to the releases page for: " + url);
    }
}
