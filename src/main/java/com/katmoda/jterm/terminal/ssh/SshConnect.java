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
package com.katmoda.jterm.terminal.ssh;

import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.terminal.ssh.agent.JdkAgentFactory;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Establishes an authenticated SSH connection (client + session) without opening any channel.
 *
 * <p>Both the interactive shell ({@link SshSession}) and the SFTP browser build on this: the
 * shell adds a {@code ChannelShell}, SFTP adds an SFTP subsystem channel. Keeping the
 * connect+auth here (rather than inline in {@link SshSession}) lets SFTP open a fresh dedicated
 * connection without also spawning an unused remote shell.</p>
 *
 * <p>This class deliberately depends only on {@code sshd-core} so it stays cheap to load; the
 * SFTP-specific {@code sshd-sftp} classes are referenced only from the on-demand SFTP UI.</p>
 */
public final class SshConnect {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);

    private SshConnect() {
    }

    /**
     * One host in a connection chain: a jump host or the final target. {@code keyPath} is an
     * optional private key file to authenticate with (in addition to the shared agent/default
     * identities); {@code password} is an optional password fallback. Either may be blank/null.
     */
    public record HostHop(String host, int port, String user, String password, String keyPath) {
    }

    /**
     * Supplies the passphrase for an encrypted key file on demand. Invoked off the EDT during
     * connect, only when a key is actually encrypted.
     *
     * <p>{@code attempt} is 0 on the first request for a key and increments after each failed
     * decrypt, letting an implementation try a saved passphrase first ({@code attempt == 0}) and
     * then prompt — showing an error — on subsequent attempts. Returning {@code null} gives up on
     * the key (it is skipped so agent/password auth can still apply).</p>
     */
    public interface PassphraseProvider {
        String passphraseFor(String keyPath, int attempt);

        /**
         * Called once a supplied passphrase has successfully decrypted {@code keyPath}, so an
         * implementation can persist it if the user asked to remember it. Default: no-op.
         */
        default void onAccepted(String keyPath) {
        }

        /** A provider that never supplies a passphrase (encrypted keys are simply skipped). */
        PassphraseProvider NONE = (keyPath, attempt) -> null;
    }

    /** How many times a single encrypted key is offered a passphrase before it is skipped. */
    private static final int MAX_PASSPHRASE_ATTEMPTS = 3;

    /**
     * An authenticated SSH connection: the shared client, the target session, and any upstream
     * jump-host sessions that tunnel it (empty for a direct connection).
     */
    public record Connected(SshClient client, ClientSession session, List<ClientSession> upstream) {
        /**
         * Close the target session and every upstream jump-host session (reverse order — closing
         * a hop session also tears down the port-forward it hosts), then stop the client.
         */
        public void close() {
            try {
                session.close(false);
            } catch (Exception ignored) {
            }
            for (int i = upstream.size() - 1; i >= 0; i--) {
                try {
                    upstream.get(i).close(false);
                } catch (Exception ignored) {
                }
            }
            client.stop();
        }
    }

    /**
     * Connects and authenticates to {@code target}, optionally tunneling through one or more
     * {@code jumpHosts} (in order). Blocking — call off the EDT. The caller owns the returned
     * {@link Connected} and must {@link Connected#close()} it (or, for the shell, let
     * {@link SshSession#close()} do so).
     *
     * <p>All hops share one client, so the ssh-agent and on-disk key identities are installed
     * once and apply to every hop; each hop may additionally supply a password fallback.
     * MINA 2.18 has no native ProxyJump, so each hop after the first is reached by opening a
     * local port-forward on the previous (authenticated) session to the next hop's address and
     * connecting through it.</p>
     */
    public static Connected open(List<HostHop> jumpHosts, HostHop target) throws IOException {
        return open(jumpHosts, target, PassphraseProvider.NONE);
    }

    /**
     * As {@link #open(List, HostHop)}, but {@code passphrases} is consulted for the passphrase of
     * any encrypted per-hop key file ({@link HostHop#keyPath()}).
     */
    public static Connected open(List<HostHop> jumpHosts, HostHop target,
                                 PassphraseProvider passphrases) throws IOException {
        SshClient client = SshClient.setUpDefaultClient();

        // OpenSSH known_hosts policy: TOFU for unknown hosts, warn on changed keys. The verifier
        // is told each hop's real host before connecting so proxied hops (reached via a local
        // 127.0.0.1 forward) are checked/prompted/recorded under their true name, not localhost.
        JtermKnownHostsVerifier verifier =
                new JtermKnownHostsVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsFile());
        client.setServerKeyVerifier(verifier);

        // ssh-agent over a JDK Unix socket (no APR); also enables agent forwarding on channels.
        installAgent(client);

        // Default on-disk identities. Encrypted ones are prompted for via the same passphrase
        // finder as configured keys (previously they were silently unusable without the agent).
        List<Path> keys = defaultIdentityFiles();
        if (!keys.isEmpty()) {
            FileKeyPairProvider defaults = new FileKeyPairProvider(keys.toArray(new Path[0]));
            defaults.setPasswordFinder(passphraseFinder(passphrases));
            client.setKeyIdentityProvider(defaults);
        }

        client.start();
        List<ClientSession> upstream = new ArrayList<>();
        try {
            ClientSession via = null;
            for (HostHop hop : jumpHosts) {
                via = connectHop(client, verifier, via, hop, passphrases);
                upstream.add(via);
            }
            ClientSession targetSession = connectHop(client, verifier, via, target, passphrases);
            return new Connected(client, targetSession, upstream);
        } catch (IOException e) {
            for (int i = upstream.size() - 1; i >= 0; i--) {
                try {
                    upstream.get(i).close(true);
                } catch (Exception ignored) {
                }
            }
            client.stop();
            throw e;
        }
    }

    /**
     * Backwards-compatible direct connection (no jump hosts). Used by the SFTP browser and any
     * other single-hop caller.
     */
    public static Connected open(String host, int port, String user, String password)
            throws IOException {
        return open(List.of(), new HostHop(host, port, user, password, null));
    }

    /**
     * Connects and authenticates one hop on the shared {@code client}. If {@code via} is non-null
     * the hop is reached through a local port-forward opened on that session; otherwise it is a
     * direct connection. The verifier is pointed at the hop's real host for the duration so
     * known_hosts handling uses the true name even when connecting via 127.0.0.1.
     */
    private static ClientSession connectHop(SshClient client, JtermKnownHostsVerifier verifier,
                                            ClientSession via, HostHop hop,
                                            PassphraseProvider passphrases) throws IOException {
        int port = hop.port() <= 0 ? 22 : hop.port();
        String connectHost = hop.host();
        int connectPort = port;
        if (via != null) {
            SshdSocketAddress bound = via.startLocalPortForwarding(
                    new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress(hop.host(), port));
            connectHost = bound.getHostName();
            connectPort = bound.getPort();
        }
        verifier.setIntendedHost(hop.host(), port);
        try {
            ClientSession session = client.connect(hop.user(), connectHost, connectPort)
                    .verify(CONNECT_TIMEOUT)
                    .getSession();
            try {
                addKeyIdentity(session, hop.keyPath(), passphrases);
                if (hop.password() != null && !hop.password().isEmpty()) {
                    session.addPasswordIdentity(hop.password());
                }
                session.auth().verify(AUTH_TIMEOUT);
                return session;
            } catch (IOException e) {
                session.close(true);
                throw e;
            }
        } finally {
            verifier.clearIntendedHost();
        }
    }

    /**
     * Registers the hop's configured private key file (if any) as a session identity. A blank
     * path is ignored. A leading {@code ~/} is expanded to the user's home directory. If the key
     * can't be read or decrypted (bad path, wrong/declined passphrase) it is skipped rather than
     * failing the connection, so agent/password auth can still apply.
     */
    private static void addKeyIdentity(ClientSession session, String keyPath,
                                       PassphraseProvider passphrases) {
        if (keyPath == null || keyPath.isBlank()) {
            return;
        }
        Path path = expandHome(keyPath.trim());
        FileKeyPairProvider provider = new FileKeyPairProvider(path);
        provider.setPasswordFinder(passphraseFinder(passphrases));
        try {
            for (KeyPair kp : provider.loadKeys(session)) {
                session.addPublicKeyIdentity(kp);
            }
        } catch (Exception ignored) {
            // Unreadable/undecryptable key: fall through to agent/password auth.
        }
    }

    /**
     * Adapts a {@link PassphraseProvider} to MINA's {@link FilePasswordProvider}. The provider is
     * asked once per attempt (the resource's name is the key file); a wrong passphrase triggers a
     * {@code RETRY} so the provider can re-prompt, up to {@link #MAX_PASSPHRASE_ATTEMPTS}. A
     * {@code null} passphrase (user cancelled) or a successful decrypt stops the loop. On success
     * the provider is told via {@link PassphraseProvider#onAccepted}.
     */
    private static FilePasswordProvider passphraseFinder(PassphraseProvider passphrases) {
        PassphraseProvider p = passphrases != null ? passphrases : PassphraseProvider.NONE;
        return new FilePasswordProvider() {
            @Override
            public String getPassword(SessionContext session, NamedResource resource, int retryIndex) {
                return p.passphraseFor(resource.getName(), retryIndex);
            }

            @Override
            public ResourceDecodeResult handleDecodeAttemptResult(SessionContext session,
                    NamedResource resource, int retryIndex, String password, Exception err) {
                if (err == null) {
                    if (password != null) {
                        p.onAccepted(resource.getName());
                    }
                    return ResourceDecodeResult.TERMINATE; // decoded OK
                }
                // Wrong passphrase: re-prompt until the cap. A null password means the user gave
                // up, so stop and let the key be skipped (agent/password auth still applies).
                if (password == null || retryIndex + 1 >= MAX_PASSPHRASE_ATTEMPTS) {
                    return ResourceDecodeResult.TERMINATE;
                }
                return ResourceDecodeResult.RETRY;
            }
        };
    }

    /**
     * Resolves {@code path} (expanding a leading {@code ~}) to an absolute, normalized string —
     * the same form a key's {@code NamedResource} name takes during auth — so a caller's
     * {@link PassphraseProvider} can recognize which configured key it is being asked about.
     * Returns {@code null} for a blank path.
     */
    public static String resolveKeyPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return expandHome(path.trim()).toAbsolutePath().normalize().toString();
    }

    private static Path expandHome(String path) {
        if (path.equals("~")) {
            return Path.of(System.getProperty("user.home", "."));
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home", "."), path.substring(2));
        }
        return Path.of(path);
    }

    /**
     * Wires the local ssh-agent (JDK Unix socket on Linux/macOS, named pipe on Windows).
     * MINA reads the endpoint from the client property {@code SSH_AUTH_SOCK} (not the process
     * env), so we set it explicitly; if no agent is available we skip it (key/password auth
     * still apply).
     */
    private static void installAgent(SshClient client) {
        if (!AgentSupport.isAgentAvailable()) {
            return;
        }
        // The endpoint may be null on Windows when only Pageant (which has no socket/pipe path)
        // is present; the factory still builds the agent itself in that case.
        String endpoint = AgentSupport.resolveEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, endpoint);
        }
        client.setAgentFactory(new JdkAgentFactory());
    }

    private static Path knownHostsFile() {
        Path ssh = Path.of(System.getProperty("user.home", "."), ".ssh");
        try {
            Files.createDirectories(ssh);
        } catch (Exception ignored) {
        }
        return ssh.resolve("known_hosts");
    }

    private static List<Path> defaultIdentityFiles() {
        Path ssh = Path.of(System.getProperty("user.home", "."), ".ssh");
        List<Path> found = new ArrayList<>();
        for (String name : new String[]{"id_ed25519", "id_ecdsa", "id_rsa"}) {
            Path p = ssh.resolve(name);
            if (Files.isRegularFile(p)) {
                found.add(p);
            }
        }
        return found;
    }
}
