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
import java.net.URI;
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

        assertTrue(OpenH264.isCurrent(dir, new OpenH264.Asset("x", sha256(packed))));
        assertArrayEquals(lib, Files.readAllBytes(dir.resolve("libopenh264.so.8")));
        try (var left = Files.list(dir)) {
            assertEquals(2, left.count(), "the library and its stamp, no temp file left behind");
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
        assertFalse(Files.exists(dir.resolve(OpenH264.LIBRARY_FILE)));
    }

    @Test
    void somethingThatIsNotASharedLibraryIsRefusedEvenWithAMatchingHash(@TempDir Path tmp) throws Exception {
        byte[] packed = bzip2("#!/bin/sh\necho owned\n".getBytes());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        Path dir = tmp.resolve("codecs");

        assertThrows(IOException.class, () ->
                OpenH264.install(dir, new OpenH264.Asset("x", sha256(packed)), src.toUri()));
        assertFalse(Files.exists(dir.resolve(OpenH264.LIBRARY_FILE)));
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
        try (var left = Files.list(dir)) {
            assertEquals(0, left.count(), "the stamp goes with the library");
        }
        OpenH264.remove(dir); // removing what is not there is fine
    }

    @Test
    void theHelperGetsTheDirectoryOnlyWhenEnabledAndCurrent(@TempDir Path tmp) throws Exception {
        byte[] packed = bzip2(fakeLibrary());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        Path dir = tmp.resolve("codecs");
        OpenH264.Asset asset = new OpenH264.Asset("x", sha256(packed));

        assertNull(OpenH264.libraryPath(true, dir, asset), "enabled but not downloaded");
        OpenH264.install(dir, asset, src.toUri());
        assertNull(OpenH264.libraryPath(false, dir, asset), "downloaded but switched off");
        assertEquals(dir.resolve(OpenH264.LIBRARY_FILE).toAbsolutePath(), OpenH264.libraryPath(true, dir, asset));
        assertNull(OpenH264.libraryPath(true, dir, null), "no build for this machine");
    }

    @Test
    void aLibraryFromAnOlderPinIsNotCurrentAndIsNotLoaded(@TempDir Path tmp) throws Exception {
        byte[] oldPacked = bzip2(fakeLibrary());
        Path src = Files.write(tmp.resolve("old.bz2"), oldPacked);
        Path dir = tmp.resolve("codecs");
        OpenH264.Asset oldPin = new OpenH264.Asset("x", sha256(oldPacked));
        OpenH264.install(dir, oldPin, src.toUri());
        assertTrue(OpenH264.isCurrent(dir, oldPin));

        // jterm is updated: same file name, new pin. The old bytes must not be handed to the helper.
        byte[] newLib = fakeLibrary();
        newLib[500] = 42;
        byte[] newPacked = bzip2(newLib);
        OpenH264.Asset newPin = new OpenH264.Asset("x", sha256(newPacked));
        assertFalse(OpenH264.isCurrent(dir, newPin));
        assertNull(OpenH264.libraryPath(true, dir, newPin));

        OpenH264.install(dir, newPin, Files.write(tmp.resolve("new.bz2"), newPacked).toUri());
        assertTrue(OpenH264.isCurrent(dir, newPin));
        assertArrayEquals(newLib, Files.readAllBytes(dir.resolve(OpenH264.LIBRARY_FILE)));
    }

    @Test
    void aLibraryWithoutAStampIsNotCurrent(@TempDir Path tmp) throws Exception {
        // What a build from before the stamp existed left behind, or an interrupted install.
        Files.writeString(tmp.resolve(OpenH264.LIBRARY_FILE), "x");
        assertFalse(OpenH264.isCurrent(tmp, new OpenH264.Asset("x", "0".repeat(64))));
    }

    @Test
    void aDownloadCancelledWhileInFlightInstallsNothingEvenIfItCompletes(@TempDir Path tmp) throws Exception {
        // The whole, valid file, with its last byte held back until after the cancel: the download
        // then completes and verifies, and must still write nothing.
        byte[] packed = bzip2(fakeLibrary());
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, packed.length);
            ex.getResponseBody().write(packed, 0, packed.length - 1);
            ex.getResponseBody().flush();
            try {
                cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // test over
            }
            ex.getResponseBody().write(packed, packed.length - 1, 1);
            ex.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x.bz2");
            Path dir = tmp.resolve("codecs");
            OpenH264.Cancel cancel = new OpenH264.Cancel();
            java.util.concurrent.CompletableFuture<Throwable> result =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            OpenH264.install(dir, new OpenH264.Asset("x", sha256(packed)), uri, cancel);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
            Thread.sleep(300);
            cancel.cancel();
            cancelled.countDown();
            Throwable t = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(t instanceof java.io.InterruptedIOException, "got " + t);
            assertFalse(Files.exists(dir.resolve(OpenH264.LIBRARY_FILE)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDownloadCancelledBeforeItStartsInstallsNothing(@TempDir Path tmp) throws Exception {
        byte[] packed = bzip2(fakeLibrary());
        Path src = Files.write(tmp.resolve("src.bz2"), packed);
        OpenH264.Cancel cancel = new OpenH264.Cancel();
        cancel.cancel();
        assertThrows(java.io.InterruptedIOException.class, () ->
                OpenH264.install(tmp.resolve("codecs"), new OpenH264.Asset("x", sha256(packed)), src.toUri(), cancel));
        assertFalse(Files.exists(tmp.resolve("codecs").resolve(OpenH264.LIBRARY_FILE)));
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
