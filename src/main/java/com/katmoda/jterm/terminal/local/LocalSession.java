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

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.terminal.TerminalSession;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local shell running in a pseudo-terminal via pty4j. Created by keyboard
 * splits and the sidebar's "Local Terminal" entry.
 */
public final class LocalSession implements TerminalSession {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSession.class);

    private final PtyProcess process;
    private final PtyTtyConnector connector;
    private final String title;
    private final TerminalProfile profile;
    private final String iconId;

    private LocalSession(PtyProcess process, String title, TerminalProfile profile, String iconId) {
        this.process = process;
        this.profile = profile;
        this.connector = new PtyTtyConnector(process, title, profile.charset());
        this.title = title;
        this.iconId = iconId;
    }

    /** Icon-library id for this session's tab/pane, or {@code null} to use the default local glyph. */
    @Override
    public String iconId() {
        return iconId;
    }

    /** Starts a login/interactive shell in {@code workingDir} (or the user's home if null). */
    public static LocalSession start(Path workingDir) throws IOException {
        String dir = (workingDir != null ? workingDir : Path.of(System.getProperty("user.home", "."))).toString();

        TerminalProfile profile = AppSettings.get().defaultProfile();

        // Under Snap the runtime's own plumbing (LD_LIBRARY_PATH, snap-prefixed PATH, SNAP_*)
        // is in our environment and must not follow the user's shell out; no-op elsewhere.
        Map<String, String> env = new HashMap<>(SnapEnvironment.sanitize(System.getenv()));
        env.put("TERM", profile.terminalType());
        env.putIfAbsent("TERM_PROGRAM", "jterm");
        seedWindowsPrompt(env, isWindows());

        // Inside a Flatpak sandbox a directly-spawned shell runs against the runtime's
        // minimal filesystem (host files appear "missing", system shell config is wrong),
        // so escape to the host — see FlatpakHost for why that needs more than flatpak-spawn.
        String[] command = FlatpakHost.isFlatpak()
                ? FlatpakHost.shellCommand(profile, dir).toArray(new String[0])
                : defaultShellCommand();

        PtyProcess process = new PtyProcessBuilder(command)
                .setEnvironment(env)
                .setDirectory(dir)
                .setInitialColumns(80)
                .setInitialRows(24)
                // Windows only: use ConPTY, not pty4j's default legacy WinPTY backend. WinPTY
                // scrapes a single console screen buffer and can't represent the alternate
                // screen, so full-screen apps (vim/less/htop) render into the primary buffer
                // and leak into scrollback. ConPTY forwards ?1049h/l so JediTerm switches
                // buffers correctly; pty4j auto-falls-back to WinPTY on unsupported Windows.
                // No-op on Unix (native pty already passes these sequences through).
                .setUseWinConPty(true)
                .start();

        String label = (workingDir != null) ? lastSegment(workingDir) : "local";
        return new LocalSession(process, label, profile, null);
    }

    /** Starts a shell inside the given WSL2 distribution via {@code wsl.exe -d <distro>}. */
    public static LocalSession startWsl(String distro) throws IOException {
        TerminalProfile profile = AppSettings.get().defaultProfile();

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", profile.terminalType());
        env.putIfAbsent("TERM_PROGRAM", "jterm");

        // --cd ~ starts in the distro's Linux home; without it WSL inherits the (Windows)
        // working directory and lands the user under /mnt/c/... instead.
        PtyProcess process = new PtyProcessBuilder(new String[]{"wsl.exe", "-d", distro, "--cd", "~"})
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.home", "."))
                .setInitialColumns(80)
                .setInitialRows(24)
                // Use ConPTY so the WSL shell's alternate-screen apps (vim/less/htop) don't
                // leak into scrollback. See start() for the full rationale.
                .setUseWinConPty(true)
                .start();

        return new LocalSession(process, distro, profile, "builtin/wsl");
    }

    /**
     * The prompt string that makes {@code cmd.exe} report its directory, using OSC 9;9 (the Windows
     * Terminal convention). {@code $e} is an escape, {@code $p} the current path, {@code $g} a
     * {@code >} — so the visible prompt is unchanged from cmd's default.
     */
    static final String WINDOWS_CWD_PROMPT = "$e]9;9;$p$e\\$p$g";

    /**
     * Windows has no readable equivalent of {@code /proc/<pid>/cwd}, so a local {@code cmd.exe} can
     * only report its directory if its prompt is made to. {@code cmd} reads {@code %PROMPT%} at
     * startup, which lets jterm arrange that for the shells it launches without typing anything into
     * the terminal and without affecting any other shell.
     *
     * <p>A prompt the user has set themselves is left strictly alone. Note the environment on
     * Windows is case-insensitive while this {@link Map} is not, so every key has to be checked —
     * {@code containsKey("PROMPT")} alone would miss a {@code Prompt} inherited from the OS.</p>
     */
    static void seedWindowsPrompt(Map<String, String> env, boolean windows) {
        if (!windows) {
            return;
        }
        for (String key : env.keySet()) {
            if (key.equalsIgnoreCase("PROMPT")) {
                return;
            }
        }
        env.put("PROMPT", WINDOWS_CWD_PROMPT);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String[] defaultShellCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String comspec = System.getenv("COMSPEC");
            return new String[]{comspec != null && !comspec.isBlank() ? comspec : "cmd.exe"};
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/bash";
        }
        // Login shell so the user's normal environment is loaded.
        return new String[]{shell, "-l"};
    }

    private static String lastSegment(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : p.toString();
    }

    @Override
    public TtyConnector connector() {
        return connector;
    }

    @Override
    public TerminalProfile profile() {
        return profile;
    }

    @Override
    public String title() {
        return title;
    }

    /**
     * Live working directory of the shell (Linux /proc), or null when it cannot be read — on
     * Windows, on macOS, and deliberately inside a Flatpak sandbox. Returned raw; {@link
     * #shortenHome} is applied where it is displayed.
     *
     * <p>Under Flatpak the process pty4j spawned is {@code flatpak-spawn} on the sandbox side, whose
     * own directory is fixed at launch and has nothing to do with the shell, and the sandbox cannot
     * see the host's {@code /proc}. Reporting that would be a plausible-looking wrong path, which is
     * worse than reporting none. The real shell runs on the host under {@code pty-agent.py}, which
     * knows its pid and reports the directory by emitting OSC 7 into the relay — so a sandboxed
     * session learns it the same way a remote one does. See {@link FlatpakHost}.</p>
     */
    @Override
    public String workingDirectory() {
        if (FlatpakHost.isFlatpak()) {
            return null;
        }
        try {
            Path link = Path.of("/proc", Long.toString(process.pid()), "cwd");
            if (Files.exists(link)) {
                return Files.readSymbolicLink(link).toString();
            }
        } catch (Exception e) {
            LOG.debug("could not read working directory from /proc", e);
        }
        return null;
    }

    /**
     * Displays a local shell's own home directory as {@code ~}. Applied wherever a local
     * directory is shown, so it reads the same whether the path came from {@code /proc} (native
     * build) or from OSC 7 (inside the Flatpak, where the host agent reports an absolute path).
     *
     * <p>Only for POSIX homes: on Windows {@code C:\Users\name} is what users expect to see. The
     * match is on a whole path component, so {@code /home/markus} is not mangled into {@code ~us}.</p>
     */
    public static String shortenHome(String path) {
        String home = System.getProperty("user.home");
        if (path == null || home == null || home.isBlank() || !home.startsWith("/")) {
            return path;
        }
        if (path.equals(home)) {
            return "~";
        }
        return path.startsWith(home + "/") ? "~" + path.substring(home.length()) : path;
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.destroy();
    }
}
