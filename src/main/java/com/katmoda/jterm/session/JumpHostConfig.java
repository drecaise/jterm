package com.katmoda.jterm.session;

import java.util.UUID;

/**
 * One jump (bastion) host in an {@link SshSessionConfig}'s connection chain. Hops are connected
 * in list order ({@code jumpHosts[0]} first), each tunneling the next, before the final target.
 *
 * <p>Auth mirrors the main host: ssh-agent + on-disk keys always apply (they are installed once
 * on the shared client), and an optional password fallback can be enabled and, if
 * {@code savePassword}, stored encrypted in the vault keyed by {@link #id}.</p>
 */
public final class JumpHostConfig {

    private String id = UUID.randomUUID().toString();
    private String host = "";
    private int port = 22;
    private String user = System.getProperty("user.name", "");
    private boolean passwordAuth = false;
    private boolean savePassword = false;

    public JumpHostConfig() {
    }

    /** Stable identifier used as the vault key for this hop's saved password. */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public boolean isPasswordAuth() {
        return passwordAuth;
    }

    public void setPasswordAuth(boolean passwordAuth) {
        this.passwordAuth = passwordAuth;
    }

    public boolean isSavePassword() {
        return savePassword;
    }

    public void setSavePassword(boolean savePassword) {
        this.savePassword = savePassword;
    }
}
