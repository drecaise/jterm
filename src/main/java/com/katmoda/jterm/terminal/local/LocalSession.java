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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A local shell running in a pseudo-terminal via pty4j. Created by keyboard
 * splits and the sidebar's "Local Terminal" entry.
 */
public final class LocalSession implements TerminalSession {

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
    public String iconId() {
        return iconId;
    }

    /** Starts a login/interactive shell in {@code workingDir} (or the user's home if null). */
    public static LocalSession start(Path workingDir) throws IOException {
        String dir = (workingDir != null ? workingDir : Path.of(System.getProperty("user.home", "."))).toString();

        TerminalProfile profile = AppSettings.get().defaultProfile();

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", profile.terminalType());
        env.putIfAbsent("TERM_PROGRAM", "jterm");

        // Inside a Flatpak sandbox a directly-spawned shell runs against the runtime's
        // minimal filesystem (host files appear "missing", system shell config is wrong),
        // so escape to the host via flatpak-spawn --host.
        String[] command = isFlatpak() ? flatpakHostShellCommand(profile, dir) : defaultShellCommand();

        PtyProcess process = new PtyProcessBuilder(command)
                .setEnvironment(env)
                .setDirectory(dir)
                .setInitialColumns(80)
                .setInitialRows(24)
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
                .start();

        return new LocalSession(process, distro, profile, "builtin/wsl");
    }

    /** True when running inside a Flatpak sandbox. */
    private static boolean isFlatpak() {
        String id = System.getenv("FLATPAK_ID");
        return (id != null && !id.isBlank()) || Files.exists(Path.of("/.flatpak-info"));
    }

    /**
     * Builds a command that runs the user's real <em>host</em> login shell via
     * {@code flatpak-spawn --host}. The host environment is cleared and rebuilt by the
     * login shell ({@code -l}); only the essentials are seeded so the sandbox's PATH/SHELL
     * don't leak through. {@code $HOME} is shared with the host, so the path maps 1:1.
     */
    private static String[] flatpakHostShellCommand(TerminalProfile profile, String dir) {
        String shell = hostLoginShell();
        String home = System.getProperty("user.home", "/");
        String user = System.getProperty("user.name", "");

        List<String> cmd = new ArrayList<>();
        cmd.add("flatpak-spawn");
        cmd.add("--host");
        cmd.add("--clear-env");
        cmd.add("--directory=" + dir);
        cmd.add("--env=TERM=" + profile.terminalType());
        cmd.add("--env=TERM_PROGRAM=jterm");
        cmd.add("--env=HOME=" + home);
        if (!user.isBlank()) {
            cmd.add("--env=USER=" + user);
            cmd.add("--env=LOGNAME=" + user);
        }
        cmd.add("--env=SHELL=" + shell);
        // Pass through display/session vars (if present) so GUI apps launched from the
        // terminal work. With --socket=x11/wayland the sandbox values match the host's.
        for (String key : new String[]{"DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                cmd.add("--env=" + key + "=" + value);
            }
        }
        cmd.add("--");
        cmd.add(shell);
        cmd.add("-l");
        return cmd.toArray(new String[0]);
    }

    /**
     * Resolves the host user's login shell from the host passwd database (the sandbox's
     * {@code $SHELL} is the runtime's, not the host's). Falls back to {@code /bin/bash}.
     */
    private static String hostLoginShell() {
        String user = System.getProperty("user.name");
        if (user != null && !user.isBlank()) {
            try {
                Process p = new ProcessBuilder("flatpak-spawn", "--host", "getent", "passwd", user)
                        .redirectErrorStream(false)
                        .start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(3, TimeUnit.SECONDS);
                // passwd line: name:passwd:uid:gid:gecos:home:shell
                int nl = out.indexOf('\n');
                String line = (nl >= 0 ? out.substring(0, nl) : out).trim();
                int idx = line.lastIndexOf(':');
                if (idx >= 0 && idx < line.length() - 1) {
                    String shell = line.substring(idx + 1).trim();
                    if (!shell.isBlank()) {
                        return shell;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "/bin/bash";
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
        String cwd = readWorkingDirectory();
        return cwd != null ? cwd : title;
    }

    /** Live working directory of the shell (Linux /proc), with $HOME shown as {@code ~}. */
    private String readWorkingDirectory() {
        try {
            Path link = Path.of("/proc", Long.toString(process.pid()), "cwd");
            if (Files.exists(link)) {
                String path = Files.readSymbolicLink(link).toString();
                String home = System.getProperty("user.home");
                if (home != null && path.startsWith(home)) {
                    path = "~" + path.substring(home.length());
                }
                return path;
            }
        } catch (Exception ignored) {
        }
        return null;
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
