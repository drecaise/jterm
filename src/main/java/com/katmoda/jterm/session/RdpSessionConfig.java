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
package com.katmoda.jterm.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * A saved RDP (Remote Desktop) connection, opened by the out-of-process FreeRDP helper
 * ({@code native/rdp-helper}) into an {@code RdpPane}. A saved password lives in the vault keyed by
 * {@link #id}, exactly like an SSH session's.
 *
 * <p><b>Unknown properties are ignored on purpose.</b> {@code JsonStore}'s mapper fails on unknown
 * fields, and a failure anywhere quarantines the <em>whole</em> {@code sessions.json}. RDP settings
 * will grow (and some fields written by the 2026-06 prototype branch — security mode, colour depth,
 * redirection, gateway, {@code ignoreCertErrors} — are deliberately not carried), so a field this
 * build doesn't know must never cost the user their session tree. Field names match that prototype
 * so anything it saved still loads.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RdpSessionConfig implements SessionNode {

    public static final int DEFAULT_PORT = 3389;
    public static final int DEFAULT_GATEWAY_PORT = 443;

    /** How the remote desktop is sized. */
    public enum WidthMode {
        /** Follow the pane: the desktop is re-laid-out to the pane's size via Display Control. */
        FIT_TO_TAB,
        /** A fixed resolution ({@link #getWidth()} x {@link #getHeight()}), scaled to the pane. */
        FIXED
    }

    private String id = UUID.randomUUID().toString();
    private String name = "rdp";
    private String iconId;
    private String host = "";
    private int port = DEFAULT_PORT;
    private String user = "";
    private String domain = "";
    private boolean savePassword = false;
    private WidthMode widthMode = WidthMode.FIT_TO_TAB;
    private int width = 1280;
    private int height = 800;
    private boolean redirectClipboard = false;
    private boolean clipboardToRemote = true;
    private boolean clipboardFilesToRemote = false;
    private boolean clipboardFilesToLocal = false;
    private boolean playSound = false;
    private boolean useMicrophone = false;
    private boolean printToJterm = false;
    private boolean printerIsDefault = false;
    private String autoPrintPrinter = "";
    private boolean useSmartCards = false;
    private java.util.List<String> smartCardReaders = new java.util.ArrayList<>();
    private boolean allowTlsWithoutNla = false;
    private boolean useKerberos = false;
    private String sshTunnelSessionId = "";
    private String gatewayHost = "";
    private int gatewayPort = DEFAULT_GATEWAY_PORT;
    private boolean gatewaySameCredentials = true;
    private String gatewayUser = "";
    private String gatewayDomain = "";
    private boolean saveGatewayPassword = false;
    private java.util.List<RdpDriveShare> driveShares = new java.util.ArrayList<>();

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
        this.host = host != null ? host.trim() : "";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = (port > 0 && port <= 65535) ? port : DEFAULT_PORT;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user != null ? user.trim() : "";
    }

    /** Windows domain (NetBIOS or FQDN), or empty for none. */
    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain != null ? domain.trim() : "";
    }

    /** Whether a password for this session is stored in the vault. */
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
        this.widthMode = widthMode != null ? widthMode : WidthMode.FIT_TO_TAB;
    }

    /** Fixed-mode width in device pixels. */
    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = clampDimension(width, 1280);
    }

    /** Fixed-mode height in device pixels. */
    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = clampDimension(height, 800);
    }

    /**
     * Whether the clipboard is shared with the remote desktop (text and images). Off by default: a
     * session opens the clipboard channel only when this is set, so the server never even sees it.
     * When on, what the server copies lands on the local clipboard without a prompt.
     */
    public boolean isRedirectClipboard() {
        return redirectClipboard;
    }

    public void setRedirectClipboard(boolean redirectClipboard) {
        this.redirectClipboard = redirectClipboard;
    }

    /**
     * Plays the remote desktop's sound on this computer. Off by default: with it off no audio
     * channel is opened, so the server cannot tell jterm has sound.
     */
    public boolean isPlaySound() {
        return playSound;
    }

    public void setPlaySound(boolean playSound) {
        this.playSound = playSound;
    }

    /**
     * Whether the remote desktop may ask for this computer's microphone. Off by default; when off no
     * microphone channel is opened. When on, jterm still asks the user once per connection before
     * the server hears anything, and sends silence until then. Never exported, and ignored in an
     * import (see {@code SessionExportService}): an imported file must not arrive with it on.
     */
    public boolean isUseMicrophone() {
        return useMicrophone;
    }

    public void setUseMicrophone(boolean useMicrophone) {
        this.useMicrophone = useMicrophone;
    }

    /**
     * Whether the remote desktop gets a printer, "jterm", whose jobs arrive here as PDFs and wait in
     * the session's bar until the user prints, saves or discards them. Off by default; when off no
     * printer is announced. Exported (it names nothing local).
     */
    public boolean isPrintToJterm() {
        return printToJterm;
    }

    public void setPrintToJterm(boolean printToJterm) {
        this.printToJterm = printToJterm;
    }

    /** With {@link #isPrintToJterm()}, whether that printer is the remote session's default. */
    public boolean isPrinterIsDefault() {
        return printerIsDefault;
    }

    public void setPrinterIsDefault(boolean printerIsDefault) {
        this.printerIsDefault = printerIsDefault;
    }

    /**
     * With {@link #isPrintToJterm()}, the local printer jobs go to without asking (within the caps
     * of {@code AutoPrintLimiter}); blank means every job waits for the user. A local printer's
     * name, so it is left out of exports and ignored in imports (see {@code SessionExportService}).
     */
    public String getAutoPrintPrinter() {
        return autoPrintPrinter;
    }

    public void setAutoPrintPrinter(String autoPrintPrinter) {
        this.autoPrintPrinter = autoPrintPrinter == null ? "" : autoPrintPrinter;
    }

    /**
     * Whether the remote desktop may use smart cards in the readers of {@link #getSmartCardReaders()}
     * (smart-card design S1, S2): jterm asks before the first card is used in a connection. Off by
     * default; when off, or with no reader picked, the server sees no smart-card device. Left out of
     * exports and ignored in imports, like the microphone: an import must not enable a local device.
     */
    public boolean isUseSmartCards() {
        return useSmartCards;
    }

    public void setUseSmartCards(boolean useSmartCards) {
        this.useSmartCards = useSmartCards;
    }

    /**
     * The local readers the remote desktop may see, by name (pcsc-lite's " 00 00" index is ignored
     * when matching). Machine-specific: left out of exports and ignored in imports.
     */
    public java.util.List<String> getSmartCardReaders() {
        return smartCardReaders;
    }

    public void setSmartCardReaders(java.util.List<String> smartCardReaders) {
        this.smartCardReaders = smartCardReaders == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(smartCardReaders);
    }

    /**
     * With {@link #isRedirectClipboard()} on, whether the remote desktop may also paste what is on
     * the local clipboard. Without it sharing is one-way, remote to local. Meaningless (and ignored)
     * while sharing is off.
     */
    public boolean isClipboardToRemote() {
        return clipboardToRemote;
    }

    public void setClipboardToRemote(boolean clipboardToRemote) {
        this.clipboardToRemote = clipboardToRemote;
    }

    /**
     * With {@link #isClipboardToRemote()} on, whether the remote desktop may also paste files copied
     * on this computer (each copy still asks first). Off by default: unlike text, a file list can
     * reach far more than the user meant to share. Ignored unless both clipboard settings are on.
     * Names no local path, so it is exported like the other clipboard settings.
     */
    public boolean isClipboardFilesToRemote() {
        return clipboardFilesToRemote;
    }

    public void setClipboardFilesToRemote(boolean clipboardFilesToRemote) {
        this.clipboardFilesToRemote = clipboardFilesToRemote;
    }

    /**
     * With {@link #isRedirectClipboard()} on, whether files copied on the remote desktop can be saved
     * on this computer ("Save files..." in the pane; nothing is written until the user picks a
     * folder). Off by default. Names no local path, so it is exported like the other settings.
     */
    public boolean isClipboardFilesToLocal() {
        return clipboardFilesToLocal;
    }

    public void setClipboardFilesToLocal(boolean clipboardFilesToLocal) {
        this.clipboardFilesToLocal = clipboardFilesToLocal;
    }

    /**
     * Whether the session also accepts a server that offers TLS but not NLA (xrdp, or a Windows
     * host with NLA turned off). Off by default: NLA makes the server prove it knows the password
     * before the password is sent, which protects it even if a wrong certificate is accepted on
     * first connect. TLS without NLA still checks the certificate. The legacy RDP security layer is
     * refused either way.
     */
    /**
     * Windows only: sign in with Windows' own authentication (Kerberos, as well as NTLM) instead of
     * the helper's built-in NTLM. Windows won't serve its authentication to a process with a
     * restricted token, so such a session's helper runs without one (sandbox design, W2): only the
     * W1 restrictions apply, and a compromised helper could read the user's files and saved
     * passwords. Off by default. Never exported, and ignored in an import (see
     * {@code SessionExportService}): an imported file must not arrive with the sandbox weakened.
     */
    public boolean isUseKerberos() {
        return useKerberos;
    }

    public void setUseKerberos(boolean useKerberos) {
        this.useKerberos = useKerberos;
    }

    public boolean isAllowTlsWithoutNla() {
        return allowTlsWithoutNla;
    }

    public void setAllowTlsWithoutNla(boolean allowTlsWithoutNla) {
        this.allowTlsWithoutNla = allowTlsWithoutNla;
    }

    /**
     * The id of a saved SSH session to reach the RDP host through, or empty to connect directly.
     * Through it, {@link #getHost()}:{@link #getPort()} is the address <em>as seen from the SSH
     * server</em> ({@code localhost} for a desktop on the SSH server itself), and the SSH session's
     * own jump hosts, keys and saved password apply.
     */
    public String getSshTunnelSessionId() {
        return sshTunnelSessionId;
    }

    public void setSshTunnelSessionId(String sshTunnelSessionId) {
        this.sshTunnelSessionId = sshTunnelSessionId != null ? sshTunnelSessionId.trim() : "";
    }

    /** Whether this session connects through a saved SSH session. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isViaSsh() {
        return !sshTunnelSessionId.isEmpty();
    }

    /**
     * The RD Gateway to connect through, or empty for none. Exclusive with
     * {@link #getSshTunnelSessionId()}: the dialog offers one route or the other.
     */
    public String getGatewayHost() {
        return gatewayHost;
    }

    public void setGatewayHost(String gatewayHost) {
        this.gatewayHost = gatewayHost != null ? gatewayHost.trim() : "";
    }

    public int getGatewayPort() {
        return gatewayPort;
    }

    public void setGatewayPort(int gatewayPort) {
        this.gatewayPort = gatewayPort >= 1 && gatewayPort <= 65535 ? gatewayPort : DEFAULT_GATEWAY_PORT;
    }

    /** Whether the gateway takes the desktop's user, domain and password (the usual setup). */
    public boolean isGatewaySameCredentials() {
        return gatewaySameCredentials;
    }

    public void setGatewaySameCredentials(boolean gatewaySameCredentials) {
        this.gatewaySameCredentials = gatewaySameCredentials;
    }

    public String getGatewayUser() {
        return gatewayUser;
    }

    public void setGatewayUser(String gatewayUser) {
        this.gatewayUser = gatewayUser != null ? gatewayUser.trim() : "";
    }

    public String getGatewayDomain() {
        return gatewayDomain;
    }

    public void setGatewayDomain(String gatewayDomain) {
        this.gatewayDomain = gatewayDomain != null ? gatewayDomain.trim() : "";
    }

    /** With separate gateway credentials: whether its password is kept in the vault. */
    public boolean isSaveGatewayPassword() {
        return saveGatewayPassword;
    }

    public void setSaveGatewayPassword(boolean saveGatewayPassword) {
        this.saveGatewayPassword = saveGatewayPassword;
    }

    /**
     * Local folders shared with the remote desktop as drives; empty means device redirection stays
     * off. Never exported (see {@link SessionExportService}).
     */
    public java.util.List<RdpDriveShare> getDriveShares() {
        return driveShares;
    }

    public void setDriveShares(java.util.List<RdpDriveShare> driveShares) {
        this.driveShares = new java.util.ArrayList<>();
        if (driveShares != null) {
            for (RdpDriveShare d : driveShares) {
                if (d != null && !d.getPath().isBlank()) {
                    this.driveShares.add(d);
                }
            }
        }
    }

    /** Whether this session connects through an RD Gateway. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isViaGateway() {
        return !gatewayHost.isEmpty();
    }

    /** The gateway's {@code host} or {@code host:port} when the port isn't 443. */
    public String gatewayLabel() {
        return gatewayPort == DEFAULT_GATEWAY_PORT ? gatewayHost : gatewayHost + ":" + gatewayPort;
    }

    /** {@code host} or {@code host:port} when the port isn't the default — for titles and prompts. */
    public String hostLabel() {
        return port == DEFAULT_PORT ? host : host + ":" + port;
    }

    /** A copy of every editable setting, under a fresh id (and so with no saved password). */
    public RdpSessionConfig copyWithNewId() {
        RdpSessionConfig copy = new RdpSessionConfig();
        copy.setName(name);
        copy.setIconId(iconId);
        copy.setHost(host);
        copy.setPort(port);
        copy.setUser(user);
        copy.setDomain(domain);
        copy.setWidthMode(widthMode);
        copy.setWidth(width);
        copy.setHeight(height);
        copy.setRedirectClipboard(redirectClipboard);
        copy.setClipboardToRemote(clipboardToRemote);
        copy.setClipboardFilesToRemote(clipboardFilesToRemote);
        copy.setClipboardFilesToLocal(clipboardFilesToLocal);
        copy.setPlaySound(playSound);
        copy.setUseMicrophone(useMicrophone);
        copy.setPrintToJterm(printToJterm);
        copy.setPrinterIsDefault(printerIsDefault);
        copy.setAutoPrintPrinter(autoPrintPrinter);
        copy.setUseSmartCards(useSmartCards);
        copy.setSmartCardReaders(smartCardReaders);
        copy.setAllowTlsWithoutNla(allowTlsWithoutNla);
        copy.setUseKerberos(useKerberos);
        copy.setSshTunnelSessionId(sshTunnelSessionId);
        copy.setGatewayHost(gatewayHost);
        copy.setGatewayPort(gatewayPort);
        copy.setGatewaySameCredentials(gatewaySameCredentials);
        copy.setGatewayUser(gatewayUser);
        copy.setGatewayDomain(gatewayDomain);
        copy.setDriveShares(driveShares.stream().map(RdpDriveShare::copy).toList());
        // The saved gateway password stays with the original, like the session password.
        return copy;
    }

    /** RDP (MS-RDPEDISP) monitor bounds: 200..8192. Out-of-range values fall back. */
    private static int clampDimension(int value, int fallback) {
        return (value >= 200 && value <= 8192) ? value : fallback;
    }
}
