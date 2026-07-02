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
package com.katmoda.jterm.security;

import com.github.javakeyring.Keyring;
import com.github.javakeyring.KeyringStorageType;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores the vault's master password in the OS keyring so it isn't prompted on every launch.
 *
 * <p>Per-OS backend, chosen for reliability:</p>
 * <ul>
 *   <li><b>Linux</b> — {@code secret-tool} (libsecret / Secret Service). java-keyring's
 *       dbus-java backend can hang, so the CLI is used instead. The master password is passed on
 *       stdin, never as a command-line argument.</li>
 *   <li><b>macOS</b> — java-keyring's Keychain backend (JNA → {@code Security.framework}). The
 *       {@code security} CLI is deliberately avoided because it takes the password as an
 *       {@code argv} element, which is visible in the process list to other local users.</li>
 *   <li><b>Windows</b> — java-keyring's Credential Store backend (JNA, no dbus).</li>
 * </ul>
 *
 * <p>Every operation is best-effort and time-bounded: any failure (missing tool, no keyring,
 * timeout) reports "unavailable" so the caller falls back to prompting for the master
 * password.</p>
 */
public final class MasterPasswordKeyring {

    private static final String SERVICE = "jterm";
    private static final String ACCOUNT = "master-password";
    private static final long TIMEOUT_SECONDS = 5;

    private enum Os { LINUX, MAC, WINDOWS, OTHER }

    private final Os os = detectOs();

    public boolean isAvailable() {
        return switch (os) {
            case LINUX -> hasCommand("secret-tool");
            case MAC -> nativeKeyringAvailable(KeyringStorageType.OSX_KEYCHAIN);
            case WINDOWS -> nativeKeyringAvailable(KeyringStorageType.WINDOWS_CREDENTIAL_STORE);
            case OTHER -> false;
        };
    }

    public Optional<char[]> retrieve() {
        try {
            return switch (os) {
                case LINUX -> {
                    CommandResult r = run(null, "secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT);
                    yield (r.exitCode == 0 && !r.stdout.isEmpty())
                            ? Optional.of(stripTrailingNewline(r.stdout).toCharArray()) : Optional.empty();
                }
                case MAC -> nativeRetrieve(KeyringStorageType.OSX_KEYCHAIN);
                case WINDOWS -> nativeRetrieve(KeyringStorageType.WINDOWS_CREDENTIAL_STORE);
                case OTHER -> Optional.empty();
            };
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public boolean store(char[] masterPassword) {
        try {
            return switch (os) {
                case LINUX -> run(new String(masterPassword), "secret-tool", "store",
                        "--label=jterm master password", "service", SERVICE, "account", ACCOUNT).exitCode == 0;
                case MAC -> nativeStore(KeyringStorageType.OSX_KEYCHAIN, masterPassword);
                case WINDOWS -> nativeStore(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, masterPassword);
                case OTHER -> false;
            };
        } catch (Throwable t) {
            return false;
        }
    }

    public void clear() {
        try {
            switch (os) {
                case LINUX -> run(null, "secret-tool", "clear", "service", SERVICE, "account", ACCOUNT);
                case MAC -> nativeClear(KeyringStorageType.OSX_KEYCHAIN);
                case WINDOWS -> nativeClear(KeyringStorageType.WINDOWS_CREDENTIAL_STORE);
                case OTHER -> {
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    // ---- native (java-keyring / JNA) backends: macOS Keychain and Windows Credential Store ----

    private static Optional<char[]> nativeRetrieve(KeyringStorageType type) {
        try (Keyring keyring = Keyring.create(type)) {
            String value = keyring.getPassword(SERVICE, ACCOUNT);
            return value != null ? Optional.of(value.toCharArray()) : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static boolean nativeStore(KeyringStorageType type, char[] masterPassword) {
        try (Keyring keyring = Keyring.create(type)) {
            keyring.setPassword(SERVICE, ACCOUNT, new String(masterPassword));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void nativeClear(KeyringStorageType type) {
        try (Keyring keyring = Keyring.create(type)) {
            keyring.deletePassword(SERVICE, ACCOUNT);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static boolean nativeKeyringAvailable(KeyringStorageType type) {
        try (Keyring ignored = Keyring.create(type)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- helpers ----

    private record CommandResult(int exitCode, String stdout) {
    }

    /** Run a command with an optional stdin string, time-bounded; never throws on failure. */
    private static CommandResult run(String stdin, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            if (stdin != null) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] outBytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, "");
            }
            return new CommandResult(process.exitValue(), new String(outBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new CommandResult(-1, "");
        }
    }

    private static boolean hasCommand(String name) {
        return run(null, "sh", "-c", "command -v " + name).exitCode == 0;
    }

    private static String stripTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MAC;
        }
        if (name.contains("nux") || name.contains("nix")) {
            return Os.LINUX;
        }
        return Os.OTHER;
    }
}
