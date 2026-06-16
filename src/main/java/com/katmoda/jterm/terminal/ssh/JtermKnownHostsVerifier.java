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

import com.katmoda.jterm.config.AppSettings;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * Host-key policy backed by an OpenSSH {@code known_hosts} file.
 *
 * <ul>
 *   <li>Known + matching key → accept silently.</li>
 *   <li>Unknown host → trust-on-first-use prompt showing the SHA-256 fingerprint; on
 *       acceptance the key is appended to {@code known_hosts}.</li>
 *   <li>Stored key changed → strong warning that defaults to reject; only continues (and
 *       updates the file) if the user explicitly confirms.</li>
 * </ul>
 *
 * <p>Verification runs on the SSH connect background thread, so prompts are marshalled to
 * the EDT via {@link SwingUtilities#invokeAndWait}.</p>
 */
public final class JtermKnownHostsVerifier extends KnownHostsServerKeyVerifier {

    // When connecting a jump-host hop through a local 127.0.0.1 port-forward, the address MINA
    // reports is localhost; this override lets us check/prompt/record known_hosts under the hop's
    // real name instead. Null for direct connections. Set/cleared around each sequential connect.
    private volatile SocketAddress intendedHost;

    public JtermKnownHostsVerifier(ServerKeyVerifier delegate, Path knownHostsFile) {
        super(delegate, knownHostsFile);
        setModifiedServerKeyAcceptor(this);
    }

    /** Point known_hosts verification at {@code host}:{@code port} for the next connect. */
    public void setIntendedHost(String host, int port) {
        this.intendedHost = InetSocketAddress.createUnresolved(host, port);
    }

    /** Resume verifying against the address MINA actually connected to. */
    public void clearIntendedHost() {
        this.intendedHost = null;
    }

    @Override
    public boolean verifyServerKey(ClientSession session, SocketAddress remote, PublicKey serverKey) {
        SocketAddress override = intendedHost;
        return super.verifyServerKey(session, override != null ? override : remote, serverKey);
    }

    @Override
    protected boolean acceptUnknownHostKey(ClientSession session, SocketAddress remote, PublicKey serverKey) {
        // With auto-accept on, trust a first-seen host silently and record its key. This only
        // covers unknown hosts; a CHANGED key still goes through acceptModifiedServerKey, which
        // always warns regardless of this setting.
        if (!AppSettings.get().isAutoAcceptNewHostKeys()) {
            String message = "The authenticity of host '" + remote + "' can't be established.\n"
                    + keyType(serverKey) + " key fingerprint:\n" + KeyUtils.getFingerPrint(serverKey)
                    + "\n\nTrust this host and continue connecting?";
            if (!confirmOnEdt("Unknown host key", message, JOptionPane.WARNING_MESSAGE)) {
                return false;
            }
        }
        // MINA's base impl would append the key here, but it gates the write behind the
        // (reject-all) delegate verifier, so we must persist it ourselves. Without this the
        // key is never written to known_hosts — re-prompting on every reconnect (notably on
        // Windows, where the file isn't pre-populated by the system ssh client).
        try {
            updateKnownHostsFile(session, remote, serverKey, getPath(), java.util.Collections.emptyList());
        } catch (Exception e) {
            handleKnownHostsFileUpdateFailure(session, remote, serverKey, getPath(),
                    java.util.Collections.emptyList(), e);
        }
        return true;
    }

    @Override
    public boolean acceptModifiedServerKey(ClientSession session, SocketAddress remote,
                                           KnownHostEntry entry, PublicKey expected, PublicKey actual) {
        String message = "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED for '" + remote + "'.\n"
                + "This could indicate a man-in-the-middle attack.\n\n"
                + "Stored fingerprint:   " + KeyUtils.getFingerPrint(expected) + "\n"
                + "Received fingerprint: " + KeyUtils.getFingerPrint(actual) + "\n\n"
                + "Update known_hosts and connect anyway?";
        return confirmOnEdt("Host key CHANGED", message, JOptionPane.ERROR_MESSAGE);
    }

    private static String keyType(PublicKey key) {
        String type = KeyUtils.getKeyType(key);
        return (type != null && !type.isBlank()) ? type : "Server";
    }

    /** Show a yes/no dialog on the EDT; Escape/close counts as "no" (default reject). */
    private static boolean confirmOnEdt(String title, String message, int messageType) {
        boolean[] accepted = {false};
        Runnable show = () -> accepted[0] = JOptionPane.showConfirmDialog(
                null, message, title, JOptionPane.YES_NO_OPTION, messageType) == JOptionPane.YES_OPTION;
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(show);
            } catch (Exception e) {
                return false;
            }
        }
        return accepted[0];
    }
}
