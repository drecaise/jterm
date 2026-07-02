/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.sftp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks in the path-traversal guard for server-supplied filenames in an SFTP download
 * ({@link SftpTransfer#safeResolve}).
 */
class SftpTransferTest {

    private final Path root = Path.of("/tmp/jterm-download-root");

    @Test
    void rejectsParentTraversal() {
        assertNull(SftpTransfer.safeResolve(root, ".."));
    }

    @Test
    void rejectsCurrentDir() {
        assertNull(SftpTransfer.safeResolve(root, "."));
    }

    @Test
    void rejectsEmbeddedSlash() {
        assertNull(SftpTransfer.safeResolve(root, "a/b"));
        assertNull(SftpTransfer.safeResolve(root, "../etc/passwd"));
    }

    @Test
    void rejectsBackslashAndNul() {
        assertNull(SftpTransfer.safeResolve(root, "a\\b"));
        assertNull(SftpTransfer.safeResolve(root, "a\0b"));
    }

    @Test
    void rejectsEmptyName() {
        assertNull(SftpTransfer.safeResolve(root, ""));
    }

    @Test
    void acceptsPlainNameUnderRoot() {
        Path resolved = SftpTransfer.safeResolve(root, "report.txt");
        assertEquals(root.resolve("report.txt"), resolved);
    }
}
