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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileListTest {

    @TempDir
    Path dir;

    /** A packed list of hand-made entries: {name, directory?, size}. */
    static byte[] list(Object[]... entries) {
        ByteBuffer b = ByteBuffer.allocate(4 + entries.length * FileSnapshot.DESCRIPTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(entries.length);
        for (Object[] e : entries) {
            int start = b.position();
            boolean folder = (Boolean) e[1];
            b.putInt(FileSnapshot.FD_ATTRIBUTES | FileSnapshot.FD_FILESIZE);
            b.position(start + 36);
            b.putInt(folder ? FileSnapshot.FILE_ATTRIBUTE_DIRECTORY : FileSnapshot.FILE_ATTRIBUTE_NORMAL);
            b.position(start + 64);
            long size = ((Number) e[2]).longValue();
            b.putInt((int) (size >>> 32)).putInt((int) size);
            byte[] name = ((String) e[0]).getBytes(StandardCharsets.UTF_16LE);
            b.put(name, 0, Math.min(name.length, 520));
            b.position(start + FileSnapshot.DESCRIPTOR_SIZE);
        }
        return b.array();
    }

    @Test
    void readsWhatJtermItselfSendsTheOtherWay() throws Exception {
        Path f = Files.write(dir.resolve("data.bin"), new byte[300]);
        Files.setLastModifiedTime(f, FileTime.from(Instant.parse("2026-11-10T12:00:00Z")));
        Files.createDirectories(dir.resolve("sub/inner"));
        Files.writeString(dir.resolve("sub/inner/x.txt"), "x");

        RemoteFileList l = RemoteFileList.parse(
                FileSnapshot.build(List.of(f, dir.resolve("sub")), List.of()).descriptors());

        assertEquals(List.of("data.bin", "sub", "sub\\inner", "sub\\inner\\x.txt"),
                l.entries().stream().map(RemoteFileList.Entry::name).toList());
        assertEquals(List.of("sub", "inner", "x.txt"), l.entries().get(3).components());
        assertEquals(2, l.files());
        assertEquals(2, l.folders());
        assertEquals(301, l.bytes());
        assertEquals(FileTime.from(Instant.parse("2026-11-10T12:00:00Z")), l.entries().get(0).modified());
        assertTrue(l.entries().get(1).directory());
        assertEquals(0, l.leftOut());
    }

    @Test
    void hostileNamesAreLeftOutNotRenamed() throws Exception {
        RemoteFileList l = RemoteFileList.parse(list(
                new Object[]{"ok.txt", false, 1},
                new Object[]{"..\\..\\.bashrc", false, 1},
                new Object[]{"a\\..\\..\\x", false, 1},
                new Object[]{"\\absolute.txt", false, 1},
                new Object[]{"C:\\Windows\\x.dll", false, 1},
                new Object[]{"file.txt:stream", false, 1},
                new Object[]{"dir/slash.txt", false, 1},
                new Object[]{"trailing. ", false, 1},
                new Object[]{"CON", false, 1},
                new Object[]{"a\\\\b", false, 1},
                new Object[]{"tab\there", false, 1},
                new Object[]{"OK.TXT", false, 1})); // a repeat, ignoring case

        assertEquals(List.of("ok.txt"), l.entries().stream().map(RemoteFileList.Entry::name).toList());
        assertEquals(11, l.leftOut());
        assertEquals(0, l.entries().get(0).index());
    }

    @Test
    void aNameWithoutTerminatorIsRefusedNotReadPastItsField() throws Exception {
        byte[] data = list(new Object[]{"x".repeat(260), false, 1}, new Object[]{"next.txt", false, 1});
        RemoteFileList l = RemoteFileList.parse(data);
        assertEquals(List.of("next.txt"), l.entries().stream().map(RemoteFileList.Entry::name).toList());
        assertEquals(1, l.entries().get(0).index(), "the index is the server's, so reads still name the right file");
    }

    @Test
    void malformedListsAreRefusedWhole() {
        assertThrows(RemoteFileList.FormatException.class, () -> RemoteFileList.parse(new byte[3]));
        assertThrows(RemoteFileList.FormatException.class, () -> RemoteFileList.parse(new byte[4]), "zero entries");
        byte[] truncated = list(new Object[]{"a", false, 1});
        assertThrows(RemoteFileList.FormatException.class,
                () -> RemoteFileList.parse(java.util.Arrays.copyOf(truncated, truncated.length - 1)));
        byte[] huge = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array();
        assertThrows(RemoteFileList.FormatException.class, () -> RemoteFileList.parse(huge),
                "a count of 4 billion is refused before anything is allocated");
        assertThrows(RemoteFileList.FormatException.class,
                () -> RemoteFileList.parse(list(new Object[]{"..", false, 1})), "nothing usable");
    }

    @Test
    void anImpossibleSizeIsTreatedAsUnknown() throws Exception {
        RemoteFileList l = RemoteFileList.parse(list(new Object[]{"a", false, -1L}));
        assertFalse(l.entries().get(0).sizeKnown());
        assertEquals(0, l.bytes());
        assertNull(l.entries().get(0).modified(), "no write time was given");
    }

    @Test
    void randomBytesNeverThrowAnythingButFormatException() {
        Random r = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            byte[] data = new byte[r.nextInt(3 * FileSnapshot.DESCRIPTOR_SIZE)];
            r.nextBytes(data);
            if (data.length >= 4 && r.nextBoolean()) {
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 1 + r.nextInt(3));
            }
            try {
                RemoteFileList l = RemoteFileList.parse(data);
                for (RemoteFileList.Entry e : l.entries()) {
                    assertFalse(e.components().contains(".."));
                }
            } catch (RemoteFileList.FormatException expected) {
                // fine
            }
        }
    }
}
