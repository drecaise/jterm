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
package com.katmoda.jterm.ui.pane;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure composition rules for a pane's title-bar label and its tab title. No Swing, no session
 * access — every input is passed in — so the rules are unit-testable headlessly (a
 * {@link TerminalPane} cannot be constructed in a test: it starts a JediTerm widget).
 *
 * <p>The shape of a label is decided by three inputs: a user-chosen name (a rename wins outright,
 * showing no path at all), whether the pane is a <em>plain local</em> shell (a {@code LocalSession}
 * with no icon id — a WSL distro has one, so it is not plain local), and the
 * {@code Show working directory} preference:</p>
 *
 * <table><caption>composition</caption>
 * <tr><th></th><th>pane label</th><th>tab title</th></tr>
 * <tr><td>renamed</td><td>{@code custom}</td><td>{@code custom}</td></tr>
 * <tr><td>plain local</td><td>{@code cwd} (bare, always)</td><td>{@code Terminal N}, + {@code (cwd)} iff on</td></tr>
 * <tr><td>SSH / WSL</td><td>{@code name}, + {@code (cwd)} iff on</td><td>{@code name}, + {@code (cwd)} iff on</td></tr>
 * </table>
 *
 * <p>The pane label carries the <em>full</em> path and the tab only the last segment, because tab
 * strips are narrow and a pane's bar can truncate (see {@link #truncationCandidates}).</p>
 */
public final class PaneTitle {

    /** Longest label kept; a hostile remote could otherwise send a megabyte of title text. */
    private static final int MAX_LEN = 512;

    private PaneTitle() {
    }

    /**
     * A pane label split into its two parts so the bar can shorten the path without losing the
     * name. {@code path} is null when there is no directory to show.
     */
    public record PaneLabel(String head, String path) {

        /** The label as it reads when it fits: {@code name (path)}, or whichever half exists. */
        public String full() {
            if (path == null || path.isEmpty()) {
                return head;
            }
            return head.isEmpty() ? path : head + " (" + path + ")";
        }
    }

    /**
     * The label for a pane's own title bar. {@code base} is the session's automatic name, {@code
     * custom} the user's rename (null/blank when not renamed), {@code cwd} the tracked working
     * directory (null when unknown).
     */
    public static PaneLabel paneLabel(String custom, String base, String cwd,
                                      boolean plainLocal, boolean showCwd) {
        String name = clean(custom, null);
        if (name != null) {
            // A rename wins outright — no path, on either surface.
            return new PaneLabel(name, null);
        }
        String head = clean(base, "terminal");
        String dir = clean(cwd, null);
        if (plainLocal) {
            // A local shell's own name ("local") carries nothing, so the path stands alone —
            // and it does so whether or not the preference is on.
            return dir != null ? new PaneLabel("", dir) : new PaneLabel(head, null);
        }
        return (showCwd && dir != null) ? new PaneLabel(head, dir) : new PaneLabel(head, null);
    }

    /**
     * The tab title for a pane. {@code baseTitle} is the grid's generic {@code "Terminal N"} label,
     * used only by a plain local shell; everything else is named by its session. The directory is
     * appended as its last segment only, and only when the preference is on.
     */
    public static String tabTitle(String custom, String baseTitle, String base, String cwd,
                                  boolean plainLocal, boolean showCwd) {
        String name = clean(custom, null);
        if (name != null) {
            return name;
        }
        String head = plainLocal ? clean(baseTitle, clean(base, "terminal")) : clean(base, "terminal");
        String dir = clean(cwd, null);
        if (!showCwd || dir == null) {
            return head;
        }
        return head + " (" + lastSegment(dir) + ")";
    }

    /**
     * Progressively shorter renderings of {@code label}, widest first, for a title bar to measure
     * against its available width. Leading path components are dropped and replaced by {@code "..."}
     * — {@code /a/very/long/path/to/some/cwd} becomes {@code .../to/some/cwd} — then the name is
     * dropped too, and finally only the last segment is left. If even that does not fit, the caller
     * lets the label clip it in the usual way.
     */
    public static List<String> truncationCandidates(PaneLabel label) {
        List<String> out = new ArrayList<>();
        if (label.path() == null || label.path().isEmpty()) {
            add(out, label.head());
            return out;
        }
        List<String> paths = pathVariants(label.path());
        if (!label.head().isEmpty()) {
            for (String p : paths) {
                add(out, label.head() + " (" + p + ")");
            }
        }
        for (String p : paths) {
            add(out, p);
        }
        return out;
    }

