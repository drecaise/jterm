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

import com.katmoda.jterm.terminal.TerminalProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Escapes the Flatpak sandbox so a local terminal runs the user's real host shell.
 *
 * <p>A directly-spawned shell would run against the runtime's minimal filesystem, so the
 * shell is launched on the host via {@code flatpak-spawn --host}. That alone is not enough:
 * pty4j allocates the PTY <em>inside</em> the sandbox and the terminal semantics of that PTY
 * do not cross the boundary — the host shell cannot claim a controlling terminal owned by a
 * sandbox-side session ({@code cannot set terminal process group} / {@code no job control}),
 * {@code ttyname()} fails because the sandbox has its own devpts instance
 * ({@code tty: ttyname error: No such device}), and SIGWINCH is never forwarded.
 *
 * <p>So a small host-side agent ({@code /flatpak/pty-agent.py}) allocates a second PTY on the
 * host, runs the login shell in it, and relays bytes over the sandbox PTY — which is demoted
 * to plain transport plus the window-size channel. See the agent for the details.
 */
final class FlatpakHost {

    private static final Logger LOG = LoggerFactory.getLogger(FlatpakHost.class);

    /** Marker prefix so the probe's own output can be told apart from a login shell's MOTD. */
    private static final String MARK = "JTERM_PROBE_";

    private static final String AGENT_RESOURCE = "/flatpak/pty-agent.py";

    /** Env var carrying the base64 agent source; the agent unsets it before exec'ing the shell. */
    private static final String AGENT_ENV = "JTERM_PTY_AGENT_B64";

    /** Session vars worth passing through so GUI apps launched from the terminal work. */
    private static final String[] PASSTHROUGH = {"DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR"};

    private static volatile HostProbe cachedProbe;
    private static volatile String cachedAgentSource;
    private static boolean agentSourceLoaded;

    private FlatpakHost() {
    }

    /**
     * What the host looks like, gathered in one round trip. Any field may be {@code null}
     * except {@code loginShell}, which falls back to {@code /bin/bash}.
     */
    record HostProbe(String loginShell, String python3, String script, String sshAuthSock) {
    }

    /** True when running inside a Flatpak sandbox. */
    static boolean isFlatpak() {
        String id = System.getenv("FLATPAK_ID");
        return (id != null && !id.isBlank()) || Files.exists(Path.of("/.flatpak-info"));
    }

    /** Command line for a login shell on the host, using the best PTY strategy available. */
    static List<String> shellCommand(TerminalProfile profile, String dir) {
        return shellCommand(profile, dir,
                System.getProperty("user.home", "/"),
                System.getProperty("user.name", ""),
                probe(),
                agentSource(),
                System.getenv());
    }

    /**
     * Pure command builder, split out so the argv shape is unit-testable.
     *
     * <p>The host environment is cleared and rebuilt by the login shell ({@code -l}); only the
     * essentials are seeded so the sandbox's PATH/SHELL don't leak through. {@code $HOME} is
     * shared with the host, so the path maps 1:1.
     */
    static List<String> shellCommand(TerminalProfile profile,
                                     String dir,
                                     String home,
                                     String user,
                                     HostProbe probe,
                                     String agentSource,
                                     Map<String, String> sandboxEnv) {
        String shell = probe.loginShell() != null && !probe.loginShell().isBlank()
                ? probe.loginShell() : "/bin/bash";

        List<String> cmd = new ArrayList<>();
        cmd.add("flatpak-spawn");
        cmd.add("--host");
        cmd.add("--clear-env");
        cmd.add("--directory=" + dir);
        cmd.add("--env=TERM=" + profile.terminalType());
        cmd.add("--env=TERM_PROGRAM=jterm");
        cmd.add("--env=HOME=" + home);
        if (user != null && !user.isBlank()) {
            cmd.add("--env=USER=" + user);
            cmd.add("--env=LOGNAME=" + user);
        }
        cmd.add("--env=SHELL=" + shell);
        // Never forward the sandbox's own SSH_AUTH_SOCK: with --socket=ssh-auth it points at
        // /run/flatpak/ssh-auth, a path that does not exist on the host. Setting a dead path
        // would be worse than setting nothing, because rc files commonly repair the variable
        // only when it is empty. probe() supplies the host's real socket, or null.
        if (probe.sshAuthSock() != null && !probe.sshAuthSock().isBlank()) {
            cmd.add("--env=SSH_AUTH_SOCK=" + probe.sshAuthSock());
        }
        for (String key : PASSTHROUGH) {
            String value = sandboxEnv.get(key);
            if (value != null && !value.isBlank()) {
                cmd.add("--env=" + key + "=" + value);
            }
        }

        boolean useAgent = agentSource != null && !agentSource.isBlank()
                && probe.python3() != null && !probe.python3().isBlank();
        if (useAgent) {
            String blob = Base64.getEncoder().encodeToString(agentSource.getBytes(StandardCharsets.UTF_8));
            cmd.add("--env=" + AGENT_ENV + "=" + blob);
            cmd.add("--");
            cmd.add(probe.python3());
            cmd.add("-c");
            // os.environ.pop really unsets it, so the user's shell never sees the blob and the
            // agent's own argv stays just [shell, "-l"].
            cmd.add("import base64,os;exec(base64.b64decode(os.environ.pop('" + AGENT_ENV + "')))");
            cmd.add(shell);
            cmd.add("-l");
            return cmd;
        }

        if (probe.script() != null && !probe.script().isBlank()) {
            // Fallback: script(1) also allocates a host PTY with a proper session, so job
            // control and ttyname work — but it cannot see SIGWINCH either, so the window
            // size stays frozen at whatever it was when the shell started.
            LOG.warn("no python3 on the host; falling back to script(1) — terminal resize will not "
                    + "reach the shell. Install python3 on the host for full support.");
            cmd.add("--");
            cmd.add(probe.script());
            cmd.add("-q");
            cmd.add("-e");
            cmd.add("-f");
            cmd.add("-c");
            cmd.add("exec " + shell + " -l");
            cmd.add("/dev/null");
            return cmd;
        }

        LOG.warn("no python3 or script(1) on the host; the shell will report 'no job control' and "
                + "'ttyname error'. Install python3 on the host to fix this.");
        cmd.add("--");
        cmd.add(shell);
        cmd.add("-l");
        return cmd;
    }

