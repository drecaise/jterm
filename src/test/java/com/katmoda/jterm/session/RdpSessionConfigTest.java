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
}
