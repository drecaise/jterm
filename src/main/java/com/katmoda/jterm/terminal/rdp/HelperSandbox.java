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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far the helper could restrict itself, from the line it logs at its lockdown point (sandbox
 * design, "Reporting and failure policy"):
 * {@code jterm-rdp-helper: sandbox level=full landlock=6 net=1 scope=1 seccomp=1 fsize=1 nnp=1} on
 * Linux, {@code jterm-rdp-helper: sandbox level=partial integrity=low job=1 mitigations=4
 * token=restricted} on Windows (steps W1 and W2; neither limits the network, so never full; without
 * {@code token=restricted} only W1 applied, which can't stop reading files either), and
 * {@code jterm-rdp-helper: sandbox level=full seatbelt=1 ports=1} on macOS (a Seatbelt profile; a
 * failed {@code sandbox_init} reports {@code level=none seatbelt=0}).
 * A system that can't apply everything still connects (decision D1); this says what it got, for the
 * pane's tooltip. No protocol change: an older helper simply logs no such line. Swing-free.
 */
public record HelperSandbox(Level level, int landlock, boolean network, boolean scopes, boolean seccomp,
                            boolean fileSizeLimit, boolean windows, boolean restrictedToken) {

    public enum Level { FULL, PARTIAL, NONE }

    private static final String PREFIX = "jterm-rdp-helper: sandbox ";

    /** The report in {@code line}, or {@code null} when the line is anything else. */
    public static HelperSandbox parseHelperLine(String line) {
        if (line == null || !line.startsWith(PREFIX)) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (String part : line.substring(PREFIX.length()).trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        Level level = switch (fields.getOrDefault("level", "")) {
            case "full" -> Level.FULL;
            case "partial" -> Level.PARTIAL;
            case "none" -> Level.NONE;
            default -> null;
        };
        if (level == null) {
            return null;
        }
        return new HelperSandbox(level, number(fields.get("landlock")), flag(fields.get("net")),
                flag(fields.get("scope")), flag(fields.get("seccomp")), flag(fields.get("fsize")),
                fields.containsKey("integrity"), "restricted".equals(fields.get("token")));
    }

    private static int number(String v) {
        try {
            return v == null ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean flag(String v) {
        return "1".equals(v);
    }

    /** One line for a tooltip, e.g. "Helper sandbox: partial (no network rule: this kernel's Landlock is too old)". */
    public String describe() {
        return switch (level) {
            case FULL -> "Helper sandbox: full";
            case NONE -> "Helper sandbox: none";
            case PARTIAL -> !windows
                    ? "Helper sandbox: partial (" + String.join(", ", missing()) + ")"
                    : restrictedToken
                    ? "Helper sandbox: partial (it can't reach your files, saved passwords, microphone or "
                            + "other programs, but can connect to any computer)"
                    : "Helper sandbox: partial (it can't change your files or reach other programs, "
                            + "but can still read files)";
        };
    }

    private List<String> missing() {
        List<String> out = new ArrayList<>();
        if (landlock == 0) {
            out.add("no file restrictions: this kernel has no Landlock");
        } else if (!network) {
            out.add("no network rule: this kernel's Landlock is too old");
        }
        if (!seccomp) {
            out.add("no system-call filter");
        }
        if (!fileSizeLimit) {
            out.add("files can be written");
        }
        return out;
    }
}
