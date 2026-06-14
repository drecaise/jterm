package com.katmoda.jterm.session;

import java.util.UUID;

/**
 * A saved RDP (Remote Desktop) connection. Unlike SSH/local sessions this is a full graphical
 * desktop driven by an external FreeRDP process (see {@code com.katmoda.jterm.rdp}); it always
 * opens in its own tab rather than a grid pane. An optional password can be enabled, and (if
 * {@code savePassword}) is stored encrypted in the vault keyed by {@link #id}.
 */
public final class RdpSessionConfig implements SessionNode {

    /** How the remote desktop resolution is chosen. */
    public enum WidthMode {
        /** Track the tab size and let the server resize dynamically (default). */
        FIT_TO_TAB,
        /** Use a fixed {@link #width}×{@link #height}. */
        FIXED
    }

    /** How a fit-to-tab session fills the window. */
    public enum ScalingMode {
        /** Renegotiate the remote resolution to match the window (crisp; needs server + client support). */
        DYNAMIC,
        /** Scale the remote framebuffer to the window (always tracks size; may letterbox/blur). */
        SMART
    }

    /** Connection security level requested from FreeRDP. Never offers an "ignore" option. */
    public enum SecurityMode {
        /** Let FreeRDP negotiate (default). */
        AUTO,
        /** Require Network Level Authentication. */
        NLA,
        /** TLS only. */
        TLS
    }

    private String id = UUID.randomUUID().toString();
    private String name = "rdp";
    private String iconId;
    private String host = "";
    private int port = 3389;
    private String user = System.getProperty("user.name", "");
    private String domain = "";
    private boolean passwordAuth = false;
    private boolean savePassword = false;

    // Display sizing. FIT_TO_TAB tracks the tab and uses /dynamic-resolution; FIXED uses w/h.
    private WidthMode widthMode = WidthMode.FIT_TO_TAB;
    private ScalingMode scalingMode = ScalingMode.DYNAMIC;
    private int width = 1280;
    private int height = 800;
    // Bits per pixel. 0 = Auto: let FreeRDP/the server choose and emit no /bpp flag — some servers
    // refuse to connect when any /bpp is forced, so Auto is the safe default.
    private int colorDepth = 0;

    // Connection security; AUTO lets FreeRDP negotiate.
    private SecurityMode securityMode = SecurityMode.AUTO;

    // When false (default), host certs use trust-on-first-use. When true, all certificate errors
    // (self-signed, name mismatch, expiry) are ignored — insecure, but required for servers whose
    // self-signed cert name doesn't match the host (e.g. GNOME Remote Desktop). Opt-in per session.
    private boolean ignoreCertErrors = false;

    // Resource redirection. Off by default for security; opt-in per session.
    private boolean redirectClipboard = false;
    private boolean redirectDrives = false;
    private boolean redirectAudio = false;

    // Optional RD Gateway "host:port"; empty = connect directly. Reserved for later use.
    private String gateway = "";

    public RdpSessionConfig() {
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

    /** Windows domain (NetBIOS or FQDN), or empty for none. */
    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = (domain != null) ? domain.trim() : "";
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

    public WidthMode getWidthMode() {
        return widthMode;
    }

    public void setWidthMode(WidthMode widthMode) {
        this.widthMode = (widthMode != null) ? widthMode : WidthMode.FIT_TO_TAB;
    }

    /** How a fit-to-tab session fills the window (dynamic resolution vs scaling). */
    public ScalingMode getScalingMode() {
        return scalingMode;
    }

    public void setScalingMode(ScalingMode scalingMode) {
        this.scalingMode = (scalingMode != null) ? scalingMode : ScalingMode.DYNAMIC;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getColorDepth() {
        return colorDepth;
    }

    public void setColorDepth(int colorDepth) {
        this.colorDepth = colorDepth;
    }

    public SecurityMode getSecurityMode() {
        return securityMode;
    }

    public void setSecurityMode(SecurityMode securityMode) {
        this.securityMode = (securityMode != null) ? securityMode : SecurityMode.AUTO;
    }

    /** When true, ignore all TLS certificate errors (insecure); when false, trust-on-first-use. */
    public boolean isIgnoreCertErrors() {
        return ignoreCertErrors;
    }

    public void setIgnoreCertErrors(boolean ignoreCertErrors) {
        this.ignoreCertErrors = ignoreCertErrors;
    }

    public boolean isRedirectClipboard() {
        return redirectClipboard;
    }

    public void setRedirectClipboard(boolean redirectClipboard) {
        this.redirectClipboard = redirectClipboard;
    }

    public boolean isRedirectDrives() {
        return redirectDrives;
    }

    public void setRedirectDrives(boolean redirectDrives) {
        this.redirectDrives = redirectDrives;
    }

    public boolean isRedirectAudio() {
        return redirectAudio;
    }

    public void setRedirectAudio(boolean redirectAudio) {
        this.redirectAudio = redirectAudio;
    }

    /** Optional RD Gateway as {@code "host:port"}, or empty for a direct connection. */
    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = (gateway != null) ? gateway.trim() : "";
    }
}
