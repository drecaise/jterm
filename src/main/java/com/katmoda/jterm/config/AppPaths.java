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
