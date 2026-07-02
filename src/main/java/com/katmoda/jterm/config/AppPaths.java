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
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
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

    /** Root configuration directory (created on demand, restricted to the owner). */
    public static Path configDir() {
        try {
            Files.createDirectories(CONFIG_DIR);
            restrictDirToOwner(CONFIG_DIR);
        } catch (Exception ignored) {
            // Surface lazily when an individual file write fails.
        }
        return CONFIG_DIR;
    }

    /** A file inside the config directory. */
    public static Path file(String name) {
        return configDir().resolve(name);
    }

    /**
     * Restrict {@code file} to owner-only access (POSIX 0600). Config files can hold hostnames,
     * usernames and (for credentials/encrypted exports) secrets, so they shouldn't be group- or
     * world-readable. A no-op on non-POSIX filesystems (e.g. Windows), where the user profile's
     * ACLs already scope the config directory.
     */
    public static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem — rely on the user profile's ACLs.
        }
    }

    /**
     * Restrict {@code dir} to owner-only access (POSIX 0700). Applied to the config directory so
     * that even config files written before per-file hardening — or by external tooling — can't be
     * read by other local users, who can no longer traverse into the directory at all.
     */
    public static void restrictDirToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem — rely on the user profile's ACLs.
        }
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
