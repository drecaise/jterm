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
package com.katmoda.jterm.terminal;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroCrypto;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.macro.MacroRunner;
import com.katmoda.jterm.macro.MacroStep;
import com.katmoda.jterm.security.CredentialResolver;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.ssh.SshConnect;
import com.katmoda.jterm.terminal.ssh.SshSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Opens SSH sessions off the EDT: it resolves credentials up front (on the EDT, through the
 * injected {@link CredentialResolver}) so the background connect needs no UI, then hands the live
 * session back on the EDT. Connection failures are surfaced through the injected error reporter.
 */
public final class ConnectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionService.class);

    private final SessionStore sessionStore;
    private final CredentialResolver credentials;
    private final BiConsumer<String, Throwable> connectErrorReporter;

    public ConnectionService(SessionStore sessionStore, CredentialResolver credentials,
                             BiConsumer<String, Throwable> connectErrorReporter) {
        this.sessionStore = sessionStore;
        this.credentials = credentials;
        this.connectErrorReporter = connectErrorReporter;
    }

    /** Connect an SSH session off the EDT, then hand the live session to {@code onConnected} on the EDT. */
    public void connectAsync(SshSessionConfig cfg, Consumer<SshSession> onConnected, Runnable onError) {
        // Resolve any passwords on the EDT first — they may unlock the vault or prompt. The
        // target and every jump host are resolved up front so the background connect needs no UI.
        String password = credentials.resolvePassword(cfg);
        List<SshConnect.HostHop> jumpHosts = credentials.resolveJumpHosts(cfg);
        // Resolve the inherited username / tab color / key path (session → folder chain → global
        // default) on the EDT, since it walks the live session tree. The saved key passphrase (if
        // any) is read from the vault here too, so the background connect needs no UI on attempt 0.
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveTabColorHex = sessionStore.effectiveTabColorHex(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        int effectiveKeepAlive = sessionStore.effectiveKeepAliveSeconds(cfg);
        SshConnect.PassphraseProvider passphrases = credentials.keyPassphraseProvider(cfg, effectiveKeyPath);
        SshConnect.InteractiveAuth interactive = interactiveAuth(cfg);
        // Resolve the run-on-connect macro here too, for the same reason as the credentials above:
        // its steps may be encrypted at rest, and decrypting them can put a master-password prompt
        // on screen. Doing it before the connect starts means that prompt appears when the user
        // asked to connect, rather than surprising them once the session is already up.
        ConnectMacro connectMacro = resolveConnectMacro(cfg);
        new SwingWorker<SshSession, Void>() {
            @Override
            protected SshSession doInBackground() throws Exception {
                TerminalProfile profile = AppSettings.get().resolve(cfg.getTerminalType(),
                        cfg.getTerminalCharset(), cfg.getFontFamily(), cfg.getFontSize());
                return SshSession.connect(cfg.getHost(), cfg.getPort(), effectiveUser,
                        cfg.isAgentForwarding(), password, effectiveKeyPath, jumpHosts, passphrases,
                        interactive, cfg.getId(),
                        cfg.getName(), cfg.getIconId(), profile, cfg.getHighlightListId(),
                        effectiveTabColorHex, effectiveKeepAlive);
            }

            @Override
            protected void done() {
                try {
                    SshSession session = get();
                    onConnected.accept(session);
                    if (connectMacro != null) {
                        MacroRunner.run(connectMacro.name(), connectMacro.steps(), session.connector());
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    connectErrorReporter.accept("SSH connection failed:", cause);
                    onError.run();
                }
            }
        }.execute();
    }

    /**
     * The interactive-auth fallback to use for {@code cfg}, or
     * {@link SshConnect.InteractiveAuth#NONE} when the user has turned it off. The preference is
     * read here rather than inside {@link CredentialResolver} so the resolver stays free of
     * settings and remains headless-testable. Shared with the tunnel and SFTP connect paths.
     */
    public SshConnect.InteractiveAuth interactiveAuth(SshSessionConfig cfg) {
        return AppSettings.get().isPromptPasswordOnAuthFailure()
                ? credentials.interactiveAuth(cfg) : SshConnect.InteractiveAuth.NONE;
    }

    /** A run-on-connect macro already resolved to plaintext steps. */
    private record ConnectMacro(String name, List<MacroStep> steps) {
    }

    /**
     * The session's run-on-connect macro with its steps resolved (EDT), or {@code null} if it has
     * none, the id is stale, or the macro could not be decrypted.
     *
     * <p>A stale id has always been silently ignored, and a cancelled unlock is treated the same
     * way: the user declining to type their master password should not fail an SSH connection that
     * is otherwise fine. A decryption <em>failure</em> is different — that means the stored blob did
     * not authenticate — so it is logged.</p>
     */
    private ConnectMacro resolveConnectMacro(SshSessionConfig cfg) {
        Macro macro = MacroLibrary.get().byId(cfg.getMacroId());
        if (macro == null) {
            return null;
        }
        if (!macro.isSealed()) {
            return new ConnectMacro(macro.getName(), List.copyOf(macro.getSteps()));
        }
        if (!credentials.vaultAccess().ensureUnlocked()) {
            return null; // cancelled — connect anyway, just without the macro
        }
        try {
            Macro plain = MacroCrypto.resolved(macro, credentials.vaultAccess().vault());
            return new ConnectMacro(plain.getName(), List.copyOf(plain.getSteps()));
        } catch (VaultException e) {
            LOG.warn("could not decrypt the run-on-connect macro \"{}\"", macro.getName(), e);
            return null;
        }
    }
}