    /**
     * Everything we need to know about the host, in one {@code flatpak-spawn} round trip.
     *
     * <p>Cached for the lifetime of the process: this used to run per local-session start, and
     * a login shell on a slow host made every new tab pay for it.
     */
    static HostProbe probe() {
        HostProbe probe = cachedProbe;
        if (probe == null) {
            synchronized (FlatpakHost.class) {
                probe = cachedProbe;
                if (probe == null) {
                    probe = runProbe();
                    cachedProbe = probe;
                }
            }
        }
        return probe;
    }

    private static HostProbe runProbe() {
        String user = System.getProperty("user.name", "");
        String home = System.getProperty("user.home", "/");
        List<String> cmd = new ArrayList<>(List.of(
                "flatpak-spawn", "--host", "--clear-env",
                "--env=HOME=" + home,
                "--env=USER=" + user,
                // The probe needs a usable PATH before the login shell has built one.
                "--env=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
        // `systemctl --user` can't find the user manager without these, and without it the
        // SSH_AUTH_SOCK lookup silently returns nothing. Both paths are identical either side
        // of the sandbox boundary, so the sandbox's values are the host's.
        for (String key : new String[]{"XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                cmd.add("--env=" + key + "=" + value);
            }
        }
        cmd.addAll(List.of("--", "sh", "-lc", probeScript()));

        String out = runCapturing(cmd, 5);
        if (out == null) {
            LOG.debug("host probe failed; assuming a bare /bin/bash host shell");
            return new HostProbe("/bin/bash", null, null, null);
        }

        String shell = field(out, "SHELL");
        return new HostProbe(
                shell != null && !shell.isBlank() ? shell : "/bin/bash",
                field(out, "PYTHON"),
                field(out, "SCRIPT"),
                field(out, "SSH_AUTH_SOCK"));
    }

    private static String probeScript() {
        // A login shell may print a MOTD, so every answer is marker-prefixed and parsed by name.
        return "printf '" + MARK + "SHELL=%s\\n' "
                + "\"$(getent passwd \"$USER\" 2>/dev/null | head -n1 | sed -n 's/.*:\\([^:]*\\)$/\\1/p')\"; "
                + "printf '" + MARK + "PYTHON=%s\\n' \"$(command -v python3 2>/dev/null)\"; "
                + "printf '" + MARK + "SCRIPT=%s\\n' \"$(command -v script 2>/dev/null)\"; "
                // systemd's user manager holds the graphical session's env, which is where
                // gnome-keyring/ssh-agent publish the socket the host actually uses.
                + "s=$(systemctl --user show-environment 2>/dev/null "
                + "| sed -n 's/^SSH_AUTH_SOCK=//p' | head -n1); "
                + "[ -S \"$s\" ] || s=; "
                + "printf '" + MARK + "SSH_AUTH_SOCK=%s\\n' \"$s\"";
    }

    private static String field(String out, String name) {
        String prefix = MARK + name + "=";
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** Runs a command with a bounded wait, returning stdout or {@code null} on any failure. */
    private static String runCapturing(List<String> cmd, int timeoutSeconds) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Drain stdout on a daemon thread so readAllBytes() can't outlive the waitFor timeout.
            byte[][] outHolder = new byte[1][];
            Thread reader = new Thread(() -> {
                try {
                    outHolder[0] = p.getInputStream().readAllBytes();
                } catch (IOException e) {
                    LOG.debug("failed to drain host probe stdout", e);
                }
            }, "flatpak-host-probe-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(500); // short bound so the reader thread can't leak
                return null;
            }
            reader.join(500); // stdout is closed by now; bound guards a truly stuck read
            return new String(outHolder[0] != null ? outHolder[0] : new byte[0], StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debug("host probe command failed: {}", cmd, e);
            return null;
        }
    }

    /** The agent's source, read once from the jar. {@code null} if it somehow isn't there. */
    static String agentSource() {
        if (!agentSourceLoaded) {
            synchronized (FlatpakHost.class) {
                if (!agentSourceLoaded) {
                    cachedAgentSource = loadAgentSource();
                    agentSourceLoaded = true;
                }
            }
        }
        return cachedAgentSource;
    }

    private static String loadAgentSource() {
        try (InputStream in = FlatpakHost.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) {
                LOG.warn("{} missing from the classpath; local terminals will lose job control", AGENT_RESOURCE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("could not read {}", AGENT_RESOURCE, e);
            return null;
        }
    }
}
