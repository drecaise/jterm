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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Open Folder collects from the session tree. */
class SessionStoreCollectTest {

    private static SshSessionConfig ssh(String name) {
        SshSessionConfig cfg = new SshSessionConfig();
        cfg.setName(name);
        return cfg;
    }

    private static RdpSessionConfig rdp(String name) {
        RdpSessionConfig cfg = new RdpSessionConfig();
        cfg.setName(name);
        return cfg;
    }

    @Test
    void openFolderCollectsSshAndRdpSessionsRecursivelyInTreeOrder() {
        FolderNode root = new FolderNode("root");
        FolderNode sub = new FolderNode("sub");
        root.getChildren().addAll(List.of(ssh("a"), rdp("desktop"), sub, ssh("b")));
        sub.getChildren().addAll(List.of(rdp("nested desktop"), ssh("c")));

        List<String> names = SessionStore.collectOpenableSessions(root).stream()
                .map(SessionNode::getName).toList();
        assertEquals(List.of("a", "desktop", "nested desktop", "c", "b"), names);
        assertEquals(3, SessionStore.collectSshSessions(root).size(), "the SSH-only view is unchanged");
    }

    @Test
    void aFolderOfOnlyRdpSessionsIsOpenable() {
        FolderNode root = new FolderNode("root");
        root.getChildren().add(rdp("desktop"));
        assertEquals(1, SessionStore.collectOpenableSessions(root).size());
        assertTrue(SessionStore.collectSshSessions(root).isEmpty());
    }

    @Test
    void anEmptyTreeHasNothingToOpen() {
        FolderNode root = new FolderNode("root");
        root.getChildren().add(new FolderNode("empty"));
        assertTrue(SessionStore.collectOpenableSessions(root).isEmpty());
    }
}
