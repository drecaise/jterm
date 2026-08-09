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
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.core.CoreModuleProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(SshConnect.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);

    private SshConnect() {
    }

    /**
     * One host in a connection chain: a jump host or the final target. {@code keyPath} is an
     * optional private key file to authenticate with (in addition to the shared agent/default
     * identities); {@code password} is an optional password fallback. Either may be blank/null.
     *
     * <p>{@code id} is the configuration id this hop came from ({@code SshSessionConfig} or
     * {@code JumpHostConfig}) — it lets an {@link InteractiveAuth} key the credential vault when
     * the user asks to remember a password entered at the prompt. {@code null} for ad-hoc dials
     * with no stored configuration.</p>
     */
    public record HostHop(String host, int port, String user, String password, String keyPath,
                          String id) {

        /** A hop with no backing configuration (nothing to remember a password against). */
        public HostHop(String host, int port, String user, String password, String keyPath) {
            this(host, port, user, password, keyPath, null);
        }

        /** {@code user@host}, for prompts and error messages. */
        public String label() {
            return user + "@" + host;
        }
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

    /**
     * Supplies credentials interactively <em>during</em> authentication, as the fallback for when
     * publickey auth (ssh-agent, then on-disk keys) has been exhausted. Invoked off the EDT — the
     * implementation marshals its own prompts — and only for auth methods the server actually
     * offers, so a key-only server never triggers a prompt.
     *
     * <p>Returning {@code null} means the user cancelled: no further prompt is shown for that hop
     * by <em>either</em> method, and the connect fails with an {@link SshAuthException}.</p>
     */
    public interface InteractiveAuth {

        /**
         * Password for {@code hop}. {@code attempt} is 0 the first time the server asks and
         * increments after each rejected password, so an implementation can show an error on the
         * re-prompt.
         */
        String passwordFor(HostHop hop, int attempt);

        /**
         * Answers a {@code keyboard-interactive} challenge (PAM, 2FA/OTP). {@code prompts} and
         * {@code echo} are parallel arrays; {@code echo[i]} false means the reply must be masked.
         * The returned array must have one entry per prompt.
         */
        String[] challenge(HostHop hop, String instruction, String[] prompts, boolean[] echo);

        /**
         * Called once {@code hop} has authenticated, so an implementation can persist a password
         * the user asked to remember. Default: no-op.
         */
        default void onAuthSucceeded(HostHop hop) {
        }

        /** A provider that never prompts (authentication simply fails as before). */
        InteractiveAuth NONE = new InteractiveAuth() {
            @Override
            public String passwordFor(HostHop hop, int attempt) {
                return null;
            }

            @Override
            public String[] challenge(HostHop hop, String instruction, String[] prompts,
                                      boolean[] echo) {
                return null;
            }
        };
    }

    /** How many times a single encrypted key is offered a passphrase before it is skipped. */
    private static final int MAX_PASSPHRASE_ATTEMPTS = 3;

    /**
     * Minimum size of the client's NIO worker pool. An interactive auth prompt blocks the MINA
     * worker that is driving the handshake for as long as the dialog is up; on a jump-host chain
     * the upstream session's port-forward still needs a worker to keep the tunnel flowing, so the
     * pool must not be a single thread on a low-core machine.
     */
    private static final int MIN_NIO_WORKERS = 8;

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
            } catch (Exception e) {
                LOG.warn("failed to close ssh session", e);
            }
            for (int i = upstream.size() - 1; i >= 0; i--) {
                try {
                    upstream.get(i).close(false);
                } catch (Exception e) {
                    LOG.warn("failed to close upstream jump-host session", e);
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
        return open(jumpHosts, target, PassphraseProvider.NONE, InteractiveAuth.NONE, 0);
    }

    /**
     * As {@link #open(List, HostHop)}, but {@code passphrases} is consulted for the passphrase of
     * any encrypted per-hop key file ({@link HostHop#keyPath()}).
     */
    public static Connected open(List<HostHop> jumpHosts, HostHop target,
                                 PassphraseProvider passphrases) throws IOException {
        return open(jumpHosts, target, passphrases, InteractiveAuth.NONE, 0);
    }

    /**
     * As {@link #open(List, HostHop, PassphraseProvider)}, but {@code interactive} supplies a
     * password (or keyboard-interactive answers) on demand once publickey auth has been exhausted.
     */
    public static Connected open(List<HostHop> jumpHosts, HostHop target,
                                 PassphraseProvider passphrases, InteractiveAuth interactive)
            throws IOException {
        return open(jumpHosts, target, passphrases, interactive, 0);
    }

    /**
     * As {@link #open(List, HostHop, PassphraseProvider, InteractiveAuth)}, but when
     * {@code keepAliveSeconds > 0} the SSH-protocol heartbeat ({@code keepalive@openssh.com} via
     * {@code SSH_MSG_IGNORE}) is enabled at that interval on the client before it starts, guarding
     * against NAT/firewall idle drops and detecting a dead peer. A value of {@code 0} leaves
     * heartbeats off.
     */
    public static Connected open(List<HostHop> jumpHosts, HostHop target,
                                 PassphraseProvider passphrases, InteractiveAuth interactive,
                                 int keepAliveSeconds)
            throws IOException {
        SshClient client = SshClient.setUpDefaultClient();
        CoreModuleProperties.NIO_WORKERS.set(client,
                Math.max(MIN_NIO_WORKERS, Runtime.getRuntime().availableProcessors()));
        // Set the heartbeat on the client (created fresh per open) before start(), so it is active
        // from session setup — more reliable than mutating the session post-auth. The request type
        // defaults to keepalive@openssh.com.
        if (keepAliveSeconds > 0) {
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(keepAliveSeconds));
        }

        // OpenSSH known_hosts policy: TOFU for unknown hosts, warn on changed keys. The verifier
        // is told each hop's real host before connecting so proxied hops (reached via a local
        // 127.0.0.1 forward) are checked/prompted/recorded under their true name, not localhost.
        JtermKnownHostsVerifier verifier =
                new JtermKnownHostsVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsFile());
        client.setServerKeyVerifier(verifier);

        // ssh-agent over a JDK Unix socket (no APR); also enables agent forwarding on channels.
        installAgent(client);

        // Interactive password / keyboard-interactive fallback. MINA consults this only after
        // publickey auth is exhausted and only for methods the server offers, so installing it
        // never changes the outcome on a key-only server.
        JtermUserInteraction userInteraction = null;
        if (interactive != null && interactive != InteractiveAuth.NONE) {
            userInteraction = new JtermUserInteraction(interactive);
            client.setUserInteraction(userInteraction);
        }

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
                via = connectHop(client, verifier, userInteraction, via, hop, passphrases);
                upstream.add(via);
            }
            ClientSession targetSession =
                    connectHop(client, verifier, userInteraction, via, target, passphrases);
            return new Connected(client, targetSession, upstream);
        } catch (IOException e) {
            for (int i = upstream.size() - 1; i >= 0; i--) {
                try {
                    upstream.get(i).close(true);
                } catch (Exception closeFailure) {
                    LOG.warn("failed to close jump-host session during connect teardown", closeFailure);
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
                                            JtermUserInteraction interaction,
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
        if (interaction != null) {
            interaction.setCurrentHop(hop);
        }
        try {
            ClientSession session = client.connect(hop.user(), connectHost, connectPort)
                    .verify(CONNECT_TIMEOUT)
                    .getSession();
            try {
                addKeyIdentity(session, hop.keyPath(), passphrases);
                if (hop.password() != null && !hop.password().isEmpty()) {
                    session.addPasswordIdentity(hop.password());
                }
                awaitAuth(session, hop, interaction);
                if (interaction != null) {
                    interaction.authSucceeded();
                }
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
     * Authenticates {@code session}, waiting out any interactive prompt.
     *
     * <p>{@code AuthFuture.verify(AUTH_TIMEOUT)} can't be used directly once prompting is in play:
     * the timeout exists to catch a wedged server, but a user typing a password would trip it just
     * the same. So the wait is done in {@link #AUTH_TIMEOUT} slices and only abandoned when the
     * slice elapsed with no prompt open and none shown since the previous slice — server silence,
     * not user think-time.</p>
     */
    private static void awaitAuth(ClientSession session, HostHop hop, JtermUserInteraction interaction)
            throws IOException {
        AuthFuture future = session.auth();
        long lastActivity = interaction == null ? 0 : interaction.promptActivity();
        while (!future.await(AUTH_TIMEOUT)) {
            long activity = interaction == null ? 0 : interaction.promptActivity();
            boolean busy = interaction != null && (interaction.isPrompting() || activity != lastActivity);
            if (!busy) {
                throw new SshAuthException(hop.user(), hop.host(),
                        new SshException("Authentication timed out after " + AUTH_TIMEOUT.toSeconds()
                                + "s"));
            }
            lastActivity = activity;
        }
        if (!future.isSuccess()) {
            IOException cause;
            try {
                // The future is already done, so this only unwraps MINA's own failure reason
                // (typically "No more authentication methods available").
                future.verify();
                cause = new SshException("Authentication was not granted");
            } catch (IOException e) {
                cause = e;
            }
            throw new SshAuthException(hop.user(), hop.host(), cause);
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
        } catch (Exception e) {
            // Unreadable/undecryptable key: fall through to agent/password auth.
            LOG.warn("failed to load key identity from {}; falling back to agent/password auth", path, e);
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
            // Logged rather than silent: "the agent wasn't even tried" is otherwise invisible when
            // a connect later fails with a generic authentication error.
            LOG.info("no ssh-agent available; relying on key/password authentication");
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
        } catch (Exception e) {
            LOG.debug("could not create ~/.ssh directory", e);
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
