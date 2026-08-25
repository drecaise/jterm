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
package com.katmoda.jterm.terminal.cwd;

/**
 * The working directory currently believed to belong to one terminal session, together with how
 * good the evidence for it is.
 *
 * <p>A session's directory can arrive from several places at once and they are not equally
 * trustworthy, so each report carries a {@link Source} and precedence is <em>sticky</em>: once a
 * better source has spoken, a weaker one can never overwrite it. That matters in practice because a
 * shell configured to emit OSC 7 almost always sets the window title as well, and the title is only
 * ever a guess — without stickiness every prompt would flip the value between the two.</p>
 *
 * <p>Written from the terminal's reader thread (the OSC scanner) and from the EDT (the {@code /proc}
 * poll), read from the EDT; the two fields are {@code volatile} and each report is a single
 * ordered pair of writes, which is all the consistency a display label needs.</p>
 */
public final class CwdTracker {

    /** Longest path kept, matching the label's own cap — a hostile host can send far more. */
    private static final int MAX_LEN = 512;

    /** Where a directory came from, in ascending order of trust. */
    public enum Source {
        /**
         * Parsed out of an OSC 0/1/2 window title — a guess, and the only one, see {@code
         * PaneTitle}. Ranked lowest because any program can set a title: a full-screen editor or an
         * {@code ssh} to somewhere else can put a path-shaped string there that is not the shell's
         * directory at all.
         */
        TITLE,
        /**
         * Read from {@code /proc/<pid>/cwd}; only a directly-spawned local shell on Linux. This is
         * the kernel's answer for a process we started ourselves, so it outranks a parsed title —
         * a native local pane ignores titles entirely and cannot be misled by one.
         */
        PROC,
        /** OSC 9;9, the Windows Terminal convention. The shell stating its directory outright. */
        OSC99,
        /** OSC 7 {@code file://host/path}, the sequence meant for exactly this. */
        OSC7
    }

    private volatile String cwd;
    private volatile Source best;

    /** Records a directory if {@code source} is at least as trustworthy as the best one so far. */
    public void report(Source source, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Source current = best;
        if (current != null && source.ordinal() < current.ordinal()) {
            return;
        }
        String value = path.length() > MAX_LEN ? path.substring(0, MAX_LEN) : path;
        cwd = value;
        best = source;
    }

    /** The best known working directory, or null if nothing has reported one. */
    public String current() {
        return cwd;
    }

    /**
     * Forgets everything, including the precedence high-water mark. Called when a pane reconnects:
     * the new session is a different shell, possibly on a host that reports nothing, and must not
     * inherit the old one's directory or its claim to a better source.
     */
    public void reset() {
        cwd = null;
        best = null;
    }
}
