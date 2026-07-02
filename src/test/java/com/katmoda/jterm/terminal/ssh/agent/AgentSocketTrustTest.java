/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.ssh.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@code $SSH_AUTH_SOCK} trust check ({@link AgentSupport#isTrustedUnixSocket}) that
 * stops a hostile agent socket from hijacking key-signing.
 */
class AgentSocketTrustTest {

    @TempDir
    Path dir;

    @Test
    void rejectsMissingPath() {
        assertFalse(AgentSupport.isTrustedUnixSocket(dir.resolve("nope.sock").toString()));
    }

    @Test
    void rejectsRegularFile() throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-socket"), "x");
        assertFalse(AgentSupport.isTrustedUnixSocket(file.toString()));
    }

    @Test
    void rejectsDirectory() {
        assertFalse(AgentSupport.isTrustedUnixSocket(dir.toString()));
    }

    @Test
    void acceptsOwnerOnlySocketAndRejectsGroupWritable() throws IOException {
        Path sock = dir.resolve("agent.sock");
        try (ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.bind(UnixDomainSocketAddress.of(sock));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "AF_UNIX sockets not supported here");
            return;
        }
        assumeTrue(supportsPosix(sock), "non-POSIX filesystem");

        Files.setPosixFilePermissions(sock,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertTrue(AgentSupport.isTrustedUnixSocket(sock.toString()));

        Files.setPosixFilePermissions(sock, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE));
        assertFalse(AgentSupport.isTrustedUnixSocket(sock.toString()));
    }

    private static boolean supportsPosix(Path p) {
        try {
            Files.getPosixFilePermissions(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
