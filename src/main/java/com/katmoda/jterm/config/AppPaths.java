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
package com.katmoda.jterm.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves the per-OS configuration directory used to persist sessions,
 * the icon library, the keymap and general app config.
 *
 * <ul>
 *   <li>Linux:   {@code $XDG_CONFIG_HOME/jterm} or {@code ~/.config/jterm}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/jterm}</li>
 *   <li>Windows: {@code %APPDATA%\jterm}</li>
 * </ul>
 */
public final class AppPaths {

    private static final Path CONFIG_DIR = resolveConfigDir();

    private AppPaths() {
    }

    /** Root configuration directory (created on demand). */
    public static Path configDir() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (Exception ignored) {
            // Surface lazily when an individual file write fails.
        }
        return CONFIG_DIR;
    }

    /** A file inside the config directory. */
    public static Path file(String name) {
        return configDir().resolve(name);
    }

    /** Directory where imported custom icons are copied. */
    public static Path iconsDir() {
        Path dir = configDir().resolve("icons");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    private static Path resolveConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Path.of(appData) : Path.of(home);
            return base.resolve("jterm");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", "jterm");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".config");
        return base.resolve("jterm");
    }
}
