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
package com.katmoda.jterm.ui.sftp;

import com.katmoda.jterm.terminal.ssh.SshConnect;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.ui.grid.GridContent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Builds an {@link SftpPane} against an SSH host, off the EDT, then hands it back on the EDT.
 *
 * <p>This is the single entry point the rest of the app references for SFTP; everything that
 * touches the {@code sshd-sftp} library lives here and in {@link SftpPane}, so those classes load
 * only when SFTP is first used.</p>
 *
 * <p>Two paths:</p>
 * <ul>
 *   <li>{@link #openOnLiveSession} reuses an open terminal's authenticated session — the pane
 *       closes only its own SFTP channel, leaving the SSH connection for the terminal.</li>
 *   <li>{@link #openFresh} opens a dedicated SSH connection — the pane owns it and tears it down
 *       when closed.</li>
 * </ul>
 */
public final class SftpLauncher {

    private SftpLauncher() {
    }

    /**
     * An open SFTP connection: the client (its channel) plus the cleanup that tears down whatever
     * this launcher path owns (the SSH connection for a dedicated open, a no-op when sharing a
     * terminal's session). Returned by the builder and by the pane's reconnector.
     */
    public record SftpConnection(SftpClient client, Runnable onClose) {
    }

    /** Open SFTP on an existing terminal's live, authenticated SSH session (no re-auth). */
    public static void openOnLiveSession(SshSession ssh, Consumer<GridContent> onReady,
                                         Consumer<Throwable> onError) {
        ClientSession session = ssh.clientSession();
        // Reconnect by opening a fresh SFTP channel on the same (terminal-owned) session. Best-effort:
        // succeeds when only the SFTP channel dropped; throws if the whole session has died, which the
        // pane surfaces as an ordinary error since the terminal owns that connection's lifecycle.
        Callable<SftpConnection> builder =
                () -> new SftpConnection(SftpClientFactory.instance().createSftpClient(session), () -> { });
        open(builder, builder, ssh.title(), ssh.iconId(), onReady, onError);
    }

    /** Open SFTP over a fresh, dedicated SSH connection (owned and closed by the pane). */
    public static void openFresh(String host, int port, String user, String password, String keyPath,
                                 SshConnect.PassphraseProvider passphrases, String hostLabel, String iconId,
                                 Consumer<GridContent> onReady, Consumer<Throwable> onError) {
        // A full reconnect: re-auth a fresh dedicated connection and open SFTP on it. Used both for the
        // initial open and for the pane's reconnector (this path owns the credentials).
        Callable<SftpConnection> builder = () -> {
            SshConnect.Connected conn = SshConnect.open(List.of(),
                    new SshConnect.HostHop(host, port, user, password, keyPath),
                    passphrases != null ? passphrases : SshConnect.PassphraseProvider.NONE);
            try {
                SftpClient client = SftpClientFactory.instance().createSftpClient(conn.session());
                return new SftpConnection(client, conn::close);
            } catch (Exception e) {
                conn.close();
                throw e;
            }
        };
        open(builder, builder, hostLabel, iconId, onReady, onError);
    }

    private static void open(Callable<SftpConnection> builder, Callable<SftpConnection> reconnector,
                             String hostLabel, String iconId,
                             Consumer<GridContent> onReady, Consumer<Throwable> onError) {
        new SwingWorker<SftpConnection, Void>() {
            @Override
            protected SftpConnection doInBackground() throws Exception {
                return builder.call();
            }

            @Override
            protected void done() {
                try {
                    SftpConnection built = get();
                    onReady.accept(new SftpPane(built.client(), hostLabel, iconId, built.onClose(), reconnector));
                } catch (Exception e) {
                    onError.accept(e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }
}
