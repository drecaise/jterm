package com.katmoda.jterm.terminal.ssh.agent;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OS-aware access to the local ssh-agent: resolves the endpoint, opens the right transport
 * (Unix socket vs Windows named pipe) and lists identities. Shared by the SSH connector,
 * the agent factory, and the "Agent Keys" dialog.
 */
public final class AgentSupport {

    /** Default Windows OpenSSH agent named pipe. */
    public static final String WINDOWS_PIPE = "\\\\.\\pipe\\openssh-ssh-agent";

    private AgentSupport() {
    }

    /** One key as reported by the agent. */
    public record AgentKey(String type, String fingerprint, String comment) {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * The agent endpoint to use (socket path or pipe), or {@code null} if none is available.
     * Prefers {@code $SSH_AUTH_SOCK}; on Windows falls back to the default pipe if present; on
     * Unix falls back to querying a login shell (for desktop-launched processes).
     */
    public static String resolveEndpoint() {
        String sock = System.getenv("SSH_AUTH_SOCK");
        if (sock != null && !sock.isBlank()) {
            return sock;
        }
        if (isWindows()) {
            return canOpen(WINDOWS_PIPE) ? WINDOWS_PIPE : null;
        }
        return loginShellAuthSock();
    }

    /** Open the agent at the preferred endpoint (e.g. a MINA property), else the resolved one. */
    public static SshAgent open(String preferredEndpoint) throws IOException {
        String endpoint = (preferredEndpoint != null && !preferredEndpoint.isBlank())
                ? preferredEndpoint : resolveEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IOException("No ssh-agent endpoint available (is the agent running?)");
        }
        return isWindows() ? new WindowsPipeAgentProxy(endpoint) : new JdkAgentProxy(endpoint);
    }

    public static SshAgent open() throws IOException {
        return open(null);
    }

    /** List the agent's identities (type, SHA-256 fingerprint, comment). */
    public static List<AgentKey> listIdentities() throws IOException {
        List<AgentKey> keys = new ArrayList<>();
        try (SshAgent agent = open()) {
            for (Map.Entry<PublicKey, String> id : agent.getIdentities()) {
                PublicKey key = id.getKey();
                String type = KeyUtils.getKeyType(key);
                keys.add(new AgentKey(
                        (type != null && !type.isBlank()) ? type : key.getAlgorithm(),
                        KeyUtils.getFingerPrint(key),
                        id.getValue()));
            }
        }
        return keys;
    }

    private static boolean canOpen(String path) {
        try (RandomAccessFile ignored = new RandomAccessFile(path, "rw")) {
            return true;
        } catch (FileNotFoundException e) {
            // "All pipe instances are busy" means the agent pipe exists but is momentarily
            // serving another client — the agent is present, so treat the endpoint as available.
            return e.getMessage() != null && e.getMessage().contains("All pipe instances are busy");
        } catch (Exception e) {
            return false;
        }
    }

    private static String loginShellAuthSock() {
        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) {
                shell = "/bin/bash";
            }
            Process p = new ProcessBuilder(shell, "-lic", "printf %s \"$SSH_AUTH_SOCK\"").start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
