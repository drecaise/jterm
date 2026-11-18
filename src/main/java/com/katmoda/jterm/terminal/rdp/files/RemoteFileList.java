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

import com.katmoda.jterm.terminal.rdp.WindowsNames;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The files the remote desktop copied, as described by its "FileGroupDescriptorW" list (a u32
 * count and 592-byte {@code FILEDESCRIPTORW} entries, MS-RDPECLIP 2.2.5.2.3). Everything in it is
 * the server's word and is treated as hostile. Pure and Swing-free.
 *
 * <p>A name is read inside its own 520-byte field, up to the first NUL; a name that fills the
 * field without one is refused rather than trusted. It is split on {@code \}, and every component
 * must be a name Windows itself would accept ({@link WindowsNames}): no {@code ..}, no {@code :},
 * no separator or control character. So an entry can only ever name a path beneath the folder the
 * user picks. An entry that fails is left out (and counted); entries are never renamed into
 * something else. Two entries naming the same path, ignoring case, keep only the first.</p>
 *
 * <p>Sizes are the server's claim too: the transfer asks for each file's size separately and reads
 * the smaller of the two.</p>
 */
public final class RemoteFileList {

    public static final int MAX_ENTRIES = FileSnapshot.MAX_ENTRIES;

    /** One entry the server described. {@code index} is its position in the server's list. */
    public record Entry(int index, List<String> components, boolean directory, long size, boolean sizeKnown,
                        FileTime modified) {

        /** {@code folder\file.txt}, for messages. */
        public String name() {
            return String.join("\\", components);
        }
    }

    /** Thrown for a list that is malformed as a whole (truncated, an absurd count). */
    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    private final List<Entry> entries;
    private final int leftOut;

    private RemoteFileList(List<Entry> entries, int leftOut) {
        this.entries = Collections.unmodifiableList(entries);
        this.leftOut = leftOut;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Entries refused for their names (or repeated). */
    public int leftOut() {
        return leftOut;
    }

    public int files() {
        return (int) entries.stream().filter(e -> !e.directory()).count();
    }

    public int folders() {
        return (int) entries.stream().filter(Entry::directory).count();
    }

    /** The total the server claims, of files whose size it gave. */
    public long bytes() {
        long total = 0;
        for (Entry e : entries) {
            if (!e.directory() && e.sizeKnown()) {
                total += e.size();
            }
        }
        return total;
    }

    public static RemoteFileList parse(byte[] data) throws FormatException {
        if (data == null || data.length < 4) {
            throw new FormatException("the file list is empty");
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(b.getInt());
        if (count == 0 || count > MAX_ENTRIES) {
            throw new FormatException(count == 0 ? "the file list is empty"
                    : "the remote desktop copied more than " + String.format("%,d", MAX_ENTRIES) + " files");
        }
        if (data.length - 4L < count * FileSnapshot.DESCRIPTOR_SIZE) {
            throw new FormatException("the file list is truncated");
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int leftOut = 0;
        for (int i = 0; i < count; i++) {
            int base = 4 + i * FileSnapshot.DESCRIPTOR_SIZE;
            int flags = b.getInt(base);
            int attributes = b.getInt(base + 36);
            long fileTime = b.getLong(base + 56);
            long size = (Integer.toUnsignedLong(b.getInt(base + 64)) << 32) | Integer.toUnsignedLong(b.getInt(base + 68));
            List<String> components = name(data, base + 72);
            if (components == null || !seen.add(String.join("\\", components).toLowerCase(Locale.ROOT))) {
                leftOut++;
                continue;
            }
            boolean directory = (flags & FileSnapshot.FD_ATTRIBUTES) != 0
                    && (attributes & FileSnapshot.FILE_ATTRIBUTE_DIRECTORY) != 0;
            boolean sizeKnown = !directory && (flags & FileSnapshot.FD_FILESIZE) != 0 && size >= 0;
            FileTime modified = (flags & FileSnapshot.FD_WRITESTIME) != 0 ? fromFileTime(fileTime) : null;
            out.add(new Entry(i, components, directory, sizeKnown ? size : 0, sizeKnown, modified));
        }
        if (out.isEmpty()) {
            throw new FormatException("none of the copied names can be used on this computer");
        }
        return new RemoteFileList(out, leftOut);
    }

    /** The components of the name at {@code offset}, or null when it is unterminated or unusable. */
    private static List<String> name(byte[] data, int offset) {
        int units = 0;
        while (units < 260 && (data[offset + 2 * units] != 0 || data[offset + 2 * units + 1] != 0)) {
            units++;
        }
        if (units == 260 || units == 0) {
            return null; // fills the field with no terminator, or empty
        }
        String name = new String(data, offset, units * 2, StandardCharsets.UTF_16LE);
        List<String> parts = Arrays.asList(name.split("\\\\", -1));
        for (String part : parts) {
            if (!WindowsNames.valid(part)) {
                return null; // also refuses "", ".", "..", "C:", "/x"
            }
        }
        return List.copyOf(parts);
    }

    private static FileTime fromFileTime(long ft) {
        long micros = (ft - 116_444_736_000_000_000L) / 10;
        if (ft <= 0 || micros < 0) {
            return null; // before 1970 or nonsense: leave the file's time as written
        }
        return FileTime.from(micros, TimeUnit.MICROSECONDS);
    }
}
