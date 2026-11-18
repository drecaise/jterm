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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpHelperLocatorTest {

    private static final Map<String, String> WINDOWS = Map.of("os.name", "Windows 11");
    private static final Map<String, String> LINUX = Map.of("os.name", "Linux");

    private static Map<String, String> with(Map<String, String> base, String k, String v) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>(base);
        m.put(k, v);
        return m;
    }

    @Test
    void anExplicitPathWins(@TempDir Path dir) throws Exception {
        Path exe = Files.createFile(dir.resolve("my-helper.exe"));
        RdpHelperLocator.Found found = assertInstanceOf(RdpHelperLocator.Found.class,
                RdpHelperLocator.locate(with(WINDOWS, RdpHelperLocator.PROPERTY, exe.toString()), Map.of()));
        assertEquals(exe.toAbsolutePath(), found.executable());
    }

    @Test
    void anExplicitPathThatDoesNotExistIsReported(@TempDir Path dir) {
        RdpHelperLocator.NotFound nf = assertInstanceOf(RdpHelperLocator.NotFound.class,
                RdpHelperLocator.locate(with(WINDOWS, RdpHelperLocator.PROPERTY,
                        dir.resolve("missing.exe").toString()), Map.of()));
        assertTrue(nf.reason().contains("does not exist"));
    }

    @Test
    void findsTheHelperInAJpackageWindowsInstall(@TempDir Path install) throws Exception {
        Path rdp = Files.createDirectories(install.resolve("app").resolve("rdp"));
        Path exe = Files.createFile(rdp.resolve("jterm-rdp-helper.exe"));
        RdpHelperLocator.Found found = assertInstanceOf(RdpHelperLocator.Found.class,
                RdpHelperLocator.locate(with(WINDOWS, "jpackage.app-path",
                        install.resolve("jterm.exe").toString()), Map.of()));
        assertEquals(exe, found.executable());
    }

    @Test
    void findsTheHelperInAJpackageLinuxInstall(@TempDir Path install) throws Exception {
        Path rdp = Files.createDirectories(install.resolve("lib").resolve("app").resolve("rdp"));
        Path exe = Files.createFile(rdp.resolve("jterm-rdp-helper"));
        RdpHelperLocator.Found found = assertInstanceOf(RdpHelperLocator.Found.class,
                RdpHelperLocator.locate(with(LINUX, "jpackage.app-path",
                        install.resolve("bin").resolve("jterm").toString()), Map.of()));
        assertEquals(exe, found.executable());
    }

    @Test
    void neverLooksOnThePathOrInTheWorkingDirectory(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("jterm-rdp-helper.exe"));
        RdpHelperLocator.NotFound nf = assertInstanceOf(RdpHelperLocator.NotFound.class,
                RdpHelperLocator.locate(with(WINDOWS, "user.dir", dir.toString()),
                        Map.of("PATH", dir.toString())));
        assertTrue(nf.reason().contains(RdpHelperLocator.PROPERTY), "tells a developer how to point at a helper");
    }
}
