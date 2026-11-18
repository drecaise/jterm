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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The names shared folders are announced under ({@code \\tsclient\<name>}). The device announcement
 * carries them as ASCII, and Windows shows the first 8 bytes as the DOS name, so a name is reduced
 * to letters, digits, {@code -} and {@code _}, at most 31 characters, and unique without regard to
 * case.
 */
public final class DriveNames {

    static final int MAX = 31;

    private DriveNames() {
    }

    public static String sanitize(String name) {
        StringBuilder b = new StringBuilder();
        String s = name == null ? "" : name.trim();
        for (int i = 0; i < s.length() && b.length() < MAX; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            b.append(ok ? c : '_');
        }
        return b.isEmpty() ? "share" : b.toString();
    }

    /** The name a newly added folder gets: its own, sanitised; {@code root} for a drive root. */
    public static String defaultFor(Path folder) {
        Path fn = folder.getFileName();
        return sanitize(fn != null ? fn.toString() : "root");
    }

    /** Sanitised and made unique ignoring case, by appending {@code _2}, {@code _3}… in order. */
    public static List<String> unique(List<String> names) {
        Set<String> taken = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String base = sanitize(n);
            String candidate = base;
            for (int i = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); i++) {
                String suffix = "_" + i;
                candidate = base.substring(0, Math.min(base.length(), MAX - suffix.length())) + suffix;
            }
            out.add(candidate);
        }
        return out;
    }
}