    /**
     * {@code /a/b/c} → {@code [/a/b/c, .../b/c, .../c, c]}. The separator is whichever of {@code /}
     * or {@code \} the path itself uses, so a Windows path shortens to {@code ...\b\c}.
     */
    private static List<String> pathVariants(String path) {
        char sep = dominantSeparator(path);
        List<String> segs = new ArrayList<>();
        for (String s : path.split("[/\\\\]")) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        List<String> out = new ArrayList<>();
        add(out, path);
        for (int i = 1; i < segs.size(); i++) {
            add(out, "..." + sep + String.join(String.valueOf(sep), segs.subList(i, segs.size())));
        }
        if (!segs.isEmpty()) {
            add(out, segs.get(segs.size() - 1));
        }
        return out;
    }

    /** The final component of a path: {@code /a/b/} → {@code b}, {@code /} → {@code /}. */
    public static String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        int end = path.length();
        while (end > 1 && isSeparator(path.charAt(end - 1))) {
            end--;
        }
        String trimmed = path.substring(0, end);
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (cut < 0) {
            return trimmed;
        }
        String seg = trimmed.substring(cut + 1);
        return seg.isEmpty() ? trimmed : seg;
    }

    /**
     * Best-effort working directory from an OSC 0/1/2 window title. Default bash on RHEL/Rocky and
     * Debian/Ubuntu sets the title to {@code user@host:~/dir} on every prompt, which is the only
     * cwd signal most SSH and WSL sessions ever emit. Deliberately conservative: the token is taken
     * after the <em>last</em> colon and accepted only if it actually looks like a path, so titles
     * set by a full-screen app ({@code vim README}, {@code htop}) are ignored rather than adopted.
     *
     * @return the path, or null if the title does not carry one
     */
    public static String cwdFromWindowTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = title.trim();
        // A bare Windows path must not be split on its own drive-letter colon.
        if (isDrivePath(t)) {
            return t;
        }
        int colon = t.lastIndexOf(':');
        String candidate = (colon >= 0 ? t.substring(colon + 1) : t).trim();
        if (candidate.isEmpty()) {
            return null;
        }
        boolean pathLike = candidate.charAt(0) == '/' || candidate.charAt(0) == '~'
                || isDrivePath(candidate);
        return pathLike ? candidate : null;
    }

    private static boolean isDrivePath(String s) {
        return s.length() >= 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':'
                && isSeparator(s.charAt(2));
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    private static char dominantSeparator(String path) {
        int back = 0;
        int fwd = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '\\') {
                back++;
            } else if (path.charAt(i) == '/') {
                fwd++;
            }
        }
        return back > fwd ? '\\' : '/';
    }

    private static void add(List<String> out, String s) {
        if (!s.isEmpty() && (out.isEmpty() || !out.get(out.size() - 1).equals(s))) {
            out.add(s);
        }
    }

    /**
     * The single sanitisation choke point. Every string that reaches a pane label or a tab title
     * passes through here, because a working directory is <em>attacker-controlled data</em>: it
     * arrives as an escape sequence from whatever host the user connected to. A rename goes through
     * it too, since pasting from a terminal into the rename field is a realistic path.
     *
     * <p>Both parts of a label are cleaned individually rather than after joining, so the bar can
     * still shorten the path; the rules are per-character or anchored at the start, so cleaning the
     * parts and cleaning the join are equivalent.</p>
     *
     * @return the cleaned string, or {@code fallback} if nothing usable is left
     */
    private static String clean(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LEN));
        for (int i = 0; i < raw.length() && sb.length() < MAX_LEN; i++) {
            char c = raw.charAt(i);
            // C0, DEL and C1: a stray newline or BEL in a JTabbedPane title is not benign.
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                continue;
            }
            // Bidi overrides and other invisible format characters are the spoofing vector: a
            // RIGHT-TO-LEFT OVERRIDE lets a host render its label to read as a different host.
            if (Character.getType(c) == Character.FORMAT || (c >= 0x200B && c <= 0x200D)) {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString().trim();
        // Both JLabel and JTabbedPane.setTitleAt render a string that opens with <html> as markup.
        // The client property "html.disable" is set on both components as the structural fix; this
        // is the backstop, and it also covers the tooltip, which has no such property.
        while (startsWithHtml(s)) {
            s = s.substring(1);
        }
        return s.isEmpty() ? fallback : s;
    }

    private static boolean startsWithHtml(String s) {
        return s.length() >= 5 && s.charAt(0) == '<' && s.regionMatches(true, 1, "html", 0, 4);
    }
}
