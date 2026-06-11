package com.katmoda.jterm.terminal.wsl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Enumerates installed WSL2 distributions on Windows by parsing {@code wsl.exe --list --verbose}.
 *
 * <p>The command's output is encoded UTF-16LE (a long-standing WSL quirk) and laid out as a
 * fixed header row followed by one row per distribution: an optional {@code *} default-distro
 * marker, the NAME, a STATE, and a VERSION. We keep only the names whose VERSION is {@code 2}.</p>
 *
 * <p>Everything is defensive: on any non-Windows platform, missing {@code wsl.exe}, timeout, or
 * parse failure this returns an empty list, so callers can treat "no WSL" and "not Windows"
 * identically.</p>
 */
public final class WslDistributions {

    /** Internal helper distros that aren't useful interactive shells. */
    private static final Set<String> HIDDEN = Set.of("docker-desktop", "docker-desktop-data");

    private WslDistributions() {
    }

    /** Names of installed WSL2 (VERSION 2) distributions, or an empty list when unavailable. */
    public static List<String> listVersion2() {
        if (!isWindows()) {
            return List.of();
        }
        try {
            Process process = new ProcessBuilder("wsl.exe", "--list", "--verbose")
                    .redirectErrorStream(false)
                    .start();
            byte[] bytes;
            try (InputStream in = process.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            return parse(new String(bytes, StandardCharsets.UTF_16LE));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Parses {@code wsl --list --verbose} output; package-visible for testing. */
    static List<String> parse(String output) {
        List<String> distros = new ArrayList<>();
        boolean header = true;
        for (String raw : output.split("\\r?\\n")) {
            // Drop the default-distro marker and any stray control/BOM chars, keeping spaces,
            // which separate the NAME/STATE/VERSION columns.
            String line = raw.replace('*', ' ').replaceAll("[\\p{Cntrl}\\uFEFF]", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (header) {
                header = false; // the first non-empty line is the column header
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length < 2) {
                continue;
            }
            String name = cols[0];
            String version = cols[cols.length - 1];
            if ("2".equals(version) && !HIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
                distros.add(name);
            }
        }
        return distros;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
