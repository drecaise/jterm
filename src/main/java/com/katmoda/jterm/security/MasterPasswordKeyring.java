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
 *       dbus-java backend can hang, so the CLI is used instead.</li>
 *   <li><b>macOS</b> — the {@code security} CLI (login Keychain).</li>
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
            case MAC -> hasCommand("security");
            case WINDOWS -> windowsKeyringAvailable();
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
                case MAC -> {
                    CommandResult r = run(null, "security", "find-generic-password",
                            "-a", ACCOUNT, "-s", SERVICE, "-w");
                    yield (r.exitCode == 0 && !r.stdout.isEmpty())
                            ? Optional.of(stripTrailingNewline(r.stdout).toCharArray()) : Optional.empty();
                }
                case WINDOWS -> {
                    try (Keyring keyring = windowsKeyring()) {
                        String value = keyring.getPassword(SERVICE, ACCOUNT);
                        yield value != null ? Optional.of(value.toCharArray()) : Optional.empty();
                    }
                }
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
                case MAC -> run(null, "security", "add-generic-password",
                        "-a", ACCOUNT, "-s", SERVICE, "-w", new String(masterPassword), "-U").exitCode == 0;
                case WINDOWS -> {
                    try (Keyring keyring = windowsKeyring()) {
                        keyring.setPassword(SERVICE, ACCOUNT, new String(masterPassword));
                        yield true;
                    }
                }
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
                case MAC -> run(null, "security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE);
                case WINDOWS -> {
                    try (Keyring keyring = windowsKeyring()) {
                        keyring.deletePassword(SERVICE, ACCOUNT);
                    }
                }
                case OTHER -> {
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    // ---- helpers ----

    private static Keyring windowsKeyring() throws Exception {
        return Keyring.create(KeyringStorageType.WINDOWS_CREDENTIAL_STORE);
    }

    private static boolean windowsKeyringAvailable() {
        try (Keyring ignored = windowsKeyring()) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

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
