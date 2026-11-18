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
    void microphoneNeverLeavesInAnExportNorArrivesInAnImport() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("desktop");
        rdp.setPlaySound(true);
        rdp.setUseMicrophone(true);
        rdp.setUseKerberos(true);
        FolderNode folder = new FolderNode("Desktops");
        folder.getChildren().add(rdp);
        SessionExport export = new SessionExport();
        export.folder = folder;

        Path target = dir.resolve("export.json");
        SessionExportService.writePlain(target, export);
        String json = java.nio.file.Files.readString(target);
        assertFalse(json.contains("useMicrophone"), json);
        assertFalse(json.contains("useKerberos"), json);
        assertTrue(rdp.isUseMicrophone(), "the live session keeps it");
        RdpSessionConfig back = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertTrue(back.isPlaySound(), "sound travels as before");
        assertFalse(back.isUseMicrophone());

        // A crafted import that turns it on arrives with it off.
        java.nio.file.Files.writeString(target, json.replace("\"host\" : \"desktop\"",
                "\"host\" : \"desktop\", \"useMicrophone\" : true, \"useKerberos\" : true"));
        assertTrue(java.nio.file.Files.readString(target).contains("useMicrophone"));
        RdpSessionConfig crafted = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertFalse(crafted.isUseMicrophone());
        assertFalse(crafted.isUseKerberos());
    }

    @Test
    void thePrinterTravels_butNeverTheLocalPrinterThatPrintsWithoutAsking() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("desktop");
        rdp.setPrintToJterm(true);
        rdp.setPrinterIsDefault(true);
        rdp.setAutoPrintPrinter("Office laser");
        FolderNode folder = new FolderNode("Desktops");
        folder.getChildren().add(rdp);
        SessionExport export = new SessionExport();
        export.folder = folder;

        Path target = dir.resolve("export.json");
        SessionExportService.writePlain(target, export);
        String json = java.nio.file.Files.readString(target);
        assertFalse(json.contains("autoPrintPrinter"), json);
        assertFalse(json.contains("Office laser"), json);
        RdpSessionConfig back = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertTrue(back.isPrintToJterm());
        assertTrue(back.isPrinterIsDefault());
        assertEquals("", back.getAutoPrintPrinter());

        // A crafted import naming a local printer arrives asking each time.
        java.nio.file.Files.writeString(target, json.replace("\"host\" : \"desktop\"",
                "\"host\" : \"desktop\", \"autoPrintPrinter\" : \"Office laser\""));
        RdpSessionConfig crafted = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertEquals("", crafted.getAutoPrintPrinter());
    }

    @Test
    void smartCardsNeverTravel_inEitherDirection() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("desktop");
        rdp.setUseSmartCards(true);
        rdp.setSmartCardReaders(java.util.List.of("Yubico YubiKey OTP+FIDO+CCID"));
        FolderNode folder = new FolderNode("Desktops");
        folder.getChildren().add(rdp);
        SessionExport export = new SessionExport();
        export.folder = folder;
        Path target = dir.resolve("export.json");
        SessionExportService.writePlain(target, export);
        String json = java.nio.file.Files.readString(target);
        assertFalse(json.contains("SmartCard") || json.contains("Yubico"), json);
        RdpSessionConfig back = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertFalse(back.isUseSmartCards());
        assertTrue(back.getSmartCardReaders().isEmpty());

        java.nio.file.Files.writeString(target, json.replace("\"host\" : \"desktop\"",
                "\"host\" : \"desktop\", \"useSmartCards\" : true, \"smartCardReaders\" : [ \"Any Reader\" ]"));
        RdpSessionConfig crafted = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertFalse(crafted.isUseSmartCards(), "an import never arrives able to reach a card");
        assertTrue(crafted.getSmartCardReaders().isEmpty());
    }

    @Test
    void sharedFoldersNeverLeaveInAnExportNorArriveInAnImport() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("desktop");
        rdp.setRedirectClipboard(true);
        rdp.setDriveShares(java.util.List.of(new RdpDriveShare("/home/me/Documents", "Documents", false)));
        FolderNode folder = new FolderNode("Desktops");
        folder.getChildren().add(rdp);
        SessionExport export = new SessionExport();
        export.folder = folder;

        Path target = dir.resolve("export.json");
        SessionExportService.writePlain(target, export);
        String json = java.nio.file.Files.readString(target);
        assertFalse(json.contains("driveShares"), json);
        assertFalse(json.contains("/home/me/Documents"));
        assertEquals(1, rdp.getDriveShares().size(), "the live session keeps its share");
        RdpSessionConfig back = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertTrue(back.isRedirectClipboard(), "other settings travel as before");

        // A crafted import that does carry shares gets none of them.
        java.nio.file.Files.writeString(target, json.replace("\"host\" : \"desktop\"",
                "\"host\" : \"desktop\", \"driveShares\" : [ { \"path\" : \"/\", \"name\" : \"root\", \"readOnly\" : false } ]"));
        assertTrue(java.nio.file.Files.readString(target).contains("driveShares"));
        RdpSessionConfig crafted = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertTrue(crafted.getDriveShares().isEmpty());
    }

    @Test
    void playSoundTravelsInAnExport() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("desktop");
        rdp.setPlaySound(true);
        FolderNode folder = new FolderNode("Desktops");
        folder.getChildren().add(rdp);
        SessionExport export = new SessionExport();
        export.folder = folder;

        Path target = dir.resolve("sound.json");
        SessionExportService.writePlain(target, export);
        RdpSessionConfig back = (RdpSessionConfig) SessionExportService.readPlain(target.toFile()).folder.getChildren().get(0);
        assertTrue(back.isPlaySound());
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

    // ---- node types from a newer jterm ----

    private static UnknownSessionNode futureNode(String name) {
        com.fasterxml.jackson.databind.node.ObjectNode json =
                com.katmoda.jterm.config.JsonStore.mapper().createObjectNode();
        json.put("type", "vnc");
        json.put("id", "future-id");
        json.put("name", name);
        json.putObject("secret").put("host", "vnc.example.com");
        return new UnknownSessionNode(json);
    }

    private static int countUnknown(FolderNode folder) {
        int n = 0;
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof UnknownSessionNode) {
                n++;
            } else if (child instanceof FolderNode sub) {
                n += countUnknown(sub);
            }
        }
        return n;
    }

    @Test
    void unknownNodesNeverLeaveInAnExport() throws Exception {
        SessionExport export = sampleExport();
        FolderNode sub = new FolderNode("Later");
        sub.getChildren().add(futureNode("nested"));
        export.folder.getChildren().add(futureNode("top"));
        export.folder.getChildren().add(sub);

        Path plain = dir.resolve("plain.json");
        SessionExportService.writePlain(plain, export);
        String json = java.nio.file.Files.readString(plain);
        assertFalse(json.contains("vnc"), json);
        assertEquals(2, countUnknown(export.folder), "the live tree keeps its placeholders");
        SessionExport read = SessionExportService.readPlain(plain.toFile());
        assertEquals(2, read.folder.getChildren().size(), "the SSH session and the emptied folder");
        assertTrue(((FolderNode) read.folder.getChildren().get(1)).getChildren().isEmpty());

        Path sealed = dir.resolve("sealed.json");
        export.credentials.put(((SshSessionConfig) export.folder.getChildren().get(0)).getId(), "s3cret");
        SessionExportService.writeEncrypted(sealed, export, "hunter2".toCharArray());
        SessionExport opened = SessionExportService.openEnvelope(
                SessionExportService.readEnvelope(sealed.toFile()), "hunter2".toCharArray());
        assertEquals(0, countUnknown(opened.folder));
        assertEquals("s3cret", opened.credentials.values().iterator().next());
        assertEquals(2, countUnknown(export.folder));
    }

    /** An import is untrusted: an unknown blob must not be smuggled into sessions.json. */
    @Test
    void unknownNodesInAnImportAreDropped() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.katmoda.jterm.config.JsonStore.mapper();
        Path target = dir.resolve("crafted.json");
        SessionExportService.writePlain(target, sampleExport());
        com.fasterxml.jackson.databind.node.ObjectNode tree =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(target.toFile());
        com.fasterxml.jackson.databind.node.ArrayNode children =
                (com.fasterxml.jackson.databind.node.ArrayNode) tree.get("folder").get("children");
        children.add(mapper.valueToTree(futureNode("smuggled")));
        com.fasterxml.jackson.databind.node.ObjectNode sub = children.addObject();
        sub.put("type", "folder").put("name", "inner").putArray("children")
                .add(mapper.valueToTree(futureNode("deeper")));
        byte[] crafted = mapper.writeValueAsBytes(tree);
        java.nio.file.Files.write(target, crafted);
        assertTrue(java.nio.file.Files.readString(target).contains("smuggled"));

        SessionExport plain = SessionExportService.readPlain(target.toFile());
        assertEquals(0, countUnknown(plain.folder));
        assertEquals(2, plain.folder.getChildren().size(), "the SSH session and the inner folder");

        EncryptedSessionExport envelope = new EncryptedSessionExport(
                com.katmoda.jterm.security.PassphraseBox.seal("hunter2".toCharArray(), crafted));
        SessionExport opened = SessionExportService.openEnvelope(envelope, "hunter2".toCharArray());
        assertEquals(0, countUnknown(opened.folder));
        assertEquals(2, opened.folder.getChildren().size());
    }
}
