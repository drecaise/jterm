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
package com.katmoda.jterm.terminal.rdp.files;

import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSnapshotTest {

    @TempDir
    Path dir;

    private static RdpProtocol.FileRequest size(int index) {
        return new RdpProtocol.FileRequest(1, index, RdpProtocol.FILECONTENTS_SIZE, 0, 8, false, 0);
    }

    private static RdpProtocol.FileRequest range(int index, long position, int cb) {
        return new RdpProtocol.FileRequest(1, index, RdpProtocol.FILECONTENTS_RANGE, position, cb, false, 0);
    }

    private List<String> names(FileSnapshot s) {
        return s.entries().stream().map(FileSnapshot.Entry::name).toList();
    }

    @Test
    void walksFoldersParentFirstWithRelativeBackslashNames() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("photos/2026"));
        Files.writeString(folder.resolve("b.jpg"), "bb");
        Files.writeString(dir.resolve("photos/a.jpg"), "a");
        Path report = Files.writeString(dir.resolve("report.pdf"), "pdf!");

        FileSnapshot s = FileSnapshot.build(List.of(report, dir.resolve("photos")), List.of());

        assertEquals(List.of("report.pdf", "photos", "photos\\2026", "photos\\2026\\b.jpg", "photos\\a.jpg"), names(s));
        FileSnapshot.Summary sum = s.summary();
        assertEquals(3, sum.files());
        assertEquals(2, sum.folders());
        assertEquals(7, sum.bytes());
        assertEquals(List.of("report.pdf", "photos"), sum.topNames());
        assertEquals(0, sum.skipped());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // creating a symbolic link needs a privilege there
    void linksAreLeftOutAndNeverFollowed() throws Exception {
        Path share = Files.createDirectories(dir.resolve("share"));
        Files.writeString(share.resolve("ok.txt"), "ok");
        Files.createSymbolicLink(share.resolve("etc"), Path.of("/etc"));
        Path topLink = Files.createSymbolicLink(dir.resolve("home-link"), Path.of(System.getProperty("user.home")));

        FileSnapshot s = FileSnapshot.build(List.of(share, topLink), List.of());

        assertEquals(List.of("share", "share\\ok.txt"), names(s));
        assertEquals(2, s.summary().skipped());
    }

    @Test
    void protectedFoldersAreLeftOutWhereverTheyAppear() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path ssh = Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(ssh.resolve("id_ed25519"), "secret");
        Path vault = Files.createDirectories(home.resolve(".config/jterm"));
        Files.writeString(vault.resolve("credentials.json"), "{}");
        Files.writeString(home.resolve("notes.txt"), "n");

        FileSnapshot s = FileSnapshot.build(List.of(home), List.of(ssh, vault, dir.resolve("missing")));

        assertEquals(List.of("home", "home\\.config", "home\\notes.txt"), names(s));
        assertThrows(FileSnapshot.SnapshotException.class,
                () -> FileSnapshot.build(List.of(ssh), List.of(ssh)), "copying only the keys sends nothing");
    }

    @Test
    void refusesRatherThanSendingPartOfACopy() throws Exception {
        Path many = Files.createDirectories(dir.resolve("many"));
        for (int i = 0; i < FileSnapshot.MAX_ENTRIES; i++) { // + the folder itself = one too many
            Files.createFile(many.resolve("f" + i));
        }
        FileSnapshot.SnapshotException tooMany = assertThrows(FileSnapshot.SnapshotException.class,
                () -> FileSnapshot.build(List.of(many), List.of()));
        // Formatted for the user's locale ("10.000" on a Dutch machine), so the test must do the same.
        assertTrue(tooMany.getMessage().contains(String.format("%,d", FileSnapshot.MAX_ENTRIES)),
                tooMany.getMessage());

        Path deep = dir.resolve("deep");
        Path p = deep;
        for (int i = 0; i < FileSnapshot.MAX_DEPTH; i++) {
            p = p.resolve("d");
        }
        Files.createDirectories(p);
        assertThrows(FileSnapshot.SnapshotException.class, () -> FileSnapshot.build(List.of(deep), List.of()));

        Path longNames = Files.createDirectories(dir.resolve("x".repeat(200)).resolve("y".repeat(60)));
        assertThrows(FileSnapshot.SnapshotException.class,
                () -> FileSnapshot.build(List.of(longNames.getParent()), List.of()), "260 characters is one too many");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // such names can't exist there in the first place
    void refusesNamesWindowsCannotHold() throws Exception {
        for (String bad : new String[]{"a:b.txt", "back\\slash", "trailing.", "CON", "nul.txt", "what?"}) {
            Path f = Files.writeString(dir.resolve(bad), "x");
            assertThrows(FileSnapshot.SnapshotException.class, () -> FileSnapshot.build(List.of(f), List.of()), bad);
        }
    }

    @Test
    void descriptorsCarryNamesSizesAndTimesButNoPath() throws Exception {
        Path f = Files.write(dir.resolve("größe.bin"), new byte[300]);
        Instant when = Instant.parse("2026-11-10T12:00:00Z");
        Files.setLastModifiedTime(f, FileTime.from(when));
        Path sub = Files.createDirectories(dir.resolve("sub"));

        byte[] d = FileSnapshot.build(List.of(f, sub), List.of()).descriptors();

        assertEquals(4 + 2 * FileSnapshot.DESCRIPTOR_SIZE, d.length);
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, b.getInt());
        int flags = b.getInt(4);
        assertEquals(FileSnapshot.FD_ATTRIBUTES | FileSnapshot.FD_FILESIZE | FileSnapshot.FD_WRITESTIME
                | FileSnapshot.FD_SHOWPROGRESSUI, flags);
        assertEquals(FileSnapshot.FILE_ATTRIBUTE_NORMAL, b.getInt(4 + 36));
        assertEquals(when.getEpochSecond() * 10_000_000L + 116_444_736_000_000_000L, b.getLong(4 + 56));
        assertEquals(0, b.getInt(4 + 64)); // size high
        assertEquals(300, b.getInt(4 + 68)); // size low
        String name = new String(d, 4 + 72, 520, StandardCharsets.UTF_16LE);
        assertEquals("größe.bin", name.substring(0, name.indexOf('\0')));
        assertEquals(FileSnapshot.FILE_ATTRIBUTE_DIRECTORY, b.getInt(4 + FileSnapshot.DESCRIPTOR_SIZE + 36));

        String all = new String(d, StandardCharsets.UTF_16LE) + new String(d, StandardCharsets.UTF_8);
        assertFalse(all.contains(dir.toString()), "the server learns names, never where they are");
    }

    @Test
    void servesSizesAndRangesOfTheSnapshotOnly() throws Exception {
        byte[] content = new byte[10];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Path f = Files.write(dir.resolve("data.bin"), content);
        Path sub = Files.createDirectories(dir.resolve("sub"));
        FileSnapshot s = FileSnapshot.build(List.of(f, sub), List.of());

        FileSnapshot.Reply sz = s.serve(size(0));
        assertTrue(sz.ok());
        assertEquals(10, ByteBuffer.wrap(sz.data()).order(ByteOrder.LITTLE_ENDIAN).getLong());
        assertArrayEquals(new byte[]{3, 4, 5}, s.serve(range(0, 3, 3)).data());
        assertArrayEquals(new byte[]{8, 9}, s.serve(range(0, 8, 100)).data(), "never past the end");
        FileSnapshot.Reply end = s.serve(range(0, 10, 4));
        assertTrue(end.ok());
        assertEquals(0, end.data().length);

        assertFalse(s.serve(range(1, 0, 4)).ok(), "a folder has no contents");
        assertFalse(s.serve(range(2, 0, 4)).ok(), "only indexes of the list");
        assertFalse(s.serve(range(-1, 0, 4)).ok());
        assertFalse(s.serve(range(0, -1, 4)).ok(), "a position past 2^63");
        assertFalse(s.serve(new RdpProtocol.FileRequest(1, 0, 0, 0, 4, false, 0)).ok(), "neither size nor range");
    }

    @Test
    void capsARangeAndReadsAnUnsignedRequestSize() throws Exception {
        Path f = Files.write(dir.resolve("big.bin"), new byte[FileSnapshot.MAX_RANGE + 10]);
        FileSnapshot s = FileSnapshot.build(List.of(f), List.of());
        assertEquals(FileSnapshot.MAX_RANGE, s.serve(range(0, 0, Integer.MAX_VALUE)).data().length);
        assertEquals(FileSnapshot.MAX_RANGE, s.serve(range(0, 0, 0x8000_0000)).data().length,
                "cbRequested is unsigned: 2 GiB, not a negative count");
    }

    @Test
    void aFileChangedSinceTheCopyIsNotSent() throws Exception {
        Path f = Files.writeString(dir.resolve("draft.txt"), "first");
        Files.setLastModifiedTime(f, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        FileSnapshot s = FileSnapshot.build(List.of(f), List.of());
        assertTrue(s.serve(range(0, 0, 5)).ok());

        Files.writeString(f, "other"); // same size, new time
        assertFalse(s.serve(range(0, 0, 5)).ok());
        assertFalse(s.serve(size(0)).ok());

        Files.delete(f);
        assertFalse(s.serve(range(0, 0, 5)).ok());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileSwappedForALinkIsNotFollowed() throws Exception {
        Path f = Files.writeString(dir.resolve("plain.txt"), "plain");
        FileSnapshot s = FileSnapshot.build(List.of(f), List.of());
        Files.delete(f);
        Files.createSymbolicLink(f, Path.of("/etc/hostname"));
        assertFalse(s.serve(range(0, 0, 5)).ok());
    }

    @Test
    void nothingUsableIsAnError() throws IOException {
        assertThrows(FileSnapshot.SnapshotException.class,
                () -> FileSnapshot.build(List.of(dir.resolve("gone")), List.of()));
        assertThrows(FileSnapshot.SnapshotException.class,
                () -> FileSnapshot.build(List.of(dir.getRoot()), List.of()), "a whole drive is refused");
    }
}
