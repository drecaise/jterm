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

import com.katmoda.jterm.session.JumpHostConfig;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.ssh.SshConnect;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the credentials (passwords, key passphrases, jump-host chains) needed to open an SSH
 * connection: it consults the {@link CredentialVault} (via the injected {@link VaultAccess}) and,
 * when nothing is stored, prompts through the injected {@link Prompts}. Prompts are marshalled onto
 * the EDT; the resolver itself carries no UI, so it is headless-testable with fake collaborators.
 */
public final class CredentialResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialResolver.class);

    /** Vault gateway, injectable for tests (production: adapter over VaultManager.get() + owner frame). */
    public interface VaultAccess {
        boolean ensureUnlocked();          // may prompt; true when the vault is usable
        CredentialVault vault();
    }

    /** UI prompt callbacks. Implementations show Swing dialogs; the resolver marshals to the EDT. */
    public interface Prompts {
        /** {@code hostLabel}/{@code error}/{@code allowRemember} may be null/false. Null = cancelled. */
        SessionPassword promptSessionPassword(String sessionName, String hostLabel, String error,
                                              boolean allowRemember);
        KeyPassphrase promptKeyPassphrase(String keyPath, String error, boolean allowRemember); // null = cancelled
        /** One answer per prompt (masked where {@code echo[i]} is false). Null = cancelled. */
        String[] promptChallenge(String hostLabel, String instruction, String[] prompts, boolean[] echo);
    }

    /** Outcome of a key-passphrase prompt: the entered passphrase and whether to remember it. */
    public record KeyPassphrase(char[] passphrase, boolean remember) { }

    /** Outcome of a session-password prompt: the entered password and whether to remember it. */
    public record SessionPassword(char[] password, boolean remember) { }

    private final SessionStore sessionStore;
    private final VaultAccess vault;
    private final Prompts prompts;

    public CredentialResolver(SessionStore sessionStore, VaultAccess vault, Prompts prompts) {
        this.sessionStore = sessionStore;
        this.vault = vault;
        this.prompts = prompts;
    }

    /**
     * Resolves the password to try for the target session (EDT): {@code null} if password auth is
     * off. Otherwise the cascade is the session's own saved password (when {@code savePassword}),
     * then the inherited folder/global default password, then a one-time prompt. The inherited
     * defaults apply only when password auth is enabled (so a session never silently authenticates
     * with a shared password it didn't opt into).
     */
    public String resolvePassword(SshSessionConfig cfg) {
        if (!cfg.isPasswordAuth()) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        if (cfg.isSavePassword()) {
            keys.add(VaultKeys.sessionPassword(cfg.getId()));
        }
        keys.addAll(sessionStore.defaultPasswordVaultKeys(cfg));
        String secret = resolveVaultSecret(keys);
        if (secret != null) {
            return secret;
        }
        return promptSessionPasswordOnEdt(cfg.getName(), null, null, false);
    }

    /**
     * Returns the first secret present (and decryptable) among {@code vaultKeys}, unlocking the
     * vault on demand. {@code null} if none is stored or the unlock is cancelled/fails.
     */
    private String resolveVaultSecret(List<String> vaultKeys) {
        for (String key : vaultKeys) {
            if (vault.vault().hasPassword(key)) {
                if (!vault.ensureUnlocked()) {
                    return null;
                }
                try {
                    return vault.vault().getPassword(key);
                } catch (VaultException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the password to try for one host (EDT): {@code null} if password auth is off; a
     * saved password unlocked from the vault (via keyring or prompt); otherwise a one-time prompt.
     * A cancelled prompt yields {@code null}, so connection falls back to agent/key auth.
     */
    private String resolvePassword(String id, boolean passwordAuth, boolean savePassword,
                                   String promptName) {
        if (!passwordAuth) {
            return null;
        }
        if (savePassword && vault.vault().hasPassword(id)) {
            if (!vault.ensureUnlocked()) {
                return null;
            }
            try {
                return vault.vault().getPassword(id);
            } catch (VaultException e) {
                return null;
            }
        }
        return promptSessionPasswordOnEdt(promptName, null, null, false);
    }

    /**
     * Builds the jump-host chain (EDT) for {@code cfg}, resolving each hop's password up front.
     * Hops with a blank host are skipped defensively (the dialog already drops them).
     */
    public List<SshConnect.HostHop> resolveJumpHosts(SshSessionConfig cfg) {
        List<SshConnect.HostHop> hops = new ArrayList<>();
        for (JumpHostConfig jh : cfg.getJumpHosts()) {
            if (jh.getHost() == null || jh.getHost().isBlank()) {
                continue;
            }
            String label = jh.getUser() + "@" + jh.getHost();
            String pw = resolvePassword(jh.getId(), jh.isPasswordAuth(), jh.isSavePassword(), label);
            hops.add(new SshConnect.HostHop(jh.getHost(), jh.getPort(), jh.getUser(), pw,
                    jh.getKeyPath(), jh.getId()));
        }
        return hops;
    }

    /**
     * Builds the passphrase provider for a connect (the SSH connect runs off the EDT, so prompts
     * are marshalled onto it). For the session's effective key it tries the saved passphrase first
     * (attempt 0) and offers to remember a newly entered one (unless the session is
     * {@linkplain SshSessionConfig#isEphemeral() ephemeral}); for any other key (jump-host keys,
     * auto-discovered {@code ~/.ssh} identities) it simply prompts. A wrong passphrase re-prompts
     * (with an error) until MINA gives up; cancelling skips the key so other auth still applies.
     *
     * @param cfg              the session being connected (its id keys a remembered passphrase)
     * @param effectiveKeyPath the resolved configured key path, or {@code null} if none
     */
    public SshConnect.PassphraseProvider keyPassphraseProvider(SshSessionConfig cfg,
                                                               String effectiveKeyPath) {
        String expectedKey = SshConnect.resolveKeyPath(effectiveKeyPath);
        String savedPassphrase = resolveSavedPassphrase(cfg, effectiveKeyPath);
        return new SshConnect.PassphraseProvider() {
            // Passphrases the user asked to remember, awaiting a successful decrypt to persist.
            private final Map<String, String> pendingRemember = new HashMap<>();

            @Override
            public String passphraseFor(String keyPath, int attempt) {
                boolean isSessionKey = expectedKey != null
                        && expectedKey.equals(SshConnect.resolveKeyPath(keyPath));
                if (attempt == 0 && isSessionKey && savedPassphrase != null) {
                    return savedPassphrase; // try the saved one silently first
                }
                // An ephemeral (quick-connect) session may still *use* an inherited saved
                // passphrase, but nothing may be remembered against its throwaway id.
                boolean canRemember = isSessionKey && !cfg.isEphemeral();
                String error = attempt > 0 ? "Incorrect passphrase — try again." : null;
                KeyPassphrase result = promptPassphraseOnEdt(keyPath, error, canRemember);
                if (result == null) {
                    return null;
                }
                String passphrase = new String(result.passphrase());
                Arrays.fill(result.passphrase(), '\0');
                if (result.remember() && canRemember) {
                    pendingRemember.put(SshConnect.resolveKeyPath(keyPath), passphrase);
                }
                return passphrase;
            }

            @Override
            public void onAccepted(String keyPath) {
                String passphrase = pendingRemember.remove(SshConnect.resolveKeyPath(keyPath));
                if (passphrase != null) {
                    saveSessionPassphrase(cfg.getId(), passphrase);
                }
            }
        };
    }

    /**
     * Builds the interactive-authentication fallback for a connect: what jterm answers when
     * ssh-agent and key authentication have been exhausted and the server still offers password or
     * keyboard-interactive auth. The SSH connect runs off the EDT, so prompts are marshalled onto
     * it (as with {@link #keyPassphraseProvider}).
     *
     * <p>The re-prompt carries an error line, so a wrong saved password no longer dead-ends the
     * connect. Remembering is offered only for the hop that <em>is</em> {@code cfg} (not jump
     * hosts, whose credentials are edited elsewhere, and not an
     * {@linkplain SshSessionConfig#isEphemeral() ephemeral} quick connect, whose id is
     * throwaway), and takes effect once that hop actually authenticates — a password that turned
     * out to be wrong is never persisted.</p>
     */
    public SshConnect.InteractiveAuth interactiveAuth(SshSessionConfig cfg) {
        return new SshConnect.InteractiveAuth() {
            // Password awaiting a successful authentication before it is persisted. Written on the
            // MINA auth thread that asked for it, read on the connect thread that sees auth
            // succeed, hence volatile.
            private volatile String pendingRemember;

            @Override
            public String passwordFor(SshConnect.HostHop hop, int attempt) {
                boolean isTarget = hop.id() != null && hop.id().equals(cfg.getId());
                String name = isTarget ? cfg.getName() : hop.label();
                // Never offer to remember against an ephemeral (quick-connect) config: its id dies
                // with the tab, so the vault entry would be unreachable and undeletable.
                boolean canRemember = isTarget && !cfg.isEphemeral();
                String error = attempt > 0 ? "Authentication failed — try again." : null;
                SessionPassword result =
                        promptSessionPasswordResultOnEdt(name, hop.label(), error, canRemember);
                if (result == null) {
                    return null;
                }
                String password = new String(result.password());
                Arrays.fill(result.password(), '\0');
                if (result.remember() && canRemember) {
                    pendingRemember = password;
                }
                return password;
            }

            @Override
            public String[] challenge(SshConnect.HostHop hop, String instruction, String[] prompts,
                                      boolean[] echo) {
                return promptChallengeOnEdt(hop.label(), instruction, prompts, echo);
            }

            @Override
            public void onAuthSucceeded(SshConnect.HostHop hop) {
                String password = pendingRemember;
                pendingRemember = null;
                if (password != null) {
                    saveSessionPassword(cfg, password);
                }
            }
        };
    }

    /**
     * Persists a password the user asked to remember (EDT-marshalled; best-effort). Both session
     * flags have to be set alongside the vault entry: {@link #resolvePassword} only consults the
     * vault when the session has password auth enabled and is marked as saving its password.
     */
    private void saveSessionPassword(SshSessionConfig cfg, String password) {
        runOnEdt(() -> {
            if (!vault.ensureUnlocked()) {
                return;
            }
            try {
                vault.vault().setPassword(VaultKeys.sessionPassword(cfg.getId()),
                        password.toCharArray());
            } catch (VaultException e) {
                // Remembering a password is a convenience; a failed save shouldn't break connect.
                LOG.warn("failed to save the session password to the vault", e);
                return;
            }
            cfg.setPasswordAuth(true);
            cfg.setSavePassword(true);
            sessionStore.save();
        });
    }

    /** Reads the saved passphrase for {@code cfg}'s configured key (cascade), or {@code null}. */
    String resolveSavedPassphrase(SshSessionConfig cfg, String effectiveKeyPath) {
        if (effectiveKeyPath == null || effectiveKeyPath.isBlank()) {
            return null; // no configured key → nothing to attach a saved passphrase to
        }
        return resolveVaultSecret(sessionStore.keyPassphraseVaultKeys(cfg));
    }

    /** Persists a remembered passphrase at the session level (EDT-marshalled; best-effort). */
    private void saveSessionPassphrase(String sessionId, String passphrase) {
        Runnable save = () -> {
            if (!vault.ensureUnlocked()) {
                return;
            }
            try {
                vault.vault().setPassword(VaultKeys.sessionKeyPassphrase(sessionId),
                        passphrase.toCharArray());
            } catch (VaultException e) {
                // Remembering a passphrase is a convenience; a failed save shouldn't break connect.
                LOG.warn("failed to save key passphrase to the vault", e);
            }
        };
        runOnEdt(save);
    }

    /** Shows the session-password prompt on the EDT and returns the entered text (or {@code null}). */
    private String promptSessionPasswordOnEdt(String sessionName, String hostLabel, String error,
                                              boolean allowRemember) {
        SessionPassword result =
                promptSessionPasswordResultOnEdt(sessionName, hostLabel, error, allowRemember);
        if (result == null) {
            return null;
        }
        String password = new String(result.password());
        Arrays.fill(result.password(), '\0');
        return password;
    }

    /** Shows the session-password prompt on the EDT and returns its result (or {@code null}). */
    private SessionPassword promptSessionPasswordResultOnEdt(String sessionName, String hostLabel,
                                                             String error, boolean allowRemember) {
        SessionPassword[] holder = new SessionPassword[1];
        runOnEdt(() -> holder[0] =
                prompts.promptSessionPassword(sessionName, hostLabel, error, allowRemember));
        return holder[0];
    }

    /** Shows a keyboard-interactive challenge on the EDT and returns its answers (or {@code null}). */
    private String[] promptChallengeOnEdt(String hostLabel, String instruction, String[] prompts_,
                                          boolean[] echo) {
        String[][] holder = new String[1][];
        runOnEdt(() -> holder[0] = prompts.promptChallenge(hostLabel, instruction, prompts_, echo));
        return holder[0];
    }

    /** Shows the key-passphrase prompt on the EDT and returns its result (or {@code null}). */
    private KeyPassphrase promptPassphraseOnEdt(String keyPath, String error, boolean allowRemember) {
        KeyPassphrase[] holder = new KeyPassphrase[1];
        runOnEdt(() -> holder[0] = prompts.promptKeyPassphrase(keyPath, error, allowRemember));
        return holder[0];
    }

    /** Runs {@code task} synchronously on the EDT (directly if already on it). */
    private void runOnEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (Exception e) {
                // Cancelled/interrupted: leave any holder untouched (treated as "no input").
                LOG.warn("EDT prompt was interrupted; treating as no input", e);
            }
        }
    }
}
