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
package com.katmoda.jterm.terminal.local;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strips the Snap runtime out of the environment handed to a local shell.
 *
 * <p>The Snap build uses classic confinement, so unlike Flatpak it spawns the shell directly —
 * same namespace, same devpts, same session as the PTY — and has none of the job-control or
 * {@code ttyname} problems {@link FlatpakHost} exists to solve. What it does have is leakage:
 * the shell inherits the snap's {@code LD_LIBRARY_PATH}, {@code LOCPATH}, snap-prefixed
 * {@code PATH}, and the {@code SNAP_*} set, which is enough to make host binaries launched
 * from that shell load snap libraries.
 */
final class SnapEnvironment {

    /** Runtime plumbing that points into the snap and must not follow the user's shell out. */
    private static final Set<String> DROP = Set.of(
            "LD_LIBRARY_PATH",
            "LD_PRELOAD",
            "LOCPATH",
            "GIO_MODULE_DIR",
            "GSETTINGS_SCHEMA_DIR",
            "GTK_PATH",
            "GDK_PIXBUF_MODULE_FILE",
            "GDK_PIXBUF_MODULEDIR",
            "PYTHONPATH",
            "PERL5LIB",
            "JAVA_HOME");

    /** Colon-separated vars the snap prepends itself to rather than replacing outright. */
    private static final String[] PATH_LIKE = {"PATH", "XDG_DATA_DIRS", "XDG_CONFIG_DIRS"};

    private SnapEnvironment() {
    }

    /**
     * Returns {@code env} unchanged when not running as a snap, otherwise a sanitised copy.
     * Safe to call on every platform — {@code $SNAP} is the gate.
     */
    static Map<String, String> sanitize(Map<String, String> env) {
        String snap = env.get("SNAP");
        if (snap == null || snap.isBlank()) {
            return env;
        }

        // Read before the SNAP_* sweep removes it.
        String realHome = env.get("SNAP_REAL_HOME");

        Map<String, String> out = new HashMap<>(env);
        out.keySet().removeIf(key -> key.equals("SNAP") || key.startsWith("SNAP_") || DROP.contains(key));

        for (String key : PATH_LIKE) {
            String value = out.get(key);
            if (value == null) {
                continue;
            }
            String stripped = stripSnapEntries(value, snap);
            if (stripped.isEmpty()) {
                out.remove(key);
            } else {
                out.put(key, stripped);
            }
        }

        // Classic confinement leaves HOME alone, but be explicit rather than trusting that.
        if (realHome != null && !realHome.isBlank()) {
            out.put("HOME", realHome);
        }
        return out;
    }

    /** Drops every colon-separated entry that lives under the snap's mount point. */
    private static String stripSnapEntries(String value, String snap) {
        return Arrays.stream(value.split(":", -1))
                .filter(entry -> !entry.isEmpty())
                .filter(entry -> !entry.equals(snap) && !entry.startsWith(snap + "/"))
                .collect(Collectors.joining(":"));
    }
}
