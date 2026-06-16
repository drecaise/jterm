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

        PtyProcess process = new PtyProcessBuilder(defaultShellCommand())
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
