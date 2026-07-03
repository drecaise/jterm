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

import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.local.LocalSession;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Recreates a {@link TerminalSession} of the same kind as the one a pane originally held, so a
 * stopped pane can be restarted in place. Local sessions are produced synchronously; SSH
 * sessions connect asynchronously and deliver the result later.
 *
 * <p>The factory must deliver the new session to {@code onReady} on the EDT (or not at all, if
 * creation fails — failures are reported by the factory itself, e.g. the SSH error dialog).</p>
 *
 * <p>This is also the single entry point for <em>initial</em> session creation: the {@link #local},
 * {@link #wsl} and {@link #ssh} static factories build a fresh session the very first time a cell is
 * filled and double as that cell's restart factory. Because local/WSL creation is synchronous, their
 * {@code create} runs {@code onReady} inline on the calling (EDT) thread, so callers that need the
 * session immediately can just call {@code create} and act in the callback.</p>
 */
@FunctionalInterface
public interface SessionFactory {

    /** Build a fresh session and hand it to {@code onReady} when ready. */
    void create(Consumer<TerminalSession> onReady);

    /**
     * Like {@link #create(Consumer)}, but also runs {@code onError} on the EDT if creation fails, so
     * a caller showing transient UI (e.g. the stopped screen's "Reconnecting…" status) can restore
     * it. The default ignores {@code onError}; factories whose creation can fail override this.
     */
    default void create(Consumer<TerminalSession> onReady, Runnable onError) {
        create(onReady);
    }

    /**
     * A factory for fresh local login shells. Creation is synchronous; on failure it reports the
     * error through {@code errorReporter} (a header plus the throwable — kept UI-agnostic so this
     * {@code terminal}-package type needs no {@code ui} dependency) and runs {@code onError}.
     */
    static SessionFactory local(BiConsumer<String, Throwable> errorReporter) {
        return new SessionFactory() {
            @Override
            public void create(Consumer<TerminalSession> onReady) {
                create(onReady, () -> { });
            }

            @Override
            public void create(Consumer<TerminalSession> onReady, Runnable onError) {
                TerminalSession session;
                try {
                    session = LocalSession.start(null);
                } catch (Exception e) {
                    errorReporter.accept("Failed to start local shell:", e);
                    onError.run();
                    return;
                }
                onReady.accept(session);
            }
        };
    }

    /** As {@link #local}, but for a shell inside the given WSL2 distribution. */
    static SessionFactory wsl(String distro, BiConsumer<String, Throwable> errorReporter) {
        return new SessionFactory() {
            @Override
            public void create(Consumer<TerminalSession> onReady) {
                create(onReady, () -> { });
            }

            @Override
            public void create(Consumer<TerminalSession> onReady, Runnable onError) {
                TerminalSession session;
                try {
                    session = LocalSession.startWsl(distro);
                } catch (Exception e) {
                    errorReporter.accept("Failed to start WSL distribution \"" + distro + "\":", e);
                    onError.run();
                    return;
                }
                onReady.accept(session);
            }
        };
    }

    /**
     * A factory that connects (and reconnects) an SSH session off the EDT via {@code connections}.
     * Connection failures are surfaced by {@link ConnectionService}'s own injected error reporter;
     * {@code onError} is forwarded so a caller can restore transient UI.
     */
    static SessionFactory ssh(SshSessionConfig cfg, ConnectionService connections) {
        return new SessionFactory() {
            @Override
            public void create(Consumer<TerminalSession> onReady) {
                create(onReady, () -> { });
            }

            @Override
            public void create(Consumer<TerminalSession> onReady, Runnable onError) {
                connections.connectAsync(cfg, onReady::accept, onError);
            }
        };
    }
}
