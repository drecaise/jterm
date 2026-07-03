/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialVaultTest {

    @TempDir
    Path dir;

    private CredentialVault newVault() {
        return new CredentialVault(dir.resolve("credentials.json"));
    }

    @Test
    void initializeThenUnlockWithCorrectPassword() throws Exception {
        newVault().initialize("master".toCharArray());

        CredentialVault reopened = newVault();
        assertTrue(reopened.isInitialized());
        assertTrue(reopened.unlock("master".toCharArray()));
        assertTrue(reopened.isUnlocked());
    }

    @Test
    void unlockWithWrongPasswordReturnsFalse() throws Exception {
        newVault().initialize("master".toCharArray());

        CredentialVault reopened = newVault();
        assertFalse(reopened.unlock("not-the-master".toCharArray()));
        assertFalse(reopened.isUnlocked());
    }

    @Test
    void passwordRoundTripsAcrossReopen() throws Exception {
        CredentialVault vault = newVault();
        vault.initialize("master".toCharArray());
        vault.setPassword("session-1", "hunter2".toCharArray());

        CredentialVault reopened = newVault();
        assertTrue(reopened.unlock("master".toCharArray()));
        assertEquals("hunter2", reopened.getPassword("session-1"));
        assertTrue(reopened.hasPassword("session-1"));

        reopened.removePassword("session-1");
        assertFalse(reopened.hasPassword("session-1"));
        assertNull(reopened.getPassword("session-1"));
    }

    @Test
    void corruptFileIsPreservedAndTreatedAsUnavailable() throws Exception {
        Path file = dir.resolve("credentials.json");
        Files.writeString(file, "{ this is not valid vault json");

        CredentialVault vault = new CredentialVault(file);
        // Corrupt ≠ missing: the vault reports a load failure and is not "initialized".
        assertTrue(vault.isLoadFailed());
        assertFalse(vault.isInitialized());

        // The unreadable file was moved aside (data preserved), not left in place.
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(dir.resolve("credentials.json.unreadable-1")));

        // A fresh vault must never be written over the corrupt one.
        assertThrows(VaultException.class, () -> vault.initialize("master".toCharArray()));
        assertFalse(Files.exists(file));
    }
}
