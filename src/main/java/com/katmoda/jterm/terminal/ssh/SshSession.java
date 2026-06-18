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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * Shared daemon scheduler driving idle-gated keep-alive NUL injection for every session with a
     * keep-alive interval. One thread suffices — each tick is a tiny non-blocking write.
     */
    private static final ScheduledExecutorService KEEPALIVE =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ssh-keepalive");
                t.setDaemon(true);
                return t;
            });

    private final SshConnect.Connected connection;
    private final ChannelShell channel;
    private final SshTtyConnector connector;
    private final String title;
    private final String iconId;
    private final TerminalProfile profile;
    private final String highlightListId;
    private final String tabColorHex;
    private final Callable<SshConnect.Connected> freshConnectionDialer;

    // Repeating idle-gated keep-alive task; null when keep-alive is disabled. Cancelled in close().
    private volatile ScheduledFuture<?> keepAliveFuture;

    private SshSession(SshConnect.Connected connection, ChannelShell channel,
                       String title, String iconId, TerminalProfile profile, String highlightListId,
                       String tabColorHex, Callable<SshConnect.Connected> freshConnectionDialer) {
        this.connection = connection;
        this.channel = channel;
        this.title = title;
        this.iconId = iconId;
        this.profile = profile;
        this.highlightListId = highlightListId;
        this.tabColorHex = tabColorHex;
        this.freshConnectionDialer = freshConnectionDialer;
        this.connector = new SshTtyConnector(channel, title, profile.charset());
    }

    /**
     * Dials a <em>fresh, dedicated</em> authenticated connection to this session's host (no shell
     * channel), reusing the credentials resolved when this session was first opened — so it needs no
     * UI and won't re-prompt. Used by the SFTP browser to reconnect after the shared session has
     * died (the SFTP browser then owns and closes the returned connection). May be {@code null} for
     * sessions created without a dialer.
     */
    public Callable<SshConnect.Connected> freshConnectionDialer() {
        return freshConnectionDialer;
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
     * @param keepAliveSeconds keep-alive interval in seconds, or {@code 0} to disable. When &gt; 0
     *                        it both enables the SSH-protocol heartbeat and drives idle-gated NUL
     *                        injection into the shell to defeat a server-side {@code TMOUT}.
     */
    public static SshSession connect(String host, int port, String user, boolean agentForwarding,
                                     String password, String keyPath,
                                     List<SshConnect.HostHop> jumpHosts,
                                     SshConnect.PassphraseProvider passphrases,
                                     String displayName, String iconId,
                                     TerminalProfile profile, String highlightListId,
                                     String tabColorHex, int keepAliveSeconds) throws IOException {
        List<SshConnect.HostHop> hops = jumpHosts != null ? jumpHosts : List.of();
        SshConnect.PassphraseProvider pp =
                passphrases != null ? passphrases : SshConnect.PassphraseProvider.NONE;
        SshConnect.HostHop target = new SshConnect.HostHop(host, port, user, password, keyPath);
        SshConnect.Connected connection = SshConnect.open(hops, target, pp, keepAliveSeconds);
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
            // A dialer for a fresh dedicated connection to the same target with the same resolved
            // credentials — used by the SFTP browser to reconnect independently of this shell.
            Callable<SshConnect.Connected> dialer = () -> SshConnect.open(hops, target, pp);
            SshSession session = new SshSession(connection, channel, label, iconId, profile,
                    highlightListId, tabColorHex, dialer);
            session.startKeepAlive(keepAliveSeconds);
            return session;
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

    /**
     * If {@code keepAliveSeconds > 0}, schedules a repeating task that injects a NUL into the shell
     * whenever the session is alive and has been idle for at least the interval (so a user actively
     * typing — including mid-password — is never disturbed). The SSH-protocol heartbeat is handled
     * separately on the client (see {@link SshConnect}).
     */
    private void startKeepAlive(int keepAliveSeconds) {
        if (keepAliveSeconds <= 0) {
            return;
        }
        Duration interval = Duration.ofSeconds(keepAliveSeconds);
        keepAliveFuture = KEEPALIVE.scheduleWithFixedDelay(() -> {
            try {
                if (isAlive() && connector.idleFor(interval)) {
                    connector.sendKeepAlive();
                }
            } catch (IOException ignored) {
                // Transient write failure; the next tick retries, and a truly dead session is
                // reaped by isAlive() / channel close.
            }
        }, keepAliveSeconds, keepAliveSeconds, TimeUnit.SECONDS);
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
        ScheduledFuture<?> ka = keepAliveFuture;
        if (ka != null) {
            ka.cancel(false);
        }
        try {
            channel.close(false);
        } catch (Exception ignored) {
        }
        connection.close();
    }
}
