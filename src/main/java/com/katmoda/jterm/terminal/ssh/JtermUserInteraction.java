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
package com.katmoda.jterm.terminal.ssh;

import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.session.ClientSession;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts jterm's {@link SshConnect.InteractiveAuth} to MINA's {@link UserInteraction} — the hook
 * that gives an SSH connect its interactive password fallback.
 *
 * <p>MINA's default client method order is publickey → keyboard-interactive → password, and it
 * only runs a method the server actually offers. So everything here fires <em>after</em> ssh-agent
 * and on-disk keys have been exhausted, and never on a key-only server:</p>
 * <ul>
 *   <li>{@link #resolveAuthPasswordAttempt} is called by {@code UserAuthPassword} once the
 *       statically registered password identities (a saved/prompted-up-front password) are used
 *       up — i.e. exactly the "agent failed, ask the user" case.</li>
 *   <li>{@link #interactive} answers {@code keyboard-interactive} challenges (PAM, 2FA/OTP). Note
 *       MINA first tries to auto-answer a single no-echo "…password…" prompt with the current
 *       password candidate, so a saved password is still tried before the user is bothered.</li>
 * </ul>
 *
 * <p>Both callbacks run on a MINA NIO worker thread and block it while the (modal, EDT) prompt is
 * open. {@link #isPrompting()} / {@link #promptActivity()} let {@link SshConnect} pause its auth
 * timeout for that time instead of killing the connect out from under the dialog.</p>
 *
 * <p>Not thread-safe across concurrent hops by design: {@link SshConnect} connects hops
 * sequentially and sets the current hop around each one, mirroring
 * {@link JtermKnownHostsVerifier#setIntendedHost}.</p>
 */
final class JtermUserInteraction implements UserInteraction {

    private static final Logger LOG = LoggerFactory.getLogger(JtermUserInteraction.class);

    /**
     * Total interactive prompts allowed per hop. MINA enforces {@code PASSWORD_PROMPTS} (3)
     * <em>per auth method</em>, so a server offering both keyboard-interactive and password would
     * otherwise ask six times; this cap keeps it to the OpenSSH-like three for the whole hop.
     */
    private static final int MAX_PROMPTS_PER_HOP = 3;

    private final SshConnect.InteractiveAuth delegate;

    private volatile SshConnect.HostHop currentHop;
    private volatile int prompts;
    private final AtomicBoolean prompting = new AtomicBoolean();
    private final AtomicLong activity = new AtomicLong();

    JtermUserInteraction(SshConnect.InteractiveAuth delegate) {
        this.delegate = delegate;
    }

    /** Point subsequent prompts at {@code hop} and reset its prompt budget. */
    void setCurrentHop(SshConnect.HostHop hop) {
        this.currentHop = hop;
        this.prompts = 0;
    }

    /** Tell the delegate this hop authenticated, so it can persist a remembered password. */
    void authSucceeded() {
        SshConnect.HostHop hop = currentHop;
        if (hop != null) {
            delegate.onAuthSucceeded(hop);
        }
    }

    /** True while a prompt is on screen (the auth timeout must not run during that). */
    boolean isPrompting() {
        return prompting.get();
    }

    /** Monotonic count of prompts shown; a change means the user was busy, not the server. */
    long promptActivity() {
        return activity.get();
    }

    @Override
    public boolean isInteractionAllowed(ClientSession session) {
        return currentHop != null && prompts < MAX_PROMPTS_PER_HOP;
    }

    @Override
    public String resolveAuthPasswordAttempt(ClientSession session) {
        SshConnect.HostHop hop = currentHop;
        if (hop == null || prompts >= MAX_PROMPTS_PER_HOP) {
            return null;
        }
        int attempt = prompts++;
        return prompt(() -> delegate.passwordFor(hop, attempt));
    }

    @Override
    public String[] interactive(ClientSession session, String name, String instruction,
                                String lang, String[] prompt, boolean[] echo) {
        SshConnect.HostHop hop = currentHop;
        if (hop == null || prompts >= MAX_PROMPTS_PER_HOP) {
            return null;
        }
        prompts++;
        // The server's own instruction is the useful text; its "name" is usually blank, so fall
        // back to it only when there is nothing else to show above the fields.
        String text = instruction != null && !instruction.isBlank() ? instruction : name;
        return prompt(() -> delegate.challenge(hop, text, prompt, echo));
    }

    /**
     * Password-change requests ({@code SSH_MSG_USERAUTH_PASSWD_CHANGEREQ}) are not supported;
     * returning {@code null} makes MINA fail the method cleanly rather than hang.
     */
    @Override
    public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
        LOG.info("server requested a password change ({}); not supported — failing password auth",
                prompt);
        return null;
    }

    @Override
    public void welcome(ClientSession session, String banner, String lang) {
        LOG.debug("ssh banner: {}", banner);
    }

    @Override
    public void serverVersionInfo(ClientSession session, List<String> lines) {
        LOG.debug("ssh server version info: {}", lines);
    }

    /** Runs a prompting call with the "a dialog is open" flag held, so the auth clock pauses. */
    private <T> T prompt(java.util.function.Supplier<T> action) {
        prompting.set(true);
        try {
            return action.get();
        } finally {
            activity.incrementAndGet();
            prompting.set(false);
        }
    }
}
