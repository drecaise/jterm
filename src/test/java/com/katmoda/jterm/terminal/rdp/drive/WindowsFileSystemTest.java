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
package com.katmoda.jterm.terminal.rdp.drive;

import com.katmoda.jterm.terminal.rdp.files.FileSnapshot;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileList;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileTransfer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shared-folder and file-copy code promises on Windows, where the other tests can't reach:
 * they make their links with {@code createSymbolicLink}, which needs a privilege there, and a
 * Windows link is usually a <em>junction</em> anyway, which any user can create and which Java
 * reports as "other", not as a symbolic link. Runs only on Windows; nothing here needs a privilege.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsFileSystemTest {

    @TempDir
    Path tmp;
    private Path share;
    private Path outside;

    /** Removed by hand afterwards: JUnit's own clean-up of the temp folder trips over a junction. */
    private final List<Path> junctions = new ArrayList<>();

    @AfterEach
    void removeJunctions() throws IOException {
        for (Path j : junctions) {
            Files.deleteIfExists(j); // deletes the junction itself, never what it points at
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        share = Files.createDirectory(tmp.resolve("share"));
        outside = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.writeString(share.resolve("a.txt"), "hello");
        junction(share.resolve("escape"), outside);
    }

    /** {@code mklink /J}: a directory junction, the link an unprivileged Windows user can make. */
    private void junction(Path link, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0, "mklink /J failed");
        // The premise of every test below: the junction really leads somewhere.
        assertTrue(Files.isDirectory(link), "the junction does not resolve");
        junctions.add(link);
    }

    @Test
    void aJunctionIsNeitherALinkNorADirectoryToJava() throws Exception {
        // Why DriveShare tests isOther() as well as isSymbolicLink(): this is the fact it rests on.
        BasicFileAttributes a = DriveShare.stat(share.resolve("escape"));
        assertNotNull(a);
        assertTrue(a.isSymbolicLink() || a.isOther(), "a junction must read as a link or as other");
    }

    @Test
    void aShareNeverResolvesThroughAJunction() throws IOException {
        DriveShare s = DriveShare.open(1, "share", share, false, List.of());

        DriveException direct = assertThrows(DriveException.class, () -> s.resolve(List.of("escape")));
        assertEquals(NtStatus.ACCESS_DENIED, direct.status);
        DriveException through = assertThrows(DriveException.class,
                () -> s.resolve(List.of("escape", "secret.txt")));
        assertEquals(NtStatus.ACCESS_DENIED, through.status);
        // A new file beneath it can't be created either: the junction is an intermediate component.
        DriveException create = assertThrows(DriveException.class,
                () -> s.resolve(List.of("escape", "planted.txt")));
        assertEquals(NtStatus.ACCESS_DENIED, create.status);
    }

    @Test
    void aJunctionIsNotListed() throws Exception {
        DriveShare s = DriveShare.open(1, "share", share, false, List.of());
        Path j = share.resolve("escape");

        assertFalse(s.listable(j, DriveShare.stat(j)));
        assertTrue(s.listable(share.resolve("a.txt"), DriveShare.stat(share.resolve("a.txt"))));
    }

    @Test
    void aProtectedFolderIsRefusedUnderAnotherCase() throws IOException {
        Path home = Files.createDirectory(tmp.resolve("home"));
        Path ssh = Files.createDirectory(home.resolve(".ssh"));
        Files.writeString(ssh.resolve("id_ed25519"), "key");
        DriveShare s = DriveShare.open(1, "home", home, true, List.of(ssh));

        for (String name : List.of(".ssh", ".SSH", ".Ssh")) {
            DriveException e = assertThrows(DriveException.class,
                    () -> s.resolve(List.of(name, "id_ed25519")), name);
            assertEquals(NtStatus.ACCESS_DENIED, e.status, name);
        }
        assertThrows(IOException.class, () -> DriveShare.open(2, "ssh", tmp.resolve("HOME").resolve(".SSH"),
                true, List.of(ssh)), "a protected folder can't be shared under another spelling");
    }

    @Test
    void anAlternateDataStreamCannotBeNamed() {
        assertThrows(DriveException.class, () -> DrivePath.parse("\\a.txt:hidden"));
        assertThrows(DriveException.class, () -> DrivePath.parse("\\a.txt::$DATA"));
    }

    @Test
    void aLocalCopyLeavesJunctionsOut() throws Exception {
        FileSnapshot snap = FileSnapshot.build(List.of(share), List.of());

        List<String> names = snap.entries().stream().map(FileSnapshot.Entry::name).toList();
        assertEquals(List.of("share", "share\\a.txt"), names);
        assertEquals(1, snap.summary().skipped());
    }

    @Test
    void savingRemoteFilesNeverWritesThroughAJunction() throws Exception {
        Path dest = Files.createDirectory(tmp.resolve("dest"));
        Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
        junction(dest.resolve("docs"), elsewhere);
        byte[] content = "pwned".getBytes(StandardCharsets.US_ASCII);

        RemoteFileTransfer.Result r = new RemoteFileTransfer(dest, List.of(), reader(content), QUIET,
                OriginMark.NONE).run(RemoteFileList.parse(descriptors(
                        new String[]{"docs", "docs\\x.txt"}, new boolean[]{true, false}, content.length)));

        assertEquals(2, r.failed().size(), r.failed().toString());
        assertEquals(0, r.written());
        try (var s = Files.list(elsewhere)) {
            assertEquals(0, s.count(), "nothing may arrive on the far side of the junction");
        }
    }

    @Test
    void aSavedFileCarriesTheMarkOfTheWeb() throws Exception {
        Path dest = Files.createDirectory(tmp.resolve("dest"));
        byte[] content = "MZ".getBytes(StandardCharsets.US_ASCII);

        RemoteFileTransfer.Result r = new RemoteFileTransfer(dest, List.of(), reader(content), QUIET,
                OriginMark.forThisPlatform()).run(RemoteFileList.parse(descriptors(
                        new String[]{"tool.exe"}, new boolean[]{false}, content.length)));

        assertEquals(1, r.written(), r.failed().toString());
        // Needs NTFS (the temp folder's volume); a volume without streams goes unmarked by design.
        // java.io: a Path can't name a stream (see OriginMark).
        String zone;
        try (var in = new java.io.FileInputStream(dest.resolve("tool.exe") + ":Zone.Identifier")) {
            zone = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        assertTrue(zone.contains("ZoneId=3"), zone);
    }

    // ------------------------------------------------------------------------------- helpers

    private static final RemoteFileTransfer.Listener QUIET = new RemoteFileTransfer.Listener() {
        @Override
        public RemoteFileTransfer.Decision exists(Path target) {
            return RemoteFileTransfer.Decision.SKIP;
        }

        @Override
        public void progress(String name, long done, long total) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /** A server whose every file is {@code content}. */
    private static RemoteFileTransfer.Reader reader(byte[] content) {
        return (index, flags, position, cb) -> {
            if (flags == 1) { // FILECONTENTS_SIZE
                return CompletableFuture.completedFuture(
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(content.length).array());
            }
            int from = (int) Math.min(position, content.length);
            int to = Math.min(content.length, from + cb);
            return CompletableFuture.completedFuture(java.util.Arrays.copyOfRange(content, from, to));
        };
    }

    /** A FileGroupDescriptorW list: u32 count, then 592-byte entries (MS-RDPECLIP 2.2.5.2.3). */
    private static byte[] descriptors(String[] names, boolean[] directory, long fileSize) {
        ByteBuffer b = ByteBuffer.allocate(4 + names.length * 592).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(names.length);
        for (int i = 0; i < names.length; i++) {
            int base = 4 + i * 592;
            b.putInt(base, 0x4 | 0x40);                       // FD_ATTRIBUTES | FD_FILESIZE
            b.putInt(base + 36, directory[i] ? 0x10 : 0x80);  // FILE_ATTRIBUTE_DIRECTORY / _NORMAL
            b.putInt(base + 64, (int) (fileSize >>> 32));
            b.putInt(base + 68, (int) fileSize);
            byte[] name = names[i].getBytes(StandardCharsets.UTF_16LE);
            b.put(base + 72, name, 0, name.length);
        }
        return b.array();
    }
}
