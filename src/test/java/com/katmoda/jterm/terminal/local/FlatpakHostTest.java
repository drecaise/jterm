/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.local;

import com.katmoda.jterm.terminal.TerminalProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code flatpak-spawn} command line: which PTY strategy each host supports, and the
 * SSH_AUTH_SOCK rule that keeps a sandbox-only socket path out of the host shell.
 */
class FlatpakHostTest {

    private static final TerminalProfile PROFILE = TerminalProfile.DEFAULT;
    private static final String AGENT = "print('agent')\n";
    private static final Map<String, String> SANDBOX_ENV = Map.of(
            "DISPLAY", ":0",
            "WAYLAND_DISPLAY", "wayland-0",
            // The sandbox's own agent socket: must never reach the host shell.
            "SSH_AUTH_SOCK", "/run/flatpak/ssh-auth",
            "PATH", "/app/bin:/usr/bin");

    private static List<String> build(FlatpakHost.HostProbe probe, String agent) {
        return FlatpakHost.shellCommand(PROFILE, "/home/u/work", "/home/u", "u", probe, agent, SANDBOX_ENV);
    }

    /** Everything after the {@code --} separator, i.e. what actually runs on the host. */
    private static List<String> hostArgv(List<String> cmd) {
        return cmd.subList(cmd.indexOf("--") + 1, cmd.size());
    }

    private static String envArg(List<String> cmd, String name) {
        return cmd.stream()
                .filter(a -> a.startsWith("--env=" + name + "="))
                .map(a -> a.substring(("--env=" + name + "=").length()))
                .findFirst().orElse(null);
    }

    @Test
    void usesTheHostPtyAgentWhenPython3IsAvailable() {
        FlatpakHost.HostProbe probe =
                new FlatpakHost.HostProbe("/usr/bin/zsh", "/usr/bin/python3", "/usr/bin/script", null);

        List<String> cmd = build(probe, AGENT);

        assertEquals(List.of("flatpak-spawn", "--host", "--clear-env", "--directory=/home/u/work"),
                cmd.subList(0, 4));
        assertEquals("/usr/bin/zsh", envArg(cmd, "SHELL"));
        assertEquals("xterm-256color", envArg(cmd, "TERM"));
        assertEquals("jterm", envArg(cmd, "TERM_PROGRAM"));
        assertEquals("/home/u", envArg(cmd, "HOME"));
        assertEquals("u", envArg(cmd, "USER"));
        assertEquals("u", envArg(cmd, "LOGNAME"));

        List<String> host = hostArgv(cmd);
        assertEquals("/usr/bin/python3", host.get(0));
        assertEquals("-c", host.get(1));
        // The shell is the agent's argv, so it sees exactly [shell, "-l"].
        assertEquals(List.of("/usr/bin/zsh", "-l"), host.subList(3, host.size()));

        // The agent source rides in an env var the bootstrap pops, so the shell never sees it.
        String blob = envArg(cmd, "JTERM_PTY_AGENT_B64");
        assertNotNull(blob);
        assertEquals(AGENT, new String(Base64.getDecoder().decode(blob), StandardCharsets.UTF_8));
        assertTrue(host.get(2).contains("JTERM_PTY_AGENT_B64"), "bootstrap should pop the env var");
        assertFalse(host.contains(blob), "the blob belongs in the environment, not the host argv");
    }

    @Test
    void fallsBackToScriptWhenTheHostHasNoPython3() {
        FlatpakHost.HostProbe probe =
                new FlatpakHost.HostProbe("/bin/bash", null, "/usr/bin/script", null);

        List<String> cmd = build(probe, AGENT);

        assertEquals(List.of("/usr/bin/script", "-q", "-e", "-f", "-c", "exec /bin/bash -l", "/dev/null"),
                hostArgv(cmd));
        assertNull(envArg(cmd, "JTERM_PTY_AGENT_B64"));
    }

    @Test
    void fallsBackToABareShellWhenTheHostHasNeither() {
        FlatpakHost.HostProbe probe = new FlatpakHost.HostProbe("/bin/bash", null, null, null);

        assertEquals(List.of("/bin/bash", "-l"), hostArgv(build(probe, AGENT)));
    }

    @Test
    void fallsBackWhenTheAgentResourceIsMissing() {
        FlatpakHost.HostProbe probe =
                new FlatpakHost.HostProbe("/bin/bash", "/usr/bin/python3", "/usr/bin/script", null);

        assertEquals("/usr/bin/script", hostArgv(build(probe, null)).get(0));
    }

    @Test
    void defaultsToBinBashWhenTheProbeFoundNoLoginShell() {
        FlatpakHost.HostProbe probe = new FlatpakHost.HostProbe(null, null, null, null);

        assertEquals("/bin/bash", envArg(build(probe, AGENT), "SHELL"));
    }

    /**
     * The sandbox's SSH_AUTH_SOCK is /run/flatpak/ssh-auth, which does not exist on the host.
     * Leaving the variable unset lets rc files that repair it when empty do their job; setting
     * a dead path would defeat them.
     */
    @Test
    void neverForwardsTheSandboxSshAuthSock() {
        FlatpakHost.HostProbe probe =
                new FlatpakHost.HostProbe("/bin/bash", "/usr/bin/python3", null, null);

        List<String> cmd = build(probe, AGENT);

        assertNull(envArg(cmd, "SSH_AUTH_SOCK"));
        assertFalse(cmd.stream().anyMatch(a -> a.contains("/run/flatpak/ssh-auth")));
    }

    @Test
    void forwardsTheHostSshAuthSockWhenTheProbeResolvedOne() {
        FlatpakHost.HostProbe probe = new FlatpakHost.HostProbe(
                "/bin/bash", "/usr/bin/python3", null, "/run/user/1000/keyring/ssh");

        assertEquals("/run/user/1000/keyring/ssh", envArg(build(probe, AGENT), "SSH_AUTH_SOCK"));
    }

    @Test
    void passesThroughDisplayVarsButNotTheSandboxPath() {
        FlatpakHost.HostProbe probe =
                new FlatpakHost.HostProbe("/bin/bash", "/usr/bin/python3", null, null);

        List<String> cmd = build(probe, AGENT);

        assertEquals(":0", envArg(cmd, "DISPLAY"));
        assertEquals("wayland-0", envArg(cmd, "WAYLAND_DISPLAY"));
        // --clear-env plus a login shell is the whole point: the runtime's PATH must not leak.
        assertNull(envArg(cmd, "PATH"));
    }
}
