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
package com.katmoda.jterm.terminal.rdp;

import java.util.Locale;
import java.util.Set;

/**
 * Which names Windows accepts for a file or directory: the rule both directions of file exchange
 * with an RDP server apply, to a name the server sends as well as to one jterm sends it. Pure.
 */
public final class WindowsNames {

    /** Longest name accepted, in UTF-16 units. */
    public static final int MAX_COMPONENT = 255;

    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private WindowsNames() {
    }

    /**
     * Whether {@code name} is one plain name Windows would accept: not empty, not {@code .} or
     * {@code ..}, no separator, no {@code :} (an NTFS alternate data stream), no wildcard or control
     * character, no trailing dot or space, and no reserved device name ({@code CON}, {@code NUL.txt}...).
     */
    public static boolean valid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_COMPONENT
                || name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        char last = name.charAt(name.length() - 1);
        if (last == '.' || last == ' ') {
            return false;
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).trim().toUpperCase(Locale.ROOT);
        return !RESERVED.contains(stem);
    }
}
