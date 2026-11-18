/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.rdp.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders.Verdict.CONFIRM;
import static com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders.Verdict.OK;
import static com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders.Verdict.REFUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DriveNamesAndProtectedFoldersTest {

    @TempDir
    Path tmp;

    @Test
    void namesAreReducedToWhatTheAnnouncementCarries() {
        assertEquals("My_Documents", DriveNames.sanitize("My Documents"));
        assertEquals("caf__", DriveNames.sanitize("café!"));
        assertEquals("share", DriveNames.sanitize("  "));
        assertEquals("a".repeat(31), DriveNames.sanitize("a".repeat(40)));
        assertEquals("root", DriveNames.defaultFor(Path.of("/")));
        assertEquals("src", DriveNames.defaultFor(Path.of("/home/me/src")));
    }

    @Test
    void namesAreMadeUniqueIgnoringCase() {
        assertEquals(List.of("docs", "Docs_2", "docs_3", "src"), DriveNames.unique(List.of("docs", "Docs", "docs", "src")));
        String longName = "x".repeat(31);
        List<String> two = DriveNames.unique(List.of(longName, longName));
        assertEquals(31, two.get(1).length());
        assertEquals("x".repeat(29) + "_2", two.get(1));
    }

    @Test
    void protectedFoldersAndTheirContentsAreRefused() throws Exception {
        Path home = Files.createDirectory(tmp.resolve("home"));
        Path ssh = Files.createDirectory(home.resolve(".ssh"));
        Path config = Files.createDirectories(home.resolve(".config").resolve("jterm"));
        Path docs = Files.createDirectory(home.resolve("docs"));
        Files.createDirectory(ssh.resolve("inner"));
        Files.createSymbolicLink(home.resolve("sshlink"), ssh);
        List<Path> prot = List.of(config, ssh);

        assertEquals(REFUSE, ProtectedFolders.classify(ssh, prot, home));
        assertEquals(REFUSE, ProtectedFolders.classify(ssh.resolve("inner"), prot, home));
        assertEquals(REFUSE, ProtectedFolders.classify(config, prot, home));
        assertEquals(REFUSE, ProtectedFolders.classify(home.resolve("sshlink"), prot, home), "even through a link");
        assertEquals(CONFIRM, ProtectedFolders.classify(home, prot, home), "the home folder itself");
        assertEquals(CONFIRM, ProtectedFolders.classify(tmp, prot, home), "a folder above it");
        assertEquals(CONFIRM, ProtectedFolders.classify(tmp.getRoot(), prot, home), "a root");
        assertEquals(OK, ProtectedFolders.classify(docs, prot, home));
    }
}
