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
package com.katmoda.jterm.terminal.rdp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelperSandboxTest {

    @Test
    void parsesTheFullReport() {
        HelperSandbox s = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=full landlock=6 net=1 scope=1 seccomp=1 fsize=1 nnp=1");
        assertEquals(HelperSandbox.Level.FULL, s.level());
        assertEquals(6, s.landlock());
        assertTrue(s.network() && s.scopes() && s.seccomp() && s.fileSizeLimit());
        assertEquals("Helper sandbox: full", s.describe());
    }

    @Test
    void partialSaysWhatIsMissing() {
        HelperSandbox old = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=partial landlock=3 net=0 scope=0 seccomp=1 fsize=1 nnp=1");
        assertEquals("Helper sandbox: partial (no network rule: this kernel's Landlock is too old)", old.describe());
        HelperSandbox none = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=partial landlock=0 net=0 scope=0 seccomp=1 fsize=1 nnp=1");
        assertEquals("Helper sandbox: partial (no file restrictions: this kernel has no Landlock)",
                none.describe());
    }

    @Test
    void windowsW1IsPartialAndSaysWhatItCovers() {
        HelperSandbox s = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=partial integrity=low job=1 mitigations=4");
        assertEquals(HelperSandbox.Level.PARTIAL, s.level());
        assertTrue(s.windows());
        assertEquals("Helper sandbox: partial (it can't change your files or reach other programs, "
                + "but can still read files)", s.describe());
    }

    @Test
    void windowsW2SaysWhatTheRestrictedTokenAdds() {
        HelperSandbox s = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=partial integrity=low job=1 mitigations=4 token=restricted");
        assertTrue(s.windows() && s.restrictedToken());
        assertEquals("Helper sandbox: partial (it can't reach your files, saved passwords, microphone or "
                + "other programs, but can connect to any computer)", s.describe());
    }

    @Test
    void macOsSeatbeltIsFull() {
        HelperSandbox s = HelperSandbox.parseHelperLine("jterm-rdp-helper: sandbox level=full seatbelt=1 ports=1");
        assertEquals(HelperSandbox.Level.FULL, s.level());
        assertEquals("Helper sandbox: full", s.describe());
        HelperSandbox failed = HelperSandbox.parseHelperLine(
                "jterm-rdp-helper: sandbox level=none seatbelt=0 (profile too long)");
        assertEquals("Helper sandbox: none", failed.describe());
    }

    @Test
    void platformsWithoutALockdownReportNone() {
        HelperSandbox s = HelperSandbox.parseHelperLine("jterm-rdp-helper: sandbox level=none");
        assertEquals(HelperSandbox.Level.NONE, s.level());
        assertEquals("Helper sandbox: none", s.describe());
    }

    @Test
    void otherLinesAreNotReports() {
        assertNull(HelperSandbox.parseHelperLine("jterm-rdp-helper: stats ms=1000 frames=3"));
        assertNull(HelperSandbox.parseHelperLine("jterm-rdp-helper: sandbox level=bogus"));
        assertNull(HelperSandbox.parseHelperLine("jterm-rdp-helper: selftest execve blocked"));
        assertNull(HelperSandbox.parseHelperLine(null));
    }
}
