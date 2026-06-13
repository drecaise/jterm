package com.katmoda.jterm.terminal.ssh;

import com.katmoda.jterm.session.TunnelConfig;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the running SSH tunnels and owns their forward lifecycle. A small singleton mirroring
 * the other live managers ({@code security.VaultManager}, {@code macro.MacroLibrary}).
 *
 * <p>Opening the dedicated SSH connection is the caller's job (it needs EDT credential prompts
 * then an off-EDT blocking connect — see {@code MainWindow.startTunnelAsync}); this manager only
 * attaches the right forward to the supplied connection and tears it down on stop, keeping it
 * free of any UI dependency.</p>
 */
public final class TunnelManager {

    /** A running tunnel: its connection, the bound listen address, and the forward kind. */
    private record ActiveTunnel(SshConnect.Connected connection, SshdSocketAddress bound,
                                TunnelConfig.TunnelType type) {
    }

    private static final TunnelManager INSTANCE = new TunnelManager();

    /** Loopback bind host: local/dynamic forwards and the server-side remote bind all use it. */
    private static final String BIND_HOST = "127.0.0.1";

    private final Map<String, ActiveTunnel> active = new ConcurrentHashMap<>();

    private TunnelManager() {
    }

    public static TunnelManager get() {
        return INSTANCE;
    }

    public boolean isRunning(String tunnelId) {
        return active.containsKey(tunnelId);
    }

    /** The bound listen address of a running tunnel (for status display), or {@code null}. */
    public SshdSocketAddress boundAddress(String tunnelId) {
        ActiveTunnel t = active.get(tunnelId);
        return t != null ? t.bound() : null;
    }

    /**
     * Attaches {@code cfg}'s forward to the already-opened {@code connection} and records it as
     * running. On any failure the connection is closed and the exception rethrown, so the caller
     * need not own teardown of a half-started tunnel. A tunnel already running under the same id
     * is stopped first.
     */
    public void start(TunnelConfig cfg, SshConnect.Connected connection) throws IOException {
        stop(cfg.getId());
        try {
            SshdSocketAddress bound = openForward(cfg, connection);
            active.put(cfg.getId(), new ActiveTunnel(connection, bound, cfg.getType()));
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private static SshdSocketAddress openForward(TunnelConfig cfg, SshConnect.Connected connection)
            throws IOException {
        var session = connection.session();
        return switch (cfg.getType()) {
            case LOCAL -> session.startLocalPortForwarding(
                    new SshdSocketAddress(BIND_HOST, cfg.getListenPort()),
                    new SshdSocketAddress(cfg.getDestHost(), cfg.getDestPort()));
            case REMOTE -> session.startRemotePortForwarding(
                    new SshdSocketAddress(BIND_HOST, cfg.getListenPort()),
                    new SshdSocketAddress(cfg.getDestHost(), cfg.getDestPort()));
            case DYNAMIC -> session.startDynamicPortForwarding(
                    new SshdSocketAddress(BIND_HOST, cfg.getListenPort()));
        };
    }

    /** Stops the tunnel with {@code tunnelId} (no-op if not running): drops the forward and closes its connection. */
    public void stop(String tunnelId) {
        ActiveTunnel t = active.remove(tunnelId);
        if (t == null) {
            return;
        }
        try {
            var session = t.connection().session();
            switch (t.type()) {
                case LOCAL -> session.stopLocalPortForwarding(t.bound());
                case REMOTE -> session.stopRemotePortForwarding(t.bound());
                case DYNAMIC -> session.stopDynamicPortForwarding(t.bound());
            }
        } catch (Exception ignored) {
            // Best-effort: closing the connection below tears the forward down regardless.
        }
        t.connection().close();
    }

    /** Stops every running tunnel (for application shutdown). */
    public void stopAll() {
        for (String id : active.keySet().toArray(new String[0])) {
            stop(id);
        }
    }
}
