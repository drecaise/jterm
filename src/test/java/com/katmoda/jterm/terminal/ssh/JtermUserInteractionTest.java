/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.ssh;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt budgeting for the interactive auth fallback. The {@link org.apache.sshd.client.session.ClientSession}
 * argument is unused by the implementation, so these run headless with {@code null}.
 */
class JtermUserInteractionTest {

    private static final SshConnect.HostHop HOP =
            new SshConnect.HostHop("host.example", 22, "me", null, null, "cfg-id");

    /** Records every call and answers with a fixed script ({@code null} = the user cancelled). */
    private static final class Delegate implements SshConnect.InteractiveAuth {
        final List<String> calls = new ArrayList<>();
        private final String answer;

        Delegate(String answer) {
            this.answer = answer;
        }

        @Override
        public String passwordFor(SshConnect.HostHop hop, int attempt) {
            calls.add("password:" + attempt);
            return answer;
        }

        @Override
        public String[] challenge(SshConnect.HostHop hop, String instruction, String[] prompts,
                                  boolean[] echo) {
            calls.add("challenge");
            return answer == null ? null : new String[]{answer};
        }
    }

    private static JtermUserInteraction onHop(Delegate delegate) {
        JtermUserInteraction interaction = new JtermUserInteraction(delegate);
        interaction.setCurrentHop(HOP);
        return interaction;
    }

    private static String[] oneChallenge(JtermUserInteraction interaction) {
        return interaction.interactive(null, "", "Two-factor", "en",
                new String[]{"Password: "}, new boolean[]{false});
    }

    @Test
    void cancellingAChallengeStopsAllFurtherPrompts() {
        // MINA's keyboard-interactive method re-challenges on a null response until its own
        // budget runs out; cancel has to latch or the dialog reappears (once per remaining try).
        Delegate delegate = new Delegate(null);
        JtermUserInteraction interaction = onHop(delegate);

        assertNull(oneChallenge(interaction));
        assertNull(oneChallenge(interaction));
        assertNull(interaction.resolveAuthPasswordAttempt(null));

        assertEquals(List.of("challenge"), delegate.calls);
        assertFalse(interaction.isInteractionAllowed(null));
    }

    @Test
    void cancellingAPasswordPromptStopsTheChallengePathToo() {
        Delegate delegate = new Delegate(null);
        JtermUserInteraction interaction = onHop(delegate);

        assertNull(interaction.resolveAuthPasswordAttempt(null));
        assertNull(oneChallenge(interaction));

        assertEquals(List.of("password:0"), delegate.calls);
    }

    @Test
    void answeredPromptsAreCappedPerHop() {
        // MINA allows 3 attempts per auth method, so a server offering both would ask six times.
        Delegate delegate = new Delegate("pw");
        JtermUserInteraction interaction = onHop(delegate);

        for (int i = 0; i < 5; i++) {
            interaction.resolveAuthPasswordAttempt(null);
            oneChallenge(interaction);
        }

        // The budget is hop-wide, so the attempt index counts every prompt on the hop rather than
        // restarting per method — the user sees "try again" on their second try either way.
        assertEquals(3, delegate.calls.size());
        assertEquals(List.of("password:0", "challenge", "password:2"), delegate.calls);
    }

    @Test
    void attemptIndexIncrementsSoTheRetryCanShowAnError() {
        Delegate delegate = new Delegate("pw");
        JtermUserInteraction interaction = onHop(delegate);

        interaction.resolveAuthPasswordAttempt(null);
        interaction.resolveAuthPasswordAttempt(null);

        assertEquals(List.of("password:0", "password:1"), delegate.calls);
    }

    @Test
    void movingToTheNextHopClearsTheCancelAndTheBudget() {
        Delegate delegate = new Delegate(null);
        JtermUserInteraction interaction = onHop(delegate);
        interaction.resolveAuthPasswordAttempt(null);
        assertFalse(interaction.isInteractionAllowed(null));

        // A jump-host chain authenticates hops in turn; cancelling one must not mute the next.
        interaction.setCurrentHop(new SshConnect.HostHop("next.example", 22, "me", null, null, null));

        assertTrue(interaction.isInteractionAllowed(null));
    }

    @Test
    void noPromptsBeforeAHopIsSet() {
        Delegate delegate = new Delegate("pw");
        JtermUserInteraction interaction = new JtermUserInteraction(delegate);

        assertNull(interaction.resolveAuthPasswordAttempt(null));
        assertFalse(interaction.isInteractionAllowed(null));
        assertTrue(delegate.calls.isEmpty());
    }
}
