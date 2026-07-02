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
package com.katmoda.jterm.terminal.ssh.agent;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OS-aware access to the local ssh-agent: resolves the endpoint, opens the right transport
 * (Unix socket vs Windows named pipe) and lists identities. Shared by the SSH connector,
 * the agent factory, and the "Agent Keys" dialog.
 */
public final class AgentSupport {

    /** Default Windows OpenSSH agent named pipe. */
    public static final String WINDOWS_PIPE = "\\\\.\\pipe\\openssh-ssh-agent";

    private static final Logger LOG = LoggerFactory.getLogger(AgentSupport.class);

    private AgentSupport() {
    }

    /** One key as reported by the agent. */
    public record AgentKey(String type, String fingerprint, String comment) {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * The agent endpoint to use (socket path or pipe), or {@code null} if none is available.
     * Prefers {@code $SSH_AUTH_SOCK}; on Windows falls back to the default pipe if present; on
     * Unix falls back to querying a login shell (for desktop-launched processes).
     *
     * <p>On Unix the socket path is verified to be a socket the current user owns with no
     * group/other write access before it is trusted (see {@link #isTrustedUnixSocket}); an
     * attacker-planted {@code $SSH_AUTH_SOCK} is logged and ignored, so auth falls through to
     * on-disk keys / password rather than signing against a rogue agent.</p>
     */
    public static String resolveEndpoint() {
        if (isWindows()) {
            String sock = System.getenv("SSH_AUTH_SOCK");
            if (sock != null && !sock.isBlank()) {
                return sock;
            }
            return canOpen(WINDOWS_PIPE) ? WINDOWS_PIPE : null;
        }
        String sock = System.getenv("SSH_AUTH_SOCK");
        if (sock != null && !sock.isBlank()) {
            if (isTrustedUnixSocket(sock)) {
                return sock;
            }
            LOG.warn("Ignoring untrusted SSH_AUTH_SOCK '{}' — not a socket owned by {} with no "
                    + "group/other write access", sock, System.getProperty("user.name"));
        }
        String shellSock = loginShellAuthSock();
        return (shellSock != null && isTrustedUnixSocket(shellSock)) ? shellSock : null;
    }

    /**
     * Whether {@code path} is a Unix-domain socket the current user owns and that is not
     * group- or world-writable — the properties a genuine ssh-agent socket has. Anything else
     * (a regular file, a symlink, a foreign owner, a writable-by-others socket, or an unreadable
     * path) is rejected so a hostile {@code $SSH_AUTH_SOCK} can't redirect key-signing.
     */
    static boolean isTrustedUnixSocket(String path) {
        try {
            Path p = Path.of(path);
            PosixFileAttributes attrs =
                    Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile() || attrs.isDirectory() || attrs.isSymbolicLink()) {
                return false; // a socket is "other"; never follow/accept these
            }
            String current = System.getProperty("user.name");
            if (current != null && !current.equals(attrs.owner().getName())) {
                return false;
            }
            Set<PosixFilePermission> perms = attrs.permissions();
            return !perms.contains(PosixFilePermission.GROUP_WRITE)
                    && !perms.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (Exception e) {
            return false; // missing, unreadable, or non-POSIX — don't trust it
        }
    }

    /** Open the agent at the preferred endpoint (e.g. a MINA property), else the resolved one. */
    public static SshAgent open(String preferredEndpoint) throws IOException {
        String endpoint = (preferredEndpoint != null && !preferredEndpoint.isBlank())
                ? preferredEndpoint : resolveEndpoint();
        if (!isWindows()) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IOException("No ssh-agent endpoint available (is the agent running?)");
            }
            if (!isTrustedUnixSocket(endpoint)) {
                throw new IOException("Refusing to use untrusted ssh-agent socket: " + endpoint);
            }
            return new JdkAgentProxy(endpoint);
        }
        return openWindows(endpoint);
    }

    /**
     * On Windows several agents can be present at once (e.g. an empty OpenSSH agent service plus
     * Pageant holding the key). Open every available source and, if there's more than one, front
     * them with a {@link CompositeSshAgent} so jterm uses whichever agent actually holds the key.
     */
    private static SshAgent openWindows(String endpoint) throws IOException {
        List<SshAgent> agents = new ArrayList<>();
        if (endpoint != null && !endpoint.isBlank()) {
            agents.add(new WindowsPipeAgentProxy(endpoint)); // OpenSSH pipe / KeeAgent / SSH_AUTH_SOCK
        }
        if (PageantAgentProxy.isPageantRunning()) {
            agents.add(new PageantAgentProxy());
        }
        if (agents.isEmpty()) {
            throw new IOException("No ssh-agent endpoint available (is the agent running?)");
        }
        return agents.size() == 1 ? agents.get(0) : new CompositeSshAgent(agents);
    }

    public static SshAgent open() throws IOException {
        return open(null);
    }

    /**
     * Whether any agent is reachable: a resolvable Unix endpoint / OpenSSH pipe, or — on Windows —
     * a running Pageant. Used to decide whether to install the agent auth factory at all.
     */
    public static boolean isAgentAvailable() {
        String endpoint = resolveEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            return true;
        }
        return isWindows() && PageantAgentProxy.isPageantRunning();
    }

    /** List the agent's identities (type, SHA-256 fingerprint, comment). */
    public static List<AgentKey> listIdentities() throws IOException {
        List<AgentKey> keys = new ArrayList<>();
        try (SshAgent agent = open()) {
            for (Map.Entry<PublicKey, String> id : agent.getIdentities()) {
                PublicKey key = id.getKey();
                String type = KeyUtils.getKeyType(key);
                keys.add(new AgentKey(
                        (type != null && !type.isBlank()) ? type : key.getAlgorithm(),
                        KeyUtils.getFingerPrint(key),
                        id.getValue()));
            }
        }
        return keys;
    }

    private static boolean canOpen(String path) {
        try (RandomAccessFile ignored = new RandomAccessFile(path, "rw")) {
            return true;
        } catch (FileNotFoundException e) {
            // "All pipe instances are busy" means the agent pipe exists but is momentarily
            // serving another client — the agent is present, so treat the endpoint as available.
            return e.getMessage() != null && e.getMessage().contains("All pipe instances are busy");
        } catch (Exception e) {
            return false;
        }
    }

    private static String loginShellAuthSock() {
        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) {
                shell = "/bin/bash";
            }
            Process p = new ProcessBuilder(shell, "-lic", "printf %s \"$SSH_AUTH_SOCK\"").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
