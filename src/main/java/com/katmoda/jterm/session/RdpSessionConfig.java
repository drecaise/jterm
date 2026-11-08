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
        return copy;
    }

    /** RDP (MS-RDPEDISP) monitor bounds: 200..8192. Out-of-range values fall back. */
    private static int clampDimension(int value, int fallback) {
        return (value >= 200 && value <= 8192) ? value : fallback;
    }
}
