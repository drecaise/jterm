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

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The server's copied files, as far as one session's clipboard is concerned: which list is current,
 * the lock that keeps it readable, and the reads in flight. Swing-free and thread-safe (the bridge
 * calls it from its thread, a transfer from its own).
 *
 * <p><b>Client locks</b> (MS-RDPECLIP clip-data locking): when the server agreed to locking, every
 * server copy that holds files is locked straight away under a fresh id, before jterm even asks for
 * the list, and every read carries the id. A transfer started from that list then keeps reading the
 * same files after the user copies something else on the remote desktop. A lock is released when
 * the next server copy arrives, unless a transfer still uses it, and then when that transfer ends.
 * Without locking, a new server copy fails every read of the old list in flight and refuses new ones
 * ({@link ClipboardChangedException}), so two copies are never mixed into one file.</p>
 */
public final class RemoteClipboardFiles {

    /** What the session can send to the helper. */
    public interface Wire {
        void fetch(int streamId, int listIndex, int flags, long position, int cbRequested,
                   boolean haveClipDataId, int clipDataId);

        void lock(boolean lock, int clipDataId);
    }

    /** A read failed because the remote clipboard changed and the list was not locked. */
    public static final class ClipboardChangedException extends IOException {
        public ClipboardChangedException() {
            super("the remote clipboard changed during the copy");
        }
    }

    /** One list the server described, from one server copy. */
    public record Offer(long generation, RemoteFileList list, Integer lockId) {
    }

    private record Pending(CompletableFuture<byte[]> future, long generation, boolean locked) {
    }

    private final Wire wire;
    private boolean canLock;
    private long generation;
    private Integer currentLock;
    private Offer current;
    private int nextLockId = 1;
    private int nextStreamId = 1;
    private final Map<Integer, Integer> lockUsers = new HashMap<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private boolean closed;

    public RemoteClipboardFiles(Wire wire) {
        this.wire = wire;
    }

    /** The server agreed (or not) to clip-data locking. */
    public synchronized void setCanLock(boolean canLock) {
        this.canLock = canLock;
    }

    /**
     * The server announced a new clipboard. Returns the generation a list fetched for it must carry.
     * Any earlier list stops being current; reads of an unlocked one fail.
     */
    public synchronized long serverCopied(boolean hasFiles) {
        generation++;
        current = null;
        releaseIfUnused(currentLock);
        currentLock = null;
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.locked()) {
                p.future().completeExceptionally(new ClipboardChangedException());
                it.remove();
            }
        }
        if (hasFiles && canLock && !closed) {
            currentLock = nextLockId++;
            wire.lock(true, currentLock);
        }
        return generation;
    }

    /** The list for generation {@code gen} arrived: the current offer, or null when it is stale. */
    public synchronized Offer listArrived(long gen, RemoteFileList list) {
        if (gen != generation || closed) {
            return null;
        }
        current = new Offer(gen, list, currentLock);
        return current;
    }

    /** The list a "Save files" may use now, or null. */
    public synchronized Offer current() {
        return current;
    }

    /** A transfer starts reading {@code o}: its lock is kept until {@link #end}. */
    public synchronized void begin(Offer o) {
        if (o.lockId() != null) {
            lockUsers.merge(o.lockId(), 1, Integer::sum);
        }
    }

    public synchronized void end(Offer o) {
        if (o.lockId() == null) {
            return;
        }
        lockUsers.computeIfPresent(o.lockId(), (k, n) -> n > 1 ? n - 1 : null);
        if (!o.lockId().equals(currentLock)) {
            releaseIfUnused(o.lockId());
        }
    }

    private void releaseIfUnused(Integer lockId) {
        if (lockId != null && !lockUsers.containsKey(lockId) && !closed) {
            wire.lock(false, lockId);
        }
    }

    /**
     * Reads from {@code o}: a size ({@code FILECONTENTS_SIZE}) or {@code cb} bytes at {@code position}.
     * Fails at once if {@code o} was not locked and the server has copied something else since.
     */
    public CompletableFuture<byte[]> fetch(Offer o, int listIndex, int flags, long position, int cb) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        int stream;
        synchronized (this) {
            if (closed) {
                f.completeExceptionally(new IOException("the session ended"));
                return f;
            }
            if (o.lockId() == null && o.generation() != generation) {
                f.completeExceptionally(new ClipboardChangedException());
                return f;
            }
            stream = nextStreamId++;
            pending.put(stream, new Pending(f, o.generation(), o.lockId() != null));
        }
        wire.fetch(stream, listIndex, flags, position, cb, o.lockId() != null, o.lockId() != null ? o.lockId() : 0);
        return f;
    }

    /** The helper's answer to a read. An unknown stream id is dropped. */
    public void dataArrived(int streamId, boolean ok, byte[] data) {
        Pending p;
        synchronized (this) {
            p = pending.remove(streamId);
        }
        if (p == null) {
            return;
        }
        if (ok) {
            p.future().complete(data != null ? data : new byte[0]);
        } else {
            p.future().completeExceptionally(new IOException("the remote desktop could not supply the file"));
        }
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    /** The session ended: every read in flight fails, and nothing more is sent. */
    public void close() {
        Map<Integer, Pending> failing;
        synchronized (this) {
            closed = true;
            current = null;
            failing = new HashMap<>(pending);
            pending.clear();
        }
        for (Pending p : failing.values()) {
            p.future().completeExceptionally(new IOException("the session ended"));
        }
    }
}
