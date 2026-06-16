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

    // Path to a private key file to authenticate this hop with; empty means none
    // ({@code ~/} expanded). Mirrors the main host's key-file option.
    private String keyPath = "";

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

    /** Path to a private key file for this hop, or empty for none ({@code ~/} expanded). */
    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = (keyPath != null) ? keyPath.trim() : "";
    }
}
