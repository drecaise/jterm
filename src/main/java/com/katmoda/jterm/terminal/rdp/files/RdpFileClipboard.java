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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which files the remote desktop may read when it pastes, for one session: the local copy currently
 * announced to it, the user's answer for that copy, and the copies the server has locked. Swing-free
 * and not thread-safe: the clipboard bridge calls it from its one thread.
 *
 * <p><b>A copy</b> is one announcement of a file list. The user's answer to "may the remote desktop
 * paste these?" belongs to it, a refusal as much as a consent, and the next announcement starts a
 * new copy with a new question. jterm announces only when the local clipboard changed (a new copy,
 * noticed by an event or on focus gain), never because the server asks, so a server cannot ask
 * again and again to wear the user down, and Windows retrying a refused request is refused
 * silently.</p>
 *
 * <p><b>Locks</b> (clip-data locking, MS-RDPECLIP 3.1.5.3): the server sends Lock with an id before
 * reading, and its file-contents requests carry the id. A lock pins the copy announced at that
 * moment, so a paste that is still running keeps reading the files it started with even after the
 * user copies something else locally. A request without an id reads the current copy. Locks hold
 * only a reference to a copy (a list of paths, sizes and times), and at most {@link #MAX_LOCKS} are
 * kept, the oldest dropped first, so a server cannot make jterm hold unbounded state.</p>
 */
public final class RdpFileClipboard {

    public static final int MAX_LOCKS = 16;

    /** One announced file list, and what became of it. */
    public static final class Copy {
        private final List<Path> roots;
        private FileSnapshot snapshot;
        private Boolean allowed; // null: not asked yet

        Copy(List<Path> roots) {
            this.roots = List.copyOf(roots);
        }

        public List<Path> roots() {
            return roots;
        }

        /** The snapshot built for the first paste of this copy, or null. */
        public FileSnapshot snapshot() {
            return snapshot;
        }

        public void setSnapshot(FileSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /** The user's answer, or null when not asked yet. */
        public Boolean allowed() {
            return allowed;
        }

        public void decide(boolean allow) {
            this.allowed = allow;
        }
    }

    private Copy current;
    private final Map<Integer, Copy> locks = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Copy> eldest) {
            return size() > MAX_LOCKS;
        }
    };

    /** jterm announced the local clipboard: these files, or none ({@code null} or empty). */
    public void announced(List<Path> roots) {
        current = roots == null || roots.isEmpty() ? null : new Copy(roots);
    }

    /**
     * The copy the server's paste reads, given the files on the local clipboard now: the announced
     * one, or a new one if the clipboard changed and the announcement hasn't gone out yet.
     */
    public Copy copyFor(List<Path> roots) {
        if (current == null || !current.roots.equals(roots)) {
            current = new Copy(roots);
        }
        return current;
    }

    /** The server locks the clipboard: {@code id} pins the copy announced now. */
    public void lock(int id) {
        locks.remove(id); // re-inserted as the newest
        if (current != null) {
            locks.put(id, current);
        }
    }

    public void unlock(int id) {
        locks.remove(id);
    }

    /**
     * The snapshot a file-contents request may read: of the copy its lock pinned, else of the
     * current copy. Null when there is none, the user has not allowed it, or the id is not locked.
     */
    public FileSnapshot snapshotFor(RdpProtocol.FileRequest r) {
        Copy c = r.haveClipDataId() ? locks.get(r.clipDataId()) : current;
        if (c == null || !Boolean.TRUE.equals(c.allowed)) {
            return null;
        }
        return c.snapshot;
    }

    int lockCount() {
        return locks.size();
    }

    /** Forgets everything: the session ended. */
    public void clear() {
        current = null;
        locks.clear();
    }
}
