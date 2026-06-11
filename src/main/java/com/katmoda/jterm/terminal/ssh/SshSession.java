package com.katmoda.jterm.terminal.ssh;

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.terminal.ssh.agent.JdkAgentFactory;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An interactive SSH shell session backed by Apache MINA SSHD.
 *
 * <p>Phase-1 authentication is ssh-agent + on-disk key files only — no secrets are
 * stored by the app. When the host config requests it, ssh-agent forwarding is enabled
 * on the channel so the remote can use the local agent's keys for onward hops.</p>
 *
 * <p>Host-key verification currently accepts all keys (trust-on-connect). Tightening
 * this to a known_hosts-backed policy is a flagged follow-up.</p>
 */
public final class SshSession implements TerminalSession {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);

    /** UTF-8 character type requested when the client conveys no UTF-8 locale (e.g. on Windows). */
    private static final String DEFAULT_UTF8_LOCALE = "C.UTF-8";

    private final SshClient client;
    private final ClientSession session;
    private final ChannelShell channel;
    private final SshTtyConnector connector;
    private final String title;
    private final String iconId;
    private final TerminalProfile profile;

    private SshSession(SshClient client, ClientSession session, ChannelShell channel,
                       String title, String iconId, TerminalProfile profile) {
        this.client = client;
        this.session = session;
        this.channel = channel;
        this.title = title;
        this.iconId = iconId;
        this.profile = profile;
        this.connector = new SshTtyConnector(channel, title, profile.charset());
    }

    /** Icon library id this session was launched with (may be {@code null} → type default). */
    public String iconId() {
        return iconId;
    }

    /**
     * Connects and opens a shell channel. Blocking — call off the EDT.
     *
     * <p>Authentication tries publickey (ssh-agent then on-disk keys) first; if a
     * {@code password} is supplied it is added as a fallback (also covers
     * keyboard-interactive).</p>
     *
     * @param host            remote host
     * @param port            remote port (22 if &lt;= 0)
     * @param user            login user
     * @param agentForwarding whether to forward the local ssh-agent
     * @param password        optional password fallback (may be {@code null}/blank)
     * @param displayName     label for the pane title
     * @param iconId          icon library id for the tab (may be {@code null})
     * @param profile         terminal type, charset and font settings for this session
     */
    public static SshSession connect(String host, int port, String user, boolean agentForwarding,
                                     String password, String displayName, String iconId,
                                     TerminalProfile profile) throws IOException {
        SshClient client = SshClient.setUpDefaultClient();

        // OpenSSH known_hosts policy: TOFU for unknown hosts, warn on changed keys.
        client.setServerKeyVerifier(
                new JtermKnownHostsVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsFile()));

        // ssh-agent over a JDK Unix socket (no APR); also enables agent forwarding.
        installAgent(client);

        // Default on-disk identities (agent covers passphrase-protected keys).
        List<Path> keys = defaultIdentityFiles();
        if (!keys.isEmpty()) {
            client.setKeyIdentityProvider(new FileKeyPairProvider(keys.toArray(new Path[0])));
        }

        client.start();
        try {
            ClientSession session = client.connect(user, host, port <= 0 ? 22 : port)
                    .verify(CONNECT_TIMEOUT)
                    .getSession();
            try {
                if (password != null && !password.isEmpty()) {
                    session.addPasswordIdentity(password);
                }
                session.auth().verify(AUTH_TIMEOUT);

                ChannelShell channel = session.createShellChannel();
                channel.setPtyType(profile.terminalType());
                channel.setPtyColumns(80);
                channel.setPtyLines(24);
                channel.setAgentForwarding(agentForwarding);
                channel.setRedirectErrorStream(true);
                applyLocale(channel);
                channel.open().verify(CONNECT_TIMEOUT);

                String label = displayName != null && !displayName.isBlank()
                        ? displayName : user + "@" + host;
                return new SshSession(client, session, channel, label, iconId, profile);
            } catch (IOException e) {
                session.close(true);
                throw e;
            }
        } catch (IOException e) {
            client.stop();
            throw e;
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

    /**
     * Conveys the character encoding to the remote login session, mirroring OpenSSH's
     * {@code SendEnv LANG LC_*}: forwards the client's locale variables (servers accept these
     * by default) and, when none of them indicate UTF-8 — typically on Windows, where {@code LANG}
     * is unset — requests {@code LC_CTYPE=C.UTF-8}. Without this, MOTD/banner output drawn with
     * box-drawing characters arrives in a non-UTF-8 encoding and shows up as {@code �}.
     */
    private static void applyLocale(ChannelShell channel) {
        boolean utf8Conveyed = false;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String name = e.getKey();
            if (name.equals("LANG") || name.equals("LANGUAGE") || name.startsWith("LC_")) {
                channel.setEnv(name, e.getValue());
                if ((name.equals("LANG") || name.equals("LC_ALL") || name.equals("LC_CTYPE"))
                        && isUtf8(e.getValue())) {
                    utf8Conveyed = true;
                }
            }
        }
        if (!utf8Conveyed) {
            channel.setEnv("LC_CTYPE", DEFAULT_UTF8_LOCALE);
        }
    }

    private static boolean isUtf8(String localeValue) {
        return localeValue != null
                && localeValue.toUpperCase(Locale.ROOT).replace("-", "").contains("UTF8");
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

    @Override
    public TtyConnector connector() {
        return connector;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public TerminalProfile profile() {
        return profile;
    }

    @Override
    public boolean isAlive() {
        return channel.isOpen() && session.isOpen();
    }

    @Override
    public void close() {
        try {
            channel.close(false);
        } catch (Exception ignored) {
        }
        try {
            session.close(false);
        } catch (Exception ignored) {
        }
        client.stop();
    }
}
