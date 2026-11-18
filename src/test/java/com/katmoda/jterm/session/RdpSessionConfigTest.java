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
package com.katmoda.jterm.session;

import com.katmoda.jterm.config.JsonStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpSessionConfigTest {

    @Test
    void roundTripsInsideTheSessionTree() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setName("orion");
        rdp.setHost(" orion.katmoda.lan ");
        rdp.setUser("mark");
        rdp.setSavePassword(true);
        rdp.setWidthMode(RdpSessionConfig.WidthMode.FIXED);
        rdp.setWidth(1920);
        rdp.setHeight(1080);
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);

        String json = JsonStore.mapper().writeValueAsString(root);
        assertTrue(json.contains("\"type\" : \"rdp\""), json);

        FolderNode back = JsonStore.mapper().readValue(json, FolderNode.class);
        RdpSessionConfig read = assertInstanceOf(RdpSessionConfig.class, back.getChildren().get(0));
        assertEquals(rdp.getId(), read.getId());
        assertEquals("orion.katmoda.lan", read.getHost(), "trimmed on the way in");
        assertEquals("mark", read.getUser());
        assertTrue(read.isSavePassword());
        assertEquals(RdpSessionConfig.WidthMode.FIXED, read.getWidthMode());
        assertEquals(1920, read.getWidth());
    }

    /**
     * A session written by the 2026-06 prototype branch carries fields this build dropped. Without
     * ignoreUnknown the default mapper would fail — and take the whole sessions.json with it.
     */
    @Test
    void aPrototypeSessionStillLoadsAndKeepsTheTreeAlive() throws Exception {
        String json = """
                { "type" : "folder", "name" : "Sessions", "children" : [
                  { "type" : "rdp", "id" : "abc", "name" : "old", "host" : "orion", "port" : 3389,
                    "user" : "mark", "domain" : "", "passwordAuth" : true, "savePassword" : false,
                    "widthMode" : "FIT_TO_TAB", "scalingMode" : "SMART", "width" : 1280, "height" : 800,
                    "colorDepth" : 0, "securityMode" : "AUTO", "ignoreCertErrors" : true,
                    "redirectClipboard" : false, "redirectDrives" : false, "redirectAudio" : false,
                    "gateway" : "" },
                  { "type" : "ssh", "name" : "still here", "host" : "h" }
                ] }""";
        FolderNode root = JsonStore.mapper().readValue(json, FolderNode.class);
        RdpSessionConfig rdp = assertInstanceOf(RdpSessionConfig.class, root.getChildren().get(0));
        assertEquals("abc", rdp.getId());
        assertEquals("orion", rdp.getHost());
        assertInstanceOf(SshSessionConfig.class, root.getChildren().get(1));
        String rewritten = JsonStore.mapper().writeValueAsString(root);
        assertTrue(!rewritten.contains("ignoreCertErrors"), "the insecure switch is not carried forward");
    }

    @Test
    void nlaIsRequiredUnlessTheSessionAllowsTlsAndTheChoiceIsSavedAndCopied() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        assertFalse(rdp.isAllowTlsWithoutNla(), "NLA required by default");

        rdp.setAllowTlsWithoutNla(true);
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        FolderNode back = JsonStore.mapper().readValue(JsonStore.mapper().writeValueAsString(root),
                FolderNode.class);
        assertTrue(assertInstanceOf(RdpSessionConfig.class, back.getChildren().get(0)).isAllowTlsWithoutNla());
        assertTrue(rdp.copyWithNewId().isAllowTlsWithoutNla(), "a duplicate keeps the choice");
    }

    @Test
    void aSessionSavedBeforeTheSettingExistedRequiresNla() throws Exception {
        // Older files (and the prototype's "securityMode" : "AUTO", which is ignored) have no
        // allowTlsWithoutNla: they get the safe default rather than silently allowing TLS.
        String json = """
                { "type" : "folder", "name" : "Sessions", "children" : [
                  { "type" : "rdp", "id" : "abc", "name" : "old", "host" : "orion",
                    "securityMode" : "AUTO" } ] }""";
        FolderNode root = JsonStore.mapper().readValue(json, FolderNode.class);
        assertFalse(assertInstanceOf(RdpSessionConfig.class, root.getChildren().get(0)).isAllowTlsWithoutNla());
    }

    @Test
    void outOfRangeValuesFallBackToSafeDefaults() {
        RdpSessionConfig cfg = new RdpSessionConfig();
        cfg.setPort(0);
        assertEquals(RdpSessionConfig.DEFAULT_PORT, cfg.getPort());
        cfg.setWidth(50);
        assertEquals(1280, cfg.getWidth(), "below the RDP minimum of 200");
        cfg.setHeight(9000);
        assertEquals(800, cfg.getHeight(), "above the RDP maximum of 8192");
        cfg.setWidthMode(null);
        assertEquals(RdpSessionConfig.WidthMode.FIT_TO_TAB, cfg.getWidthMode());
    }

    @Test
    void hostLabelShowsANonDefaultPortOnly() {
        RdpSessionConfig cfg = new RdpSessionConfig();
        cfg.setHost("orion");
        assertEquals("orion", cfg.hostLabel());
        cfg.setPort(3390);
        assertEquals("orion:3390", cfg.hostLabel());
    }

    @Test
    void useMicrophoneDefaultsOffIsSavedAndCopied() throws Exception {
        assertFalse(new RdpSessionConfig().isUseMicrophone());
        FolderNode old = JsonStore.mapper().readValue(
                "{ \"type\" : \"folder\", \"name\" : \"S\", \"children\" : [ { \"type\" : \"rdp\","
                        + " \"id\" : \"abc\", \"name\" : \"old\", \"host\" : \"orion\" } ] }", FolderNode.class);
        assertFalse(assertInstanceOf(RdpSessionConfig.class, old.getChildren().get(0)).isUseMicrophone(),
                "existing sessions stay without a microphone");

        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("orion");
        rdp.setUseMicrophone(true);
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        FolderNode back = JsonStore.mapper().readValue(JsonStore.mapper().writeValueAsString(root), FolderNode.class);
        assertTrue(assertInstanceOf(RdpSessionConfig.class, back.getChildren().get(0)).isUseMicrophone());
        assertTrue(rdp.copyWithNewId().isUseMicrophone(), "a duplicate keeps the choice");
    }

    @Test
    void smartCardSettingsDefaultOffAreSavedAndCopied() throws Exception {
        RdpSessionConfig fresh = new RdpSessionConfig();
        assertFalse(fresh.isUseSmartCards());
        assertTrue(fresh.getSmartCardReaders().isEmpty());
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setUseSmartCards(true);
        rdp.setSmartCardReaders(java.util.List.of("Reader A", "Reader B"));
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        RdpSessionConfig back = assertInstanceOf(RdpSessionConfig.class, JsonStore.mapper()
                .readValue(JsonStore.mapper().writeValueAsString(root), FolderNode.class).getChildren().get(0));
        assertTrue(back.isUseSmartCards());
        assertEquals(java.util.List.of("Reader A", "Reader B"), back.getSmartCardReaders());
        RdpSessionConfig copy = rdp.copyWithNewId();
        assertTrue(copy.isUseSmartCards());
        assertEquals(java.util.List.of("Reader A", "Reader B"), copy.getSmartCardReaders());
        copy.getSmartCardReaders().clear();
        assertEquals(2, rdp.getSmartCardReaders().size(), "the copy has its own list");
        rdp.setSmartCardReaders(null);
        assertTrue(rdp.getSmartCardReaders().isEmpty());
    }

    @Test
    void printerSettingsDefaultOffAreSavedAndCopied() throws Exception {
        RdpSessionConfig fresh = new RdpSessionConfig();
        assertFalse(fresh.isPrintToJterm());
        assertFalse(fresh.isPrinterIsDefault());
        assertEquals("", fresh.getAutoPrintPrinter());
        FolderNode old = JsonStore.mapper().readValue(
                "{ \"type\" : \"folder\", \"name\" : \"S\", \"children\" : [ { \"type\" : \"rdp\","
                        + " \"id\" : \"abc\", \"name\" : \"old\", \"host\" : \"orion\" } ] }", FolderNode.class);
        RdpSessionConfig oldRdp = assertInstanceOf(RdpSessionConfig.class, old.getChildren().get(0));
        assertFalse(oldRdp.isPrintToJterm(), "existing sessions stay without a printer");
        assertEquals("", oldRdp.getAutoPrintPrinter());

        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("orion");
        rdp.setPrintToJterm(true);
        rdp.setPrinterIsDefault(true);
        rdp.setAutoPrintPrinter("Office laser");
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        RdpSessionConfig back = assertInstanceOf(RdpSessionConfig.class, JsonStore.mapper()
                .readValue(JsonStore.mapper().writeValueAsString(root), FolderNode.class).getChildren().get(0));
        assertTrue(back.isPrintToJterm());
        assertTrue(back.isPrinterIsDefault());
        assertEquals("Office laser", back.getAutoPrintPrinter());
        RdpSessionConfig copy = rdp.copyWithNewId();
        assertTrue(copy.isPrintToJterm() && copy.isPrinterIsDefault());
        assertEquals("Office laser", copy.getAutoPrintPrinter());
        rdp.setAutoPrintPrinter(null);
        assertEquals("", rdp.getAutoPrintPrinter(), "null reads as ask each time");
    }

    @Test
    void playSoundDefaultsOffIsSavedAndCopied() throws Exception {
        assertFalse(new RdpSessionConfig().isPlaySound());
        FolderNode old = JsonStore.mapper().readValue(
                "{ \"type\" : \"folder\", \"name\" : \"S\", \"children\" : [ { \"type\" : \"rdp\","
                        + " \"id\" : \"abc\", \"name\" : \"old\", \"host\" : \"orion\" } ] }", FolderNode.class);
        assertFalse(assertInstanceOf(RdpSessionConfig.class, old.getChildren().get(0)).isPlaySound());

        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setHost("orion");
        rdp.setPlaySound(true);
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        FolderNode back = JsonStore.mapper().readValue(JsonStore.mapper().writeValueAsString(root), FolderNode.class);
        assertTrue(assertInstanceOf(RdpSessionConfig.class, back.getChildren().get(0)).isPlaySound());
        assertTrue(rdp.copyWithNewId().isPlaySound(), "a duplicate keeps the choice");
    }

    @Test
    void aCopyGetsAFreshIdAndNoSavedPassword() {
        RdpSessionConfig cfg = new RdpSessionConfig();
        cfg.setHost("orion");
        cfg.setSavePassword(true);
        RdpSessionConfig copy = cfg.copyWithNewId();
        assertNotEquals(cfg.getId(), copy.getId());
        assertEquals("orion", copy.getHost());
        assertEquals(false, copy.isSavePassword(), "the vault entry belongs to the original id");
    }

    @Test
    void importReassignsRdpIdsAndCarriesTheirPasswords() {
        RdpSessionConfig rdp = new RdpSessionConfig();
        String oldId = rdp.getId();
        FolderNode folder = new FolderNode("imported");
        folder.getChildren().add(rdp);
        Map<String, String> oldCreds = Map.of(oldId, "secret");
        Map<String, String> newCreds = new HashMap<>();

        SessionExportService.reassignIds(folder, oldCreds, newCreds);

        assertNotEquals(oldId, rdp.getId(), "an import must never share an existing session's vault entry");
        assertEquals(Map.of(rdp.getId(), "secret"), newCreds);
    }

    @Test
    void theSshTunnelChoiceIsSavedCopiedAndBlankMeansDirect() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        assertFalse(rdp.isViaSsh(), "direct by default");
        rdp.setSshTunnelSessionId("  ");
        assertFalse(rdp.isViaSsh(), "blank is direct");

        rdp.setSshTunnelSessionId("ssh-1");
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        String json = JsonStore.mapper().writeValueAsString(root);
        assertFalse(json.contains("viaSsh"), "the derived flag is not persisted: " + json);
        RdpSessionConfig read = assertInstanceOf(RdpSessionConfig.class,
                JsonStore.mapper().readValue(json, FolderNode.class).getChildren().get(0));
        assertEquals("ssh-1", read.getSshTunnelSessionId());
        assertTrue(read.isViaSsh());
        assertEquals("ssh-1", rdp.copyWithNewId().getSshTunnelSessionId());
    }

    @Test
    void theGatewaySettingsDefaultSafelyAndAreSavedAndCopied() throws Exception {
        RdpSessionConfig rdp = new RdpSessionConfig();
        assertFalse(rdp.isViaGateway());
        assertEquals(443, rdp.getGatewayPort());
        assertTrue(rdp.isGatewaySameCredentials(), "the usual setup");
        rdp.setGatewayPort(0);
        assertEquals(443, rdp.getGatewayPort(), "out of range falls back");

        rdp.setGatewayHost(" rdg.corp ");
        rdp.setGatewayPort(8443);
        rdp.setGatewaySameCredentials(false);
        rdp.setGatewayUser("gwuser");
        rdp.setGatewayDomain("GW");
        rdp.setSaveGatewayPassword(true);
        assertEquals("rdg.corp:8443", rdp.gatewayLabel());
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(rdp);
        String json = JsonStore.mapper().writeValueAsString(root);
        assertFalse(json.contains("viaGateway"), json);
        RdpSessionConfig read = assertInstanceOf(RdpSessionConfig.class,
                JsonStore.mapper().readValue(json, FolderNode.class).getChildren().get(0));
        assertEquals("rdg.corp", read.getGatewayHost());
        assertEquals(8443, read.getGatewayPort());
        assertFalse(read.isGatewaySameCredentials());
        assertEquals("gwuser", read.getGatewayUser());
        assertEquals("GW", read.getGatewayDomain());
        assertTrue(read.isSaveGatewayPassword());

        RdpSessionConfig copy = rdp.copyWithNewId();
        assertEquals("rdg.corp", copy.getGatewayHost());
        assertEquals("gwuser", copy.getGatewayUser());
        assertFalse(copy.isSaveGatewayPassword(), "a saved password stays with the original");
    }

    @Test
    void importFollowsAnSshSessionImportedAlongsideAndKeepsOneOutsideIt() {
        SshSessionConfig bastion = new SshSessionConfig();
        String bastionOldId = bastion.getId();
        RdpSessionConfig inside = new RdpSessionConfig();
        inside.setSshTunnelSessionId(bastionOldId);
        RdpSessionConfig outside = new RdpSessionConfig();
        outside.setSshTunnelSessionId("an-existing-session");
        FolderNode folder = new FolderNode("imported");
        FolderNode sub = new FolderNode("desktops");
        folder.getChildren().add(bastion);
        folder.getChildren().add(sub);
        sub.getChildren().addAll(java.util.List.of(inside, outside));

        SessionExportService.reassignIds(folder, Map.of(), new HashMap<>());

        assertNotEquals(bastionOldId, bastion.getId());
        assertEquals(bastion.getId(), inside.getSshTunnelSessionId(), "follows the bastion to its new id");
        assertEquals("an-existing-session", outside.getSshTunnelSessionId(), "may name a session the user has");
    }
}
