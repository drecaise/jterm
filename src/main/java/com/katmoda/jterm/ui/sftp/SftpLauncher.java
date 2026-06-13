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

    private record Built(SftpClient client, Runnable onClose) {
    }

    /** Open SFTP on an existing terminal's live, authenticated SSH session (no re-auth). */
    public static void openOnLiveSession(SshSession ssh, Consumer<GridContent> onReady,
                                         Consumer<Throwable> onError) {
        ClientSession session = ssh.clientSession();
        open(() -> new Built(SftpClientFactory.instance().createSftpClient(session), () -> { }),
                ssh.title(), onReady, onError);
    }

    /** Open SFTP over a fresh, dedicated SSH connection (owned and closed by the pane). */
    public static void openFresh(String host, int port, String user, String password, String keyPath,
                                 SshConnect.PassphraseProvider passphrases, String hostLabel,
                                 Consumer<GridContent> onReady, Consumer<Throwable> onError) {
        open(() -> {
            SshConnect.Connected conn = SshConnect.open(List.of(),
                    new SshConnect.HostHop(host, port, user, password, keyPath),
                    passphrases != null ? passphrases : SshConnect.PassphraseProvider.NONE);
            try {
                SftpClient client = SftpClientFactory.instance().createSftpClient(conn.session());
                return new Built(client, conn::close);
            } catch (Exception e) {
                conn.close();
                throw e;
            }
        }, hostLabel, onReady, onError);
    }

    private static void open(Callable<Built> builder, String hostLabel,
                             Consumer<GridContent> onReady, Consumer<Throwable> onError) {
        new SwingWorker<Built, Void>() {
            @Override
            protected Built doInBackground() throws Exception {
                return builder.call();
            }

            @Override
            protected void done() {
                try {
                    Built built = get();
                    onReady.accept(new SftpPane(built.client(), hostLabel, built.onClose()));
                } catch (Exception e) {
                    onError.accept(e.getCause() != null ? e.getCause() : e);
                }
            }
        }.execute();
    }
}
