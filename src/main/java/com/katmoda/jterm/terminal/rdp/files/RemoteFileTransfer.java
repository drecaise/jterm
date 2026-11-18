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
import com.katmoda.jterm.terminal.rdp.drive.OriginMark;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Writes the files of a {@link RemoteFileList} into a folder the user chose. Swing-free: the UI
 * answers conflicts and shows progress through a {@link Listener}, and the bytes come from a
 * {@link Reader}. Runs on the caller's thread, blocking.
 *
 * <p><b>Nothing leaves the chosen folder.</b> The names were checked when the list was parsed;
 * here every folder on the way is created one level at a time without following links, and an
 * existing link, a file where a folder belongs, or a protected folder (jterm's config folder,
 * {@code ~/.ssh}, so a copied {@code .ssh\authorized_keys} saved into the home folder can't plant a
 * key) fails that entry. A file is written under a temporary name in its own folder and renamed
 * when complete, so a cancelled or failed copy never leaves half a file under the real name, and
 * an existing file is replaced only when the user said so. Written files are marked as downloaded
 * ({@link OriginMark}).</p>
 *
 * <p><b>Sizes.</b> Each file's size is asked for separately and the smaller of that and the
 * list's figure is read, 1 MiB at a time with up to {@link #IN_FLIGHT} ranges in flight. A range
 * that comes back short (the file changed on the remote side) fails the file. A file that would
 * leave the volume with less than 1 GiB or 5 % free is not written.</p>
 */
public final class RemoteFileTransfer {

    public static final int CHUNK = 1024 * 1024;
    public static final int IN_FLIGHT = 4;
    static final long READ_TIMEOUT_SECONDS = 60;
    static final long MIN_FREE_BYTES = 1L << 30;
    static final double MIN_FREE_FRACTION = 0.05;

    /** What to do about a file that already exists. */
    public enum Decision { REPLACE, SKIP, CANCEL }

    /** Reads from the server's list (see {@link RemoteClipboardFiles#fetch}). */
    @FunctionalInterface
    public interface Reader {
        CompletableFuture<byte[]> fetch(int listIndex, int flags, long position, int cb);
    }

    /** The UI's side: conflicts, progress and Cancel. Called on the transfer's thread. */
    public interface Listener {
        Decision exists(Path target);

        void progress(String name, long done, long total);

        boolean cancelled();
    }

    public record Result(int written, List<String> skipped, List<String> failed, boolean cancelled) {
    }

    private final Path root;
    private final List<Path> protectedFolders;
    private final Reader reader;
    private final Listener listener;
    private final OriginMark mark;
    private final long readTimeoutSeconds;

    private final List<String> skipped = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private int written;
    private boolean cancelled;
    private long done;
    private long total;

    public RemoteFileTransfer(Path destination, List<Path> protectedFolders, Reader reader, Listener listener,
                              OriginMark mark) throws IOException {
        this(destination, protectedFolders, reader, listener, mark, READ_TIMEOUT_SECONDS);
    }

