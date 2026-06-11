package com.katmoda.jterm.security;

import com.katmoda.jterm.ui.security.MasterPasswordDialog;

import java.awt.Component;
import java.util.Arrays;
import java.util.Optional;

/**
 * Orchestrates unlocking the {@link CredentialVault}, transparently using the OS keyring to
 * remember the master password and falling back to prompting when needed.
 *
 * <p>Flow (all on the EDT):</p>
 * <ol>
 *   <li>Not initialized → prompt to <b>create</b> a master password, initialize, and store it
 *       in the keyring (if available).</li>
 *   <li>Initialized, locked → try the keyring; on a miss/unavailable, prompt to <b>enter</b>
 *       the master password, then store it in the keyring on success.</li>
 * </ol>
 */
public final class VaultManager {

    private static final VaultManager INSTANCE = new VaultManager();

    private final CredentialVault vault = new CredentialVault();
    private final MasterPasswordKeyring keyring = new MasterPasswordKeyring();

    private VaultManager() {
    }

    public static VaultManager get() {
        return INSTANCE;
    }

    public CredentialVault vault() {
        return vault;
    }

    /**
     * Ensures the vault is unlocked, prompting/creating as needed.
     *
     * @return true if unlocked and ready, false if the user cancelled.
     */
    public boolean ensureUnlocked(Component parent) {
        if (vault.isUnlocked()) {
            return true;
        }
        return vault.isInitialized() ? unlockExisting(parent) : createNew(parent);
    }

    private boolean createNew(Component parent) {
        char[] master = MasterPasswordDialog.promptCreate(parent);
        if (master == null) {
            return false;
        }
        try {
            vault.initialize(master);
            keyring.store(master);
            return true;
        } catch (VaultException e) {
            return false;
        } finally {
            Arrays.fill(master, '\0');
        }
    }

    private boolean unlockExisting(Component parent) {
        // 1. Try the keyring transparently.
        Optional<char[]> stored = keyring.retrieve();
        if (stored.isPresent()) {
            char[] master = stored.get();
            try {
                if (vault.unlock(master)) {
                    return true;
                }
                keyring.clear(); // stale entry — fall through to prompting
            } catch (VaultException ignored) {
                // fall through to prompting
            } finally {
                Arrays.fill(master, '\0');
            }
        }

        // 2. Prompt, retrying on a wrong password.
        String error = null;
        while (true) {
            char[] master = MasterPasswordDialog.promptEnter(parent, error);
            if (master == null) {
                return false;
            }
            try {
                if (vault.unlock(master)) {
                    keyring.store(master);
                    return true;
                }
                error = "Incorrect master password — try again.";
            } catch (VaultException e) {
                error = "Could not unlock the vault.";
            } finally {
                Arrays.fill(master, '\0');
            }
        }
    }
}
