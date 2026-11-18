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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the {@code jterm-rdp-helper} executable.
 *
 * <p><b>Only trusted locations are searched</b>, by absolute path: the {@value #PROPERTY} system
 * property (developer runs and the fat jar), then the application's own install directory
 * ({@code <app>/rdp/}, from jpackage's {@code jpackage.app-path}; {@code /app/lib/jterm/rdp} in the
 * Flatpak; {@code $SNAP/rdp} in the snap). Never {@code PATH}, never the directory of a jar that
 * might be sitting in Downloads next to a planted executable — jterm hands this process the RDP
 * password.</p>
 */
public final class RdpHelperLocator {

    /** System property naming the helper executable explicitly. */
    public static final String PROPERTY = "jterm.rdp.helper";

    /** Where the helper was found, or why not. */
    public sealed interface Result {
    }

    public record Found(Path executable) implements Result {
    }

    public record NotFound(String reason) implements Result {
    }

    private RdpHelperLocator() {
    }

    public static Result locate() {
        return locate(System.getProperties().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()),
                                e -> String.valueOf(e.getValue()))),
                System.getenv());
    }

    static Result locate(Map<String, String> props, Map<String, String> env) {
        boolean windows = props.getOrDefault("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "jterm-rdp-helper.exe" : "jterm-rdp-helper";

        String explicit = props.get(PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit).toAbsolutePath();
            return Files.isRegularFile(p)
                    ? new Found(p)
                    : new NotFound("The RDP helper set in -D" + PROPERTY + " does not exist: " + p);
        }

        List<Path> candidates = new ArrayList<>();
        String appPath = props.get("jpackage.app-path"); // the native launcher of an installed jterm
        if (appPath != null && !appPath.isBlank()) {
            for (Path appDir : jpackageAppDirs(Path.of(appPath))) {
                candidates.add(appDir.resolve("rdp").resolve(exe));
            }
        }
        if (env.get("FLATPAK_ID") != null) {
            candidates.add(Path.of("/app/lib/jterm/rdp", exe));
        }
        String snap = env.get("SNAP");
        if (snap != null && !snap.isBlank()) {
            candidates.add(Path.of(snap, "rdp", exe));
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return new Found(c);
            }
        }
        return new NotFound(candidates.isEmpty()
                ? "RDP support is not included in this build of jterm. "
                  + "Run with -D" + PROPERTY + "=<path to " + exe + "> to use a helper you built."
                : "The RDP helper is missing from this installation (looked for " + candidates + ").");
    }

    /** jpackage's {@code app} directory relative to its launcher, per OS layout. */
    private static List<Path> jpackageAppDirs(Path launcher) {
        Path dir = launcher.toAbsolutePath().getParent();
        if (dir == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        out.add(dir.resolve("app"));                         // Windows: <install>\jterm.exe, <install>\app
        if (dir.getParent() != null) {
            out.add(dir.getParent().resolve("lib").resolve("app")); // Linux: <install>/bin, <install>/lib/app
            out.add(dir.getParent().resolve("app"));                // macOS: Contents/MacOS, Contents/app
        }
        return out;
    }
}
