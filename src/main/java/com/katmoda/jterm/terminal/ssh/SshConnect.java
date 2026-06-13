package com.katmoda.jterm.terminal.ssh;

import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.terminal.ssh.agent.JdkAgentFactory;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** One host in a connection chain: a jump host or the final target. */
    public record HostHop(String host, int port, String user, String password) {
    }

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
        SshClient client = SshClient.setUpDefaultClient();

        // OpenSSH known_hosts policy: TOFU for unknown hosts, warn on changed keys. The verifier
        // is told each hop's real host before connecting so proxied hops (reached via a local
        // 127.0.0.1 forward) are checked/prompted/recorded under their true name, not localhost.
        JtermKnownHostsVerifier verifier =
                new JtermKnownHostsVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsFile());
        client.setServerKeyVerifier(verifier);

        // ssh-agent over a JDK Unix socket (no APR); also enables agent forwarding on channels.
        installAgent(client);

        // Default on-disk identities (agent covers passphrase-protected keys).
        List<Path> keys = defaultIdentityFiles();
        if (!keys.isEmpty()) {
            client.setKeyIdentityProvider(new FileKeyPairProvider(keys.toArray(new Path[0])));
        }

        client.start();
        List<ClientSession> upstream = new ArrayList<>();
        try {
            ClientSession via = null;
            for (HostHop hop : jumpHosts) {
                via = connectHop(client, verifier, via, hop);
                upstream.add(via);
            }
            ClientSession targetSession = connectHop(client, verifier, via, target);
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
        return open(List.of(), new HostHop(host, port, user, password));
    }

    /**
     * Connects and authenticates one hop on the shared {@code client}. If {@code via} is non-null
     * the hop is reached through a local port-forward opened on that session; otherwise it is a
     * direct connection. The verifier is pointed at the hop's real host for the duration so
     * known_hosts handling uses the true name even when connecting via 127.0.0.1.
     */
    private static ClientSession connectHop(SshClient client, JtermKnownHostsVerifier verifier,
                                            ClientSession via, HostHop hop) throws IOException {
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
     * Wires the local ssh-agent (JDK Unix socket on Linux/macOS, named pipe on Windows).
     * MINA reads the endpoint from the client property {@code SSH_AUTH_SOCK} (not the process
     * env), so we set it explicitly; if no agent is available we skip it (key/password auth
     * still apply).
     */
    private static void installAgent(SshClient client) {
        String endpoint = AgentSupport.resolveEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, endpoint);
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
