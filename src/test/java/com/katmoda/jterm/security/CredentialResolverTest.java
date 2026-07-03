/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.security;

import com.katmoda.jterm.session.JumpHostConfig;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.ssh.SshConnect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless coverage for the password / jump-host / passphrase cascade. The vault is a real
 * {@link CredentialVault} over a temp file (via the package-private {@code Path} seam); the prompts
 * are plain fakes, so no Swing window is ever created.
 */
class CredentialResolverTest {

    @TempDir
    Path dir;

    private CredentialVault vault;

    /** Builds a resolver over a fresh, unlocked temp-file vault and the given prompts. */
    private CredentialResolver resolver(CredentialResolver.Prompts prompts) throws Exception {
        vault = new CredentialVault(dir.resolve("credentials.json"));
        vault.initialize("master".toCharArray());
        CredentialResolver.VaultAccess access = new CredentialResolver.VaultAccess() {
            @Override
            public boolean ensureUnlocked() {
                return true;
            }

            @Override
            public CredentialVault vault() {
                return vault;
            }
        };
        return new CredentialResolver(new SessionStore(), access, prompts);
    }

    /** A prompt fake that fails the test if any prompt is shown. */
    private static CredentialResolver.Prompts noPrompts() {
        return new CredentialResolver.Prompts() {
            @Override
            public char[] promptSessionPassword(String sessionName) {
                fail("promptSessionPassword should not have been called");
                return null;
            }

            @Override
            public CredentialResolver.KeyPassphrase promptKeyPassphrase(String keyPath, String error,
                    boolean allowRemember) {
                fail("promptKeyPassphrase should not have been called");
                return null;
            }
        };
    }

    /** A prompt fake whose session-password prompt returns {@code answer} (may be {@code null}). */
    private static CredentialResolver.Prompts sessionPassword(String answer) {
        return new CredentialResolver.Prompts() {
            @Override
            public char[] promptSessionPassword(String sessionName) {
                return answer == null ? null : answer.toCharArray();
            }

            @Override
            public CredentialResolver.KeyPassphrase promptKeyPassphrase(String keyPath, String error,
                    boolean allowRemember) {
                return fail("promptKeyPassphrase should not have been called");
            }
        };
    }

    private static SshSessionConfig session(boolean passwordAuth, boolean savePassword) {
        SshSessionConfig cfg = new SshSessionConfig();
        cfg.setName("test");
        cfg.setHost("host.example");
        cfg.setUser("me");
        cfg.setPasswordAuth(passwordAuth);
        cfg.setSavePassword(savePassword);
        return cfg;
    }

    @Test
    void sessionSavedPasswordWins() throws Exception {
        CredentialResolver r = resolver(noPrompts());
        SshSessionConfig cfg = session(true, true);
        // Both a session-level and a global default are stored; the session's own value must win.
        vault.setPassword(VaultKeys.sessionPassword(cfg.getId()), "session-pw".toCharArray());
        vault.setPassword(VaultKeys.GLOBAL_PASSWORD, "global-pw".toCharArray());

        assertEquals("session-pw", r.resolvePassword(cfg));
    }

    @Test
    void globalDefaultUsedWhenNoSessionPassword() throws Exception {
        CredentialResolver r = resolver(noPrompts());
        SshSessionConfig cfg = session(true, false);
        vault.setPassword(VaultKeys.GLOBAL_PASSWORD, "global-pw".toCharArray());

        assertEquals("global-pw", r.resolvePassword(cfg));
    }

    @Test
    void promptUsedWhenNothingStored() throws Exception {
        CredentialResolver r = resolver(sessionPassword("typed-pw"));
        SshSessionConfig cfg = session(true, false);

        assertEquals("typed-pw", r.resolvePassword(cfg));
    }

    @Test
    void cancelledPromptResolvesToNull() throws Exception {
        CredentialResolver r = resolver(sessionPassword(null));
        SshSessionConfig cfg = session(true, false);

        assertNull(r.resolvePassword(cfg));
    }

    @Test
    void passwordAuthOffResolvesToNullWithoutPrompting() throws Exception {
        CredentialResolver r = resolver(noPrompts());
        SshSessionConfig cfg = session(false, false);

        assertNull(r.resolvePassword(cfg));
    }

    @Test
    void jumpHostsSkipBlankAndResolvePerHopPasswords() throws Exception {
        CredentialResolver r = resolver(noPrompts());
        SshSessionConfig cfg = session(false, false);

        JumpHostConfig blank = new JumpHostConfig(); // no host → skipped defensively
        JumpHostConfig real = new JumpHostConfig();
        real.setHost("jump.example");
        real.setPort(2222);
        real.setUser("hopuser");
        real.setPasswordAuth(true);
        real.setSavePassword(true);
        cfg.setJumpHosts(List.of(blank, real));
        vault.setPassword(real.getId(), "hop-pw".toCharArray());

        List<SshConnect.HostHop> hops = r.resolveJumpHosts(cfg);
        assertEquals(1, hops.size());
        assertEquals("jump.example", hops.get(0).host());
        assertEquals("hop-pw", hops.get(0).password());
    }

    @Test
    void resolveSavedPassphraseNullWithoutKeyPath() throws Exception {
        CredentialResolver r = resolver(noPrompts());
        SshSessionConfig cfg = session(false, false);

        assertNull(r.resolveSavedPassphrase(cfg, null));
        assertNull(r.resolveSavedPassphrase(cfg, "  "));
    }
}
