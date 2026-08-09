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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            public CredentialResolver.SessionPassword promptSessionPassword(String sessionName,
                    String hostLabel, String error, boolean allowRemember) {
                fail("promptSessionPassword should not have been called");
                return null;
            }

            @Override
            public CredentialResolver.KeyPassphrase promptKeyPassphrase(String keyPath, String error,
                    boolean allowRemember) {
                fail("promptKeyPassphrase should not have been called");
                return null;
            }

            @Override
            public String[] promptChallenge(String hostLabel, String instruction, String[] prompts,
                    boolean[] echo) {
                fail("promptChallenge should not have been called");
                return null;
            }
        };
    }

    /** A prompt fake whose session-password prompt returns {@code answer} (may be {@code null}). */
    private static CredentialResolver.Prompts sessionPassword(String answer) {
        return new RecordingPrompts(answer, false);
    }

    /**
     * A prompt fake that answers the session-password prompt with {@code answer} (null =
     * cancelled), optionally ticking "remember", and records what it was shown so the interactive
     * fallback's error text / remember affordance can be asserted.
     */
    private static final class RecordingPrompts implements CredentialResolver.Prompts {
        private final String answer;
        private final boolean remember;
        String lastSessionName;
        String lastHostLabel;
        String lastError;
        boolean lastAllowRemember;
        String[] lastPrompts;
        boolean[] lastEcho;
        int passwordPrompts;

        RecordingPrompts(String answer, boolean remember) {
            this.answer = answer;
            this.remember = remember;
        }

        @Override
        public CredentialResolver.SessionPassword promptSessionPassword(String sessionName,
                String hostLabel, String error, boolean allowRemember) {
            passwordPrompts++;
            lastSessionName = sessionName;
            lastHostLabel = hostLabel;
            lastError = error;
            lastAllowRemember = allowRemember;
            return answer == null ? null
                    : new CredentialResolver.SessionPassword(answer.toCharArray(), remember);
        }

        @Override
        public CredentialResolver.KeyPassphrase promptKeyPassphrase(String keyPath, String error,
                boolean allowRemember) {
            return fail("promptKeyPassphrase should not have been called");
        }

        @Override
        public String[] promptChallenge(String hostLabel, String instruction, String[] prompts,
                boolean[] echo) {
            lastHostLabel = hostLabel;
            lastPrompts = prompts;
            lastEcho = echo;
            String[] answers = new String[prompts.length];
            Arrays.fill(answers, answer);
            return answers;
        }
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

    /** The hop the interactive fallback is asked about, standing in for {@code cfg}'s target. */
    private static SshConnect.HostHop targetHop(SshSessionConfig cfg) {
        return new SshConnect.HostHop(cfg.getHost(), 22, cfg.getUser(), null, null, cfg.getId());
    }

    @Test
    void interactiveFirstAttemptHasNoErrorAndOffersRemember() throws Exception {
        RecordingPrompts prompts = new RecordingPrompts("typed-pw", false);
        CredentialResolver r = resolver(prompts);
        SshSessionConfig cfg = session(false, false);

        assertEquals("typed-pw", r.interactiveAuth(cfg).passwordFor(targetHop(cfg), 0));
        assertNull(prompts.lastError);
        assertEquals("test", prompts.lastSessionName);
        assertEquals("me@host.example", prompts.lastHostLabel);
        assertTrue(prompts.lastAllowRemember);
    }

    @Test
    void interactiveRetryShowsAuthenticationError() throws Exception {
        RecordingPrompts prompts = new RecordingPrompts("typed-pw", false);
        CredentialResolver r = resolver(prompts);
        SshSessionConfig cfg = session(false, false);

        r.interactiveAuth(cfg).passwordFor(targetHop(cfg), 1);
        assertEquals("Authentication failed — try again.", prompts.lastError);
    }

    @Test
    void interactiveDoesNotOfferRememberForOtherHops() throws Exception {
        RecordingPrompts prompts = new RecordingPrompts("hop-pw", false);
        CredentialResolver r = resolver(prompts);
        SshSessionConfig cfg = session(false, false);
        // A jump host carries its own id, so remembering would key the wrong session.
        SshConnect.HostHop hop =
                new SshConnect.HostHop("jump.example", 22, "hopuser", null, null, "other-id");

        assertEquals("hop-pw", r.interactiveAuth(cfg).passwordFor(hop, 0));
        assertFalse(prompts.lastAllowRemember);
        assertEquals("hopuser@jump.example", prompts.lastSessionName);
    }

    @Test
    void interactiveCancelGivesUp() throws Exception {
        CredentialResolver r = resolver(new RecordingPrompts(null, false));
        SshSessionConfig cfg = session(false, false);

        assertNull(r.interactiveAuth(cfg).passwordFor(targetHop(cfg), 0));
    }

    @Test
    void rememberedPasswordIsSavedOnlyAfterAuthSucceeds() throws Exception {
        CredentialResolver r = resolver(new RecordingPrompts("typed-pw", true));
        SshSessionConfig cfg = session(false, false);
        SshConnect.InteractiveAuth auth = r.interactiveAuth(cfg);

        auth.passwordFor(targetHop(cfg), 0);
        // Nothing is written while the password is still unproven.
        assertFalse(vault.hasPassword(VaultKeys.sessionPassword(cfg.getId())));

        auth.onAuthSucceeded(targetHop(cfg));
        assertEquals("typed-pw", vault.getPassword(VaultKeys.sessionPassword(cfg.getId())));
        // Both flags are needed for resolvePassword to read the vault entry next time.
        assertTrue(cfg.isPasswordAuth());
        assertTrue(cfg.isSavePassword());
    }

    @Test
    void unrememberedPasswordIsNotSaved() throws Exception {
        CredentialResolver r = resolver(new RecordingPrompts("typed-pw", false));
        SshSessionConfig cfg = session(false, false);
        SshConnect.InteractiveAuth auth = r.interactiveAuth(cfg);

        auth.passwordFor(targetHop(cfg), 0);
        auth.onAuthSucceeded(targetHop(cfg));

        assertFalse(vault.hasPassword(VaultKeys.sessionPassword(cfg.getId())));
        assertFalse(cfg.isPasswordAuth());
    }

    @Test
    void challengePassesPromptsThrough() throws Exception {
        RecordingPrompts prompts = new RecordingPrompts("otp", false);
        CredentialResolver r = resolver(prompts);
        SshSessionConfig cfg = session(false, false);
        String[] questions = {"Password: ", "Verification code: "};
        boolean[] echo = {false, true};

        String[] answers = r.interactiveAuth(cfg)
                .challenge(targetHop(cfg), "Two-factor", questions, echo);

        assertArrayEquals(new String[]{"otp", "otp"}, answers);
        assertArrayEquals(questions, prompts.lastPrompts);
        assertArrayEquals(echo, prompts.lastEcho);
        assertEquals("me@host.example", prompts.lastHostLabel);
    }
}
