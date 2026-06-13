package com.katmoda.jterm.terminal.ssh;

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.terminal.TerminalSession;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.time.Duration;
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

    /** UTF-8 character type requested when the client conveys no UTF-8 locale (e.g. on Windows). */
    private static final String DEFAULT_UTF8_LOCALE = "C.UTF-8";

    private final SshConnect.Connected connection;
    private final ChannelShell channel;
    private final SshTtyConnector connector;
    private final String title;
    private final String iconId;
    private final TerminalProfile profile;
    private final String highlightListId;
    private final String tabColorHex;

    private SshSession(SshConnect.Connected connection, ChannelShell channel,
                       String title, String iconId, TerminalProfile profile, String highlightListId,
                       String tabColorHex) {
        this.connection = connection;
        this.channel = channel;
        this.title = title;
        this.iconId = iconId;
        this.profile = profile;
        this.highlightListId = highlightListId;
        this.tabColorHex = tabColorHex;
        this.connector = new SshTtyConnector(channel, title, profile.charset());
    }

    /**
     * The live, authenticated SSH session backing this shell. Reused by the SFTP browser to open
     * an SFTP subsystem channel on the same connection (no re-auth). Stays open until
     * {@link #close()} tears the whole connection down, so the SFTP channel must not close it.
     */
    public ClientSession clientSession() {
        return connection.session();
    }

    /** Icon library id this session was launched with (may be {@code null} → type default). */
    public String iconId() {
        return iconId;
    }

    /** Custom tab color as {@code "#RRGGBB"}, or {@code null} for the theme default. */
    public String tabColorHex() {
        return tabColorHex;
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
     * @param keyPath         optional private key file to authenticate with (may be {@code null}/blank)
     * @param jumpHosts       jump hosts to tunnel through, in connection order (may be empty)
     * @param passphrases     supplies passphrases for any encrypted key files (may be {@code null})
     * @param displayName     label for the pane title
     * @param iconId          icon library id for the tab (may be {@code null})
     * @param profile         terminal type, charset and font settings for this session
     * @param highlightListId output-highlighting override id (may be {@code null} to inherit)
     * @param tabColorHex     custom tab color {@code "#RRGGBB"} (may be {@code null} for the default)
     */
    public static SshSession connect(String host, int port, String user, boolean agentForwarding,
                                     String password, String keyPath,
                                     List<SshConnect.HostHop> jumpHosts,
                                     SshConnect.PassphraseProvider passphrases,
                                     String displayName, String iconId,
                                     TerminalProfile profile, String highlightListId,
                                     String tabColorHex) throws IOException {
        SshConnect.Connected connection = SshConnect.open(
                jumpHosts != null ? jumpHosts : List.of(),
                new SshConnect.HostHop(host, port, user, password, keyPath),
                passphrases != null ? passphrases : SshConnect.PassphraseProvider.NONE);
        try {
            ChannelShell channel = connection.session().createShellChannel();
            channel.setPtyType(profile.terminalType());
            channel.setPtyColumns(80);
            channel.setPtyLines(24);
            channel.setAgentForwarding(agentForwarding);
            channel.setRedirectErrorStream(true);
            applyLocale(channel);
            channel.open().verify(CONNECT_TIMEOUT);

            String label = displayName != null && !displayName.isBlank()
                    ? displayName : user + "@" + host;
            return new SshSession(connection, channel, label, iconId, profile, highlightListId,
                    tabColorHex);
        } catch (IOException e) {
            connection.close();
            throw e;
        }
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
    public String highlightListOverrideId() {
        return highlightListId;
    }

    @Override
    public boolean isAlive() {
        return channel.isOpen() && connection.session().isOpen();
    }

    @Override
    public void close() {
        try {
            channel.close(false);
        } catch (Exception ignored) {
        }
        connection.close();
    }
}
