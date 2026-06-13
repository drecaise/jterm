package com.katmoda.jterm.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * A named SSH port-forwarding tunnel, persisted as part of {@code tunnels.json} (see
 * {@link TunnelStore}). Each tunnel references a saved {@link SshSessionConfig} (by
 * {@link #getSshSessionId()}); when started it opens its own dedicated SSH connection (no
 * shell) and attaches one forward to it.
 *
 * <p>Field meaning depends on {@link #getType()} (the forward always binds {@code 127.0.0.1}):</p>
 * <ul>
 *   <li>{@code LOCAL} — listen on {@code 127.0.0.1:listenPort} locally, forward to
 *       {@code destHost:destPort} reached <em>from the SSH server</em> ({@code ssh -L}).</li>
 *   <li>{@code REMOTE} — listen on {@code 127.0.0.1:listenPort} on the SSH server, forward to
 *       {@code destHost:destPort} reached <em>from this machine</em> ({@code ssh -R}).</li>
 *   <li>{@code DYNAMIC} — a local SOCKS proxy on {@code 127.0.0.1:listenPort}; {@code destHost}
 *       / {@code destPort} are unused ({@code ssh -D}).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TunnelConfig {

    /** The kind of SSH port forwarding. */
    public enum TunnelType {
        LOCAL("Local"),
        REMOTE("Remote"),
        DYNAMIC("Dynamic (SOCKS)");

        private final String label;

        TunnelType(String label) {
            this.label = label;
        }

        /** Human-readable name shown in the UI. */
        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id = UUID.randomUUID().toString();
    private String name = "Tunnel";
    private TunnelType type = TunnelType.LOCAL;
    private String sshSessionId;
    private int listenPort;
    private String destHost = "localhost";
    private int destPort;
    private boolean autoStart;

    public TunnelConfig() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TunnelType getType() {
        return type;
    }

    public void setType(TunnelType type) {
        this.type = (type != null) ? type : TunnelType.LOCAL;
    }

    /** Id of the {@link SshSessionConfig} this tunnel connects through (may be {@code null}). */
    public String getSshSessionId() {
        return sshSessionId;
    }

    public void setSshSessionId(String sshSessionId) {
        this.sshSessionId = sshSessionId;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    /** Forward destination host (unused for {@code DYNAMIC}). */
    public String getDestHost() {
        return destHost;
    }

    public void setDestHost(String destHost) {
        this.destHost = destHost;
    }

    /** Forward destination port (unused for {@code DYNAMIC}). */
    public int getDestPort() {
        return destPort;
    }

    public void setDestPort(int destPort) {
        this.destPort = destPort;
    }

    /** Whether this tunnel is started automatically when the app launches. */
    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    /** A copy for editing (so a cancelled dialog leaves the stored config untouched). */
    public TunnelConfig copy() {
        TunnelConfig c = new TunnelConfig();
        c.id = id;
        c.name = name;
        c.type = type;
        c.sshSessionId = sshSessionId;
        c.listenPort = listenPort;
        c.destHost = destHost;
        c.destPort = destPort;
        c.autoStart = autoStart;
        return c;
    }

    @Override
    public String toString() {
        return name;
    }
}
