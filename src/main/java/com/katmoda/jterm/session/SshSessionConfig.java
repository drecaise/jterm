package com.katmoda.jterm.session;

import java.util.UUID;

/**
 * A saved SSH connection. Auth tries ssh-agent + on-disk keys first; an optional password
 * fallback can be enabled, and (if {@code savePassword}) is stored encrypted in the vault
 * keyed by {@link #id}.
 */
public final class SshSessionConfig implements SessionNode {

    private String id = UUID.randomUUID().toString();
    private String name = "ssh";
    private String iconId;
    private String host = "";
    private int port = 22;
    private String user = System.getProperty("user.name", "");
    private boolean agentForwarding = true;
    private boolean passwordAuth = false;
    private boolean savePassword = false;

    // Terminal settings. Empty/zero means "use the application default" (configured in
    // Preferences ▸ Terminal Settings); new sessions inherit all of them by default.
    private String terminalType = "";
    private String terminalCharset = "";
    private String fontFamily = "";
    private int fontSize = 0;

    public SshSessionConfig() {
    }

    /** Stable identifier used as the vault key for a saved password. */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getIconId() {
        return iconId;
    }

    @Override
    public void setIconId(String iconId) {
        this.iconId = iconId;
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

    public boolean isAgentForwarding() {
        return agentForwarding;
    }

    public void setAgentForwarding(boolean agentForwarding) {
        this.agentForwarding = agentForwarding;
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

    /** Remote terminal type sent as the pty type (e.g. {@code xterm-256color}, {@code vt100}). */
    public String getTerminalType() {
        return terminalType;
    }

    public void setTerminalType(String terminalType) {
        this.terminalType = terminalType;
    }

    /** Charset used to decode/encode the terminal stream (e.g. {@code UTF-8}). */
    public String getTerminalCharset() {
        return terminalCharset;
    }

    public void setTerminalCharset(String terminalCharset) {
        this.terminalCharset = terminalCharset;
    }

    /** Terminal font family; empty means use the application default. */
    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    /** Terminal font size in points; {@code 0} means use the application default. */
    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }
}
