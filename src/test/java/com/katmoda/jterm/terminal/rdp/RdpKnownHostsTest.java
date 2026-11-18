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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpKnownHostsTest {

    private static final String FP = "77:18:EF:2A";

    @Test
    void aPinSurvivesAReloadAndFeedsConnect(@TempDir Path dir) {
        Path file = dir.resolve("rdp_known_hosts.json");
        RdpKnownHosts store = new RdpKnownHosts(file);
        assertNull(store.pinFor("orion", 3389));
        assertEquals("", store.pinsForConnect("orion", 3389));

        assertTrue(store.pin("orion", 3389, FP));

        RdpKnownHosts reloaded = new RdpKnownHosts(file);
        assertEquals("77:18:ef:2a", reloaded.pinFor("orion", 3389), "stored lower case, like FreeRDP prints it");
        assertEquals("sha256:77:18:ef:2a", reloaded.pinsForConnect("orion", 3389));
    }

    @Test
    void pinsAreKeyedByHostAndPort(@TempDir Path dir) {
        RdpKnownHosts store = new RdpKnownHosts(dir.resolve("k.json"));
        store.pin("Orion.Katmoda.LAN", 3389, FP);
        assertEquals("77:18:ef:2a", store.pinFor("orion.katmoda.lan", 3389), "host names are case-insensitive");
        assertNull(store.pinFor("orion.katmoda.lan", 3390), "another port is another server");
    }

    @Test
    void anUnreadableStoreIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("k.json");
        Files.writeString(file, "{ not json");
        RdpKnownHosts store = new RdpKnownHosts(file);
        assertFalse(store.pin("orion", 3389, FP), "refuses to replace a file it couldn't read");
        assertFalse(Files.exists(file) && Files.readString(file).contains("orion"));
    }
}