    RemoteFileTransfer(Path destination, List<Path> protectedFolders, Reader reader, Listener listener,
                       OriginMark mark, long readTimeoutSeconds) throws IOException {
        this.root = destination.toRealPath();
        this.protectedFolders = protectedFolders.stream().map(RemoteFileTransfer::canonical).toList();
        for (Path a = root; a != null; a = a.getParent()) {
            if (isProtected(a)) {
                throw new IOException("jterm never saves into its own settings folder or your SSH keys.");
            }
        }
        this.reader = reader;
        this.listener = listener;
        this.mark = mark;
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    /**
     * Why {@code bytes} can't be written into {@code folder}, or null when it can: not enough space
     * above the free-space floor.
     */
    public static String spaceProblem(Path folder, long bytes) {
        try {
            FileStore store = Files.getFileStore(folder);
            long floor = Math.max(MIN_FREE_BYTES, (long) (store.getTotalSpace() * MIN_FREE_FRACTION));
            long usable = store.getUsableSpace();
            if (usable - bytes < floor) {
                return "There is not enough free space in " + folder + ": the files need "
                        + String.format("%,d", bytes) + " bytes, and jterm keeps at least "
                        + String.format("%,d", floor) + " bytes free.";
            }
            return null;
        } catch (IOException e) {
            return null; // unknown: each file is checked again as it is written
        }
    }

    public Result run(RemoteFileList list) {
        total = list.bytes();
        for (RemoteFileList.Entry e : list.entries()) {
            if (cancelled || listener.cancelled()) {
                cancelled = true;
                break;
            }
            try {
                if (e.directory()) {
                    folder(e.components(), e.components().size());
                } else {
                    file(e);
                }
            } catch (EntryFailed f) {
                failed.add(e.name() + " (" + f.getMessage() + ")");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cancelled = true;
                break;
            }
        }
        return new Result(written, List.copyOf(skipped), List.copyOf(failed), cancelled);
    }

    /** Fails one entry, not the transfer. */
    private static final class EntryFailed extends Exception {
        EntryFailed(String message) {
            super(message);
        }
    }

    /** Creates (or finds) the folder made of the first {@code depth} components, one level at a time. */
    private Path folder(List<String> components, int depth) throws EntryFailed {
        Path cur = root;
        for (int i = 0; i < depth; i++) {
            Path next = cur.resolve(components.get(i));
            if (!next.normalize().startsWith(root) || !next.getParent().equals(cur)) {
                throw new EntryFailed("unsafe name"); // can't happen with checked names; asserted anyway
            }
            if (isProtected(next)) { // before anything is created: ~/.ssh may not exist yet
                throw new EntryFailed("jterm never writes into its own settings or your SSH keys");
            }
            BasicFileAttributes a = null;
            try {
                a = Files.readAttributes(next, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                // created below
            } catch (IOException ex) {
                throw new EntryFailed("\"" + next.getFileName() + "\" can't be read: " + ex.getMessage());
            }
            if (a != null) {
                // isOther as well as isSymbolicLink: a Windows junction is not a symbolic link to
                // Java, and it *is* a directory, so the plain "is it a folder" question lets it
                // through (found on the Windows VM: a save went straight through one).
                if (a.isSymbolicLink() || a.isOther() || !a.isDirectory()) {
                    throw new EntryFailed("\"" + next.getFileName() + "\" is in the way and is not a folder");
                }
            } else {
                try {
                    Files.createDirectory(next);
                } catch (IOException ex) {
                    throw new EntryFailed("folder can't be created: " + ex.getMessage());
                }
            }
            cur = next;
        }
        return cur;
    }

    /**
     * Whether {@code p} is a protected folder, existing or not. By path, ignoring case (a server's
     * {@code .SSH} is {@code ~/.ssh} on a case-insensitive volume, and refusing it elsewhere costs
     * nothing), and by identity when both exist (a bind mount, an 8.3 short name).
     */
    private boolean isProtected(Path p) {
        for (Path g : protectedFolders) {
            if (p.toString().equalsIgnoreCase(g.toString())) {
                return true;
            }
            try {
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.exists(g) && Files.isSameFile(p, g)) {
                    return true;
                }
            } catch (IOException e) {
                // unreadable: not the same
            }
        }
        return false;
    }

    /** {@code p} with its nearest existing ancestor resolved to its real path, the rest appended. */
    static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        Deque<Path> rest = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing)) {
            rest.push(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        try {
            Path out = existing.toRealPath();
            for (Path name : rest) {
                out = out.resolve(name);
            }
            return out;
        } catch (IOException e) {
            return abs;
        }
    }

    private void file(RemoteFileList.Entry e) throws EntryFailed, InterruptedException {
        List<String> c = e.components();
        Path dir = folder(c, c.size() - 1);
        String leaf = c.get(c.size() - 1);
        Path target = dir.resolve(leaf);

        boolean replace = false;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new EntryFailed("a folder of that name already exists");
            }
            Decision d = listener.exists(target);
            switch (d) {
                case CANCEL -> {
                    cancelled = true;
                    return;
                }
                case SKIP -> {
                    skipped.add(e.name());
                    return;
                }
                case REPLACE -> replace = true;
            }
        }

        byte[] sizeBytes = await(reader.fetch(e.index(), RdpProtocol.FILECONTENTS_SIZE, 0, 8));
        if (sizeBytes.length != 8) {
            throw new EntryFailed("the remote desktop sent no size");
        }
        long size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (size < 0) {
            throw new EntryFailed("the remote desktop sent an impossible size");
        }
        long limit = e.sizeKnown() ? Math.min(size, e.size()) : size;
        if (!e.sizeKnown()) {
            total += limit;
        }
        String space = spaceProblem(dir, limit);
        if (space != null) {
            throw new EntryFailed("not enough free space");
        }

        Path temp = dir.resolve("." + leaf + ".jterm-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".part");
        boolean moved = false;
        try {
            try (SeekableByteChannel out = Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                copy(e, limit, out);
            }
            if (cancelled) {
                return;
            }
            if (e.modified() != null) {
                Files.setLastModifiedTime(temp, e.modified());
            }
            if (replace) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); // a link is replaced, not followed
            } else {
                Files.move(temp, target); // fails rather than replace a file that appeared meanwhile
            }
            moved = true;
            mark.mark(target);
            written++;
        } catch (IOException ex) {
            throw new EntryFailed(ex.getMessage());
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // left behind under a hidden temporary name
                }
            }
        }
    }

    private record Range(long position, int length, CompletableFuture<byte[]> data) {
    }

    private void copy(RemoteFileList.Entry e, long limit, SeekableByteChannel out)
            throws EntryFailed, InterruptedException, IOException {
        Deque<Range> inFlight = new ArrayDeque<>();
        long next = 0;
        long fileDone = 0;
        while (fileDone < limit) {
            while (next < limit && inFlight.size() < IN_FLIGHT) {
                int n = (int) Math.min(CHUNK, limit - next);
                inFlight.add(new Range(next, n, reader.fetch(e.index(), RdpProtocol.FILECONTENTS_RANGE, next, n)));
                next += n;
            }
            if (listener.cancelled()) {
                cancelled = true;
                return;
            }
            Range r = inFlight.removeFirst();
            byte[] data = await(r.data());
            if (data.length != r.length()) {
                throw new EntryFailed("the file changed on the remote desktop during the copy");
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                out.write(buf);
            }
            fileDone += data.length;
            done += data.length;
            listener.progress(e.name(), done, total);
        }
    }

    private byte[] await(CompletableFuture<byte[]> f) throws EntryFailed, InterruptedException {
        try {
            return f.get(readTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw new EntryFailed(cause != null && cause.getMessage() != null ? cause.getMessage() : "read failed");
        } catch (TimeoutException ex) {
            throw new EntryFailed("the remote desktop stopped answering");
        }
    }
}
