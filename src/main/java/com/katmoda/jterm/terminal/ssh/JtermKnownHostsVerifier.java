package com.katmoda.jterm.terminal.ssh;

import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
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

    public JtermKnownHostsVerifier(ServerKeyVerifier delegate, Path knownHostsFile) {
        super(delegate, knownHostsFile);
        setModifiedServerKeyAcceptor(this);
    }

    @Override
    protected boolean acceptUnknownHostKey(ClientSession session, SocketAddress remote, PublicKey serverKey) {
        String message = "The authenticity of host '" + remote + "' can't be established.\n"
                + keyType(serverKey) + " key fingerprint:\n" + KeyUtils.getFingerPrint(serverKey)
                + "\n\nTrust this host and continue connecting?";
        if (!confirmOnEdt("Unknown host key", message, JOptionPane.WARNING_MESSAGE)) {
            return false;
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
