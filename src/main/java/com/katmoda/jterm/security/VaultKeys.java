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
