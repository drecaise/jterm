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
package com.katmoda.jterm.security;

/**
 * Centralizes the string keys under which secrets are stored in the {@link CredentialVault}.
 *
 * <p>The vault is an opaque {@code Map<String, ...>}; these helpers keep the key namespaces
 * consistent across the places that read, write and clean up secrets. Session and jump-host
 * passwords keep their historical bare-{@code id} key for backwards compatibility; everything
 * added later is namespaced so it can't collide with a session/jump-host id.</p>
 */
public final class VaultKeys {

    /** Vault key for the global default SSH password. */
    public static final String GLOBAL_PASSWORD = "global:password";

    /** Vault key for the global default SSH key passphrase. */
    public static final String GLOBAL_KEY_PASSPHRASE = "global:keypass";

    private VaultKeys() {
    }

    /** Session password — the historical bare-id key (unchanged for backwards compatibility). */
    public static String sessionPassword(String sessionId) {
        return sessionId;
    }

    /** Session-level SSH key passphrase. */
    public static String sessionKeyPassphrase(String sessionId) {
        return "keypass:" + sessionId;
    }

    /** Folder-level default SSH password. */
    public static String folderPassword(String folderId) {
        return "folderpw:" + folderId;
    }

    /** Folder-level default SSH key passphrase. */
    public static String folderKeyPassphrase(String folderId) {
        return "folderkey:" + folderId;
    }
}
