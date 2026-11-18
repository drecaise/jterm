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
import com.katmoda.jterm.terminal.rdp.WindowsNames;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the remote desktop may read when it pastes files copied on this computer: the copied entries
 * walked once, recursively, into a fixed list. The server can only ever name an entry of this list
 * (by index), so it can never reach a file the user did not copy. Swing-free.
 *
 * <p><b>Building</b> ({@link #build}) does not follow links: a symbolic link, junction or other
 * reparse point is left out, as is anything that is neither a file nor a directory, and so are
 * jterm's config folder and {@code ~/.ssh} wherever they appear (compared by identity, as for shared
 * folders). Directories come before their contents, which is the order Windows creates them in.
 * Rather than send part of a copy, building fails outright ({@link SnapshotException}) beyond
 * {@link #MAX_ENTRIES} entries or {@link #MAX_DEPTH} levels, or on a name Windows could not hold
 * (a {@code :} in a Linux file name, say, or a path past {@link #MAX_NAME} characters).</p>
 *
 * <p><b>Serving</b> ({@link #serve}) reopens the file each time without following links, and fails
 * when its size or modification time differs from the snapshot: the server gets what the user
 * copied, or nothing.</p>
 */
public final class FileSnapshot {

    public static final int MAX_ENTRIES = 10_000;
    public static final int MAX_DEPTH = 32;
    /** Longest relative name, in UTF-16 units: {@code cFileName} is 260 units with its terminator. */
    public static final int MAX_NAME = 259;
    /** Most bytes one range request is answered with. */
    public static final int MAX_RANGE = 4 * 1024 * 1024;

    /** Size of one {@code FILEDESCRIPTORW} (MS-RDPECLIP 2.2.5.2.3.1). */
    static final int DESCRIPTOR_SIZE = 592;
    private static final int NAME_BYTES = 520;

    static final int FD_ATTRIBUTES = 0x00000004;
    static final int FD_WRITESTIME = 0x00000020;
    static final int FD_FILESIZE = 0x00000040;
    static final int FD_SHOWPROGRESSUI = 0x00004000;
    static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    static final int FILE_ATTRIBUTE_NORMAL = 0x80;

    /** 100 ns intervals between 1601-01-01 (FILETIME's epoch) and 1970-01-01. */
    private static final long FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L;

    /** One entry: its name relative to the copy ({@code folder\file.txt}) and what it was then. */
    public record Entry(String name, Path path, boolean directory, long size, FileTime modified) {
    }

    /** What the user is asked about: counts, total size, and the copied entries' own names. */
    public record Summary(int files, int folders, long bytes, List<String> topNames, int skipped) {
    }

    /** Building failed; the message says why, in words for the user. */
    public static final class SnapshotException extends Exception {
        public SnapshotException(String message) {
            super(message);
        }
    }

    /** The answer to one file-contents request. */
    public record Reply(boolean ok, byte[] data) {
        static final Reply FAIL = new Reply(false, null);
    }

    private final List<Entry> entries;
    private final List<String> topNames;
    private final int skipped;
    private byte[] descriptors; // built on first use

    private FileSnapshot(List<Entry> entries, List<String> topNames, int skipped) {
        this.entries = Collections.unmodifiableList(entries);
        this.topNames = List.copyOf(topNames);
        this.skipped = skipped;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Summary summary() {
        int files = 0;
        int folders = 0;
        long bytes = 0;
        for (Entry e : entries) {
            if (e.directory()) {
                folders++;
            } else {
                files++;
                bytes += e.size();
            }
        }
        return new Summary(files, folders, bytes, topNames, skipped);
    }

    /**
     * Walks {@code roots} (what the user copied) into a snapshot. {@code protectedFolders} are left
     * out wherever they appear; those that don't exist are ignored.
     */
    public static FileSnapshot build(List<Path> roots, List<Path> protectedFolders) throws SnapshotException {
        List<Path> guarded = new ArrayList<>();
        for (Path p : protectedFolders) {
            if (Files.exists(p)) {
                guarded.add(p);
            }
        }
        Builder b = new Builder(guarded);
        for (Path root : roots) {
            Path name = root.getFileName();
            if (name == null) {
                throw new SnapshotException("A whole drive or file system can't be pasted on the remote desktop.");
            }
            b.add(root, name.toString(), 1);
        }
        if (b.entries.isEmpty()) {
            throw new SnapshotException("Nothing that was copied can be pasted on the remote desktop"
                    + " (links and special files are left out).");
        }
        return new FileSnapshot(b.entries, b.topNames, b.skipped);
    }

    private static final class Builder {
        final List<Path> guarded;
        final List<Entry> entries = new ArrayList<>();
        final List<String> topNames = new ArrayList<>();
        int skipped;

        Builder(List<Path> guarded) {
            this.guarded = guarded;
        }

        void add(Path path, String name, int depth) throws SnapshotException {
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                throw new SnapshotException("\"" + path + "\" can't be read: " + e.getMessage());
            }
            if (a.isSymbolicLink() || a.isOther() || (!a.isDirectory() && !a.isRegularFile()) || guarded(path)) {
                skipped++; // links are never followed; the vault and SSH keys are never sent
                return;
            }
            String leaf = path.getFileName().toString();
            if (!WindowsNames.valid(leaf)) {
                throw new SnapshotException("\"" + path + "\" has a name Windows can't use.");
            }
            if (name.length() > MAX_NAME) {
                throw new SnapshotException("\"" + path + "\" is nested too deeply: its name on the remote"
                        + " desktop would be longer than " + MAX_NAME + " characters.");
            }
            if (entries.size() >= MAX_ENTRIES) {
                throw new SnapshotException("More than " + String.format("%,d", MAX_ENTRIES)
                        + " files and folders were copied; copy fewer at a time.");
            }
            if (depth == 1) {
                topNames.add(leaf);
            }
            entries.add(new Entry(name, path, a.isDirectory(), a.isDirectory() ? 0 : a.size(), a.lastModifiedTime()));
            if (!a.isDirectory()) {
                return;
            }
            if (depth >= MAX_DEPTH) {
                throw new SnapshotException("\"" + path + "\" is nested more than " + MAX_DEPTH + " folders deep.");
            }
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path c : ds) {
                    children.add(c);
                    if (children.size() > MAX_ENTRIES) {
                        break; // the count check above reports it
                    }
                }
            } catch (IOException e) {
                throw new SnapshotException("The folder \"" + path + "\" can't be read: " + e.getMessage());
            }
            children.sort(null);
            for (Path c : children) {
                add(c, name + "\\" + c.getFileName(), depth + 1);
            }
        }

        boolean guarded(Path path) {
            for (Path g : guarded) {
                try {
                    if (Files.isSameFile(path, g)) {
                        return true;
                    }
                } catch (IOException e) {
                    // unreadable: treated as not the same; reading it fails later if it matters
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------ wire format

    /**
     * The packed file list the server receives for "FileGroupDescriptorW": a u32 count and one
     * {@code FILEDESCRIPTORW} per entry, names only (never a local path).
     */
    public synchronized byte[] descriptors() {
        if (descriptors == null) {
            ByteBuffer b = ByteBuffer.allocate(4 + entries.size() * DESCRIPTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(entries.size());
            for (Entry e : entries) {
                int start = b.position();
                b.putInt(FD_ATTRIBUTES | FD_FILESIZE | FD_WRITESTIME | FD_SHOWPROGRESSUI);
                b.position(start + 4 + 32); // reserved1
                b.putInt(e.directory() ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL);
                b.position(b.position() + 16); // reserved2
                b.putLong(toFileTime(e.modified()));
                b.putInt((int) (e.size() >>> 32));
                b.putInt((int) e.size());
                byte[] name = e.name().getBytes(StandardCharsets.UTF_16LE); // <= 518 bytes, MAX_NAME
                b.put(name);
                b.position(start + DESCRIPTOR_SIZE); // the rest of the 520 bytes stays NUL
            }
            descriptors = b.array();
        }
        return descriptors.clone();
    }

    static long toFileTime(FileTime t) {
        long micros = t.to(java.util.concurrent.TimeUnit.MICROSECONDS);
        return Math.max(0, micros * 10 + FILETIME_EPOCH_OFFSET);
    }

    /**
     * Answers a file-contents request from this snapshot: the 8-byte size, or up to
     * {@link #MAX_RANGE} bytes at a position. Fails for an index that is not a file of this
     * snapshot, a file that changed since, or one that can no longer be read.
     */
    public Reply serve(RdpProtocol.FileRequest r) {
        if (r.listIndex() < 0 || r.listIndex() >= entries.size()) {
            return Reply.FAIL;
        }
        Entry e = entries.get(r.listIndex());
        if (e.directory() || !unchanged(e)) {
            return Reply.FAIL;
        }
        if (r.flags() == RdpProtocol.FILECONTENTS_SIZE) {
            return new Reply(true, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(e.size()).array());
        }
        if (r.flags() != RdpProtocol.FILECONTENTS_RANGE || r.position() < 0) {
            return Reply.FAIL; // a position past 2^63 reads as negative: no file is that large
        }
        long left = e.size() - r.position();
        if (left <= 0) {
            return new Reply(true, new byte[0]); // at or past the end: nothing more
        }
        long wanted = Integer.toUnsignedLong(r.cbRequested());
        int n = (int) Math.min(Math.min(wanted, MAX_RANGE), left);
        byte[] out = new byte[n];
        try (SeekableByteChannel ch = Files.newByteChannel(e.path(), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            ch.position(r.position());
            ByteBuffer buf = ByteBuffer.wrap(out);
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) {
                    return Reply.FAIL; // shrank while being read
                }
            }
        } catch (IOException | UnsupportedOperationException ex) {
            return Reply.FAIL;
        }
        return new Reply(true, out);
    }

    private static boolean unchanged(Entry e) {
        try {
            BasicFileAttributes a = Files.readAttributes(e.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isRegularFile() && a.size() == e.size() && a.lastModifiedTime().equals(e.modified());
        } catch (IOException ex) {
            return false;
        }
    }
}
