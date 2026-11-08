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

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenH264Test {

    private static byte[] bzip2(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream z = new BZip2CompressorOutputStream(out)) {
            z.write(data);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] fakeLibrary() {
        byte[] lib = new byte[1000];
        lib[0] = 0x7F; lib[1] = 'E'; lib[2] = 'L'; lib[3] = 'F';
        for (int i = 4; i < lib.length; i++) {
            lib[i] = (byte) i;
        }
        return lib;
    }

    @Test
    void aGoodDownloadIsUnpackedUnderTheNameTheHelperLoads(@TempDir Path tmp) throws Exception {
        byte[] lib = fakeLibrary();
        byte[] packed = bzip2(lib);
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        Path dir = tmp.resolve("codecs");

        OpenH264.install(dir, new OpenH264.Asset("x", sha256(packed)), src.toUri());

        assertTrue(OpenH264.isInstalled(dir));
        assertArrayEquals(lib, Files.readAllBytes(dir.resolve("libopenh264.so.8")));
        try (var left = Files.list(dir)) {
            assertEquals(1, left.count(), "no temp file is left behind");
        }
    }

    @Test
    void aWrongChecksumInstallsNothing(@TempDir Path tmp) throws Exception {
        byte[] packed = bzip2(fakeLibrary());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        Path dir = tmp.resolve("codecs");

        IOException e = assertThrows(IOException.class, () ->
                OpenH264.install(dir, new OpenH264.Asset("x", "0".repeat(64)), src.toUri()));
        assertTrue(e.getMessage().contains("checksum"));
        assertFalse(OpenH264.isInstalled(dir));
    }

    @Test
    void somethingThatIsNotASharedLibraryIsRefusedEvenWithAMatchingHash(@TempDir Path tmp) throws Exception {
        byte[] packed = bzip2("#!/bin/sh\necho owned\n".getBytes());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        Path dir = tmp.resolve("codecs");

        assertThrows(IOException.class, () ->
                OpenH264.install(dir, new OpenH264.Asset("x", sha256(packed)), src.toUri()));
        assertFalse(OpenH264.isInstalled(dir));
    }

    @Test
    void garbageWithAMatchingHashFailsCleanly(@TempDir Path tmp) throws Exception {
        byte[] junk = "not bzip2 at all".getBytes();
        Path src = Files.write(tmp.resolve("src.bz2"), junk);
        assertThrows(IOException.class, () ->
                OpenH264.install(tmp.resolve("codecs"), new OpenH264.Asset("x", sha256(junk)), src.toUri()));
    }

    @Test
    void aReinstallReplacesTheOldFileAndRemoveDeletesIt(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("codecs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("libopenh264.so.8"), "old");
        byte[] packed = bzip2(fakeLibrary());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);

        OpenH264.install(dir, new OpenH264.Asset("x", sha256(packed)), src.toUri());
        assertEquals(1000, Files.size(dir.resolve("libopenh264.so.8")));

        OpenH264.remove(dir);
        assertFalse(OpenH264.isInstalled(dir));
        OpenH264.remove(dir); // removing what is not there is fine
    }

    @Test
    void theHelperGetsTheDirectoryOnlyWhenEnabledAndPresent(@TempDir Path tmp) throws Exception {
        assertNull(OpenH264.libraryDir(true, tmp), "enabled but not downloaded");
        Files.writeString(tmp.resolve("libopenh264.so.8"), "x");
        assertNull(OpenH264.libraryDir(false, tmp), "downloaded but switched off");
        assertEquals(tmp, OpenH264.libraryDir(true, tmp));
    }

    @Test
    void thereIsADownloadOnlyForLinuxOnX86OrArm() {
        assertNotNull(OpenH264.assetFor("Linux", "amd64"));
        assertNotNull(OpenH264.assetFor("Linux", "aarch64"));
        assertNull(OpenH264.assetFor("Linux", "riscv64"));
        assertNull(OpenH264.assetFor("Windows 11", "amd64"), "Windows uses Media Foundation");
        assertNull(OpenH264.assetFor("Mac OS X", "aarch64"), "macOS uses VideoToolbox");
        assertTrue(OpenH264.assetFor("Linux", "amd64").file().endsWith("linux64.8.so.bz2"));
        assertEquals(64, OpenH264.assetFor("Linux", "amd64").sha256().length());
    }
}
