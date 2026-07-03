/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionExportServiceTest {

    @TempDir
    Path dir;

    private static SessionExport sampleExport() {
        SshSessionConfig ssh = new SshSessionConfig();
        ssh.setName("web-1");
        ssh.setHost("web1.example.com");
        ssh.setUser("deploy");
        FolderNode folder = new FolderNode("Production");
        folder.getChildren().add(ssh);
        SessionExport export = new SessionExport();
        export.folder = folder;
        return export;
    }

    @Test
    void plainExportRoundTrips() throws Exception {
        Path target = dir.resolve("sessions-export.json");
        SessionExportService.writePlain(target, sampleExport());

        assertFalse(SessionExportService.isEncrypted(target.toFile()));
        SessionExport read = SessionExportService.readPlain(target.toFile());
        assertEquals("Production", read.folder.getName());
        SshSessionConfig ssh = (SshSessionConfig) read.folder.getChildren().get(0);
        assertEquals("web1.example.com", ssh.getHost());
        assertEquals("deploy", ssh.getUser());
        assertTrue(read.credentials.isEmpty());
    }

    @Test
    void encryptedExportRoundTripsWithTheRightPassphrase() throws Exception {
        Path target = dir.resolve("sessions-export.json");
        SessionExport export = sampleExport();
        String id = ((SshSessionConfig) export.folder.getChildren().get(0)).getId();
        export.credentials.put(id, "s3cret");
        SessionExportService.writeEncrypted(target, export, "hunter2".toCharArray());
        // The service clears the plaintext-password references once the envelope is sealed.
        assertTrue(export.credentials.isEmpty());

        assertTrue(SessionExportService.isEncrypted(target.toFile()));
        EncryptedSessionExport envelope = SessionExportService.readEnvelope(target.toFile());
        SessionExport read = SessionExportService.openEnvelope(envelope, "hunter2".toCharArray());
        assertEquals("Production", read.folder.getName());
        assertEquals("s3cret", read.credentials.get(id));
    }

    @Test
    void wrongPassphraseYieldsNullNotAnException() throws Exception {
        Path target = dir.resolve("sessions-export.json");
        SessionExport export = sampleExport();
        export.credentials.put(((SshSessionConfig) export.folder.getChildren().get(0)).getId(), "s3cret");
        SessionExportService.writeEncrypted(target, export, "hunter2".toCharArray());

        EncryptedSessionExport envelope = SessionExportService.readEnvelope(target.toFile());
        assertNull(SessionExportService.openEnvelope(envelope, "wrong".toCharArray()));
    }

    @Test
    void reassignIdsFreshensEveryIdAndRemapsCredentials() {
        SessionExport export = sampleExport();
        SshSessionConfig ssh = (SshSessionConfig) export.folder.getChildren().get(0);
        String oldFolderId = export.folder.getId();
        String oldSessionId = ssh.getId();
        Map<String, String> oldCreds = Map.of(oldSessionId, "s3cret");

        Map<String, String> remapped = new LinkedHashMap<>();
        SessionExportService.reassignIds(export.folder, oldCreds, remapped);

        assertNotEquals(oldFolderId, export.folder.getId());
        assertNotEquals(oldSessionId, ssh.getId());
        assertEquals("s3cret", remapped.get(ssh.getId()));
        assertFalse(remapped.containsKey(oldSessionId));
    }
}
