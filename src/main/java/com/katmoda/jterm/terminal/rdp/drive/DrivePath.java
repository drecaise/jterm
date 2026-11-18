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

import com.katmoda.jterm.terminal.rdp.WindowsNames;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a path from the server into checked name components, or refuses it. Pure: no file system.
 *
 * <p>The server sends paths relative to the share as UTF-16LE with {@code \} separators, such as
 * {@code \Reports\2026.xlsx}. Only plain names survive: no {@code .} or {@code ..}, no empty
 * component in the middle, nothing Windows itself would not accept as a file name (so no {@code :},
 * which would name an alternate data stream on NTFS, no wildcards, no control characters, no
 * trailing dot or space, no reserved device name). A component can therefore never step outside
 * the share or mean something different on the local file system than on the server.</p>
 */
final class DrivePath {

    /** Longest path accepted, in UTF-16 units: the Windows extended-length limit. */
    static final int MAX_LENGTH = 32_767;
    /** Longest name accepted, in UTF-16 units. */
    static final int MAX_COMPONENT = WindowsNames.MAX_COMPONENT;

    private DrivePath() {
    }

    /**
     * Decodes a path field: {@code byteLength} bytes of UTF-16LE at the buffer's position, which
     * may end in a NUL terminator. Advances the buffer past the field.
     */
    static String decode(ByteBuffer in, long byteLength) throws DriveException {
        if (byteLength < 0 || byteLength > in.remaining() || byteLength > 2L * (MAX_LENGTH + 1)) {
            throw new DriveException(NtStatus.OBJECT_NAME_INVALID);
        }
        byte[] raw = new byte[(int) byteLength & ~1];
        in.get(raw);
        if ((byteLength & 1) != 0) {
            in.get(); // a stray odd byte is not a character
        }
        String s = new String(raw, StandardCharsets.UTF_16LE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** The components of {@code path}, empty for the share root. */
    static List<String> parse(String path) throws DriveException {
        return split(path, false);
    }

    /**
     * A directory query's path: the directory's components plus a final search pattern, which may
     * hold wildcards ({@code * ?} and the DOS forms {@code < > "}). An empty pattern means "*".
     */
    static List<String> parsePattern(String path) throws DriveException {
        List<String> parts = split(path, true);
        if (parts.isEmpty()) {
            parts.add("*");
        }
        return parts;
    }

    private static List<String> split(String path, boolean lastIsPattern) throws DriveException {
        if (path.length() > MAX_LENGTH) {
            throw new DriveException(NtStatus.OBJECT_NAME_INVALID);
        }
        String p = path;
        if (p.startsWith("\\")) {
            p = p.substring(1);
        }
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) {
            return out;
        }
        String[] parts = p.split("\\\\", -1);
        int n = parts.length;
        if (parts[n - 1].isEmpty()) {
            n--; // one trailing separator, as in "dir\", is harmless
        }
        for (int i = 0; i < n; i++) {
            boolean pattern = lastIsPattern && i == n - 1;
            String name = parts[i];
            if (!pattern) {
                checkName(name);
            } else {
                checkPattern(name);
            }
            out.add(name);
        }
        return out;
    }

    /** A name the server may use for a file or directory. */
    static void checkName(String name) throws DriveException {
        if (!validName(name)) {
            throw new DriveException(NtStatus.OBJECT_NAME_INVALID);
        }
    }

    static boolean validName(String name) {
        return WindowsNames.valid(name);
    }

    private static void checkPattern(String pattern) throws DriveException {
        if (pattern.isEmpty() || pattern.length() > MAX_COMPONENT) {
            throw new DriveException(NtStatus.OBJECT_NAME_INVALID);
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c < 0x20 || "\\/:|".indexOf(c) >= 0) {
                throw new DriveException(NtStatus.OBJECT_NAME_INVALID);
            }
        }
    }

    /**
     * Whether {@code name} matches a directory-query {@code pattern}, ignoring case. {@code *} and
     * {@code ?} as usual; the DOS forms ({@code <} for {@code *}, {@code >} for {@code ?}, {@code "}
     * for {@code .}) are treated as their plain equivalents, which is all a listing needs.
     */
    static boolean matches(String pattern, String name) {
        if (pattern.equals("*") || pattern.equals("*.*") || pattern.equals("<")) {
            return true;
        }
        String p = pattern.toUpperCase(Locale.ROOT).replace('<', '*').replace('>', '?').replace('"', '.');
        String s = name.toUpperCase(Locale.ROOT);
        return glob(p, 0, s, 0, new int[]{0});
    }

    /** Iterative-backtracking glob; {@code budget} bounds the work a hostile pattern can cause. */
    private static boolean glob(String p, int pi, String s, int si, int[] budget) {
        int star = -1;
        int mark = 0;
        while (si < s.length()) {
            if (++budget[0] > 1_000_000) {
                return false;
            }
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                pi++;
                si++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = si;
            } else if (star >= 0) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }
}
