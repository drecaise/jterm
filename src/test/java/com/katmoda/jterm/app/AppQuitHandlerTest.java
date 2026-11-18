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
package com.katmoda.jterm.app;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.desktop.QuitResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response contract of the quit handler: exactly one of {@code performQuit}/{@code cancelQuit},
 * whatever happens. Driven on the real EDT with a scripted host; the dialog itself is not shown.
 */
class AppQuitHandlerTest {

    private static final QuitSummary REMOTE = new QuitSummary(1, 2, 0, 0, 0);

    /** Records every host call and every response, in order. */
    private static final class Script implements AppQuitHandler.Host, QuitResponse {
        final List<String> log = new ArrayList<>();
        Supplier<QuitSummary> open = () -> REMOTE;
        boolean answer;
        Runnable duringConfirm = () -> { };
        Runnable duringShutdown = () -> { };

        @Override
        public Component prepareForDialog() {
            log.add("prepare");
            return null;
        }

        @Override
        public QuitSummary openSessions() {
            log.add("count");
            return open.get();
        }

        @Override
        public boolean confirm(Component parent, QuitSummary summary) {
            log.add("confirm");
            assertTrue(SwingUtilities.isEventDispatchThread());
            duringConfirm.run();
            return answer;
        }

        @Override
        public void shutdown() {
            log.add("shutdown");
            duringShutdown.run();
        }

        @Override
        public void performQuit() {
            log.add("performQuit");
        }

        @Override
        public void cancelQuit() {
            log.add("cancelQuit");
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void quitsWithoutAskingWhenNothingRemoteIsOpen() throws Exception {
        Script s = new Script();
        s.open = () -> new QuitSummary(0, 0, 0, 0, 2);
        onEdt(() -> new AppQuitHandler(s).handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "shutdown", "performQuit"), s.log);
    }

    @Test
    void cancelLeavesEverythingRunning() throws Exception {
        Script s = new Script();
        onEdt(() -> new AppQuitHandler(s).handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "confirm", "cancelQuit"), s.log,
                "full screen ends before the question, and nothing is torn down");
    }

    @Test
    void quitRunsTheTeardownBeforeTheQuit() throws Exception {
        Script s = new Script();
        s.answer = true;
        onEdt(() -> new AppQuitHandler(s).handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "confirm", "shutdown", "performQuit"), s.log);
    }

    @Test
    void aFailedTeardownStillQuitsOnce() throws Exception {
        Script s = new Script();
        s.answer = true;
        s.duringShutdown = () -> {
            throw new IllegalStateException("boom");
        };
        onEdt(() -> new AppQuitHandler(s).handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "confirm", "shutdown", "performQuit"), s.log);
    }

    @Test
    void aFailureBeforeTheAnswerCancelsOnce() throws Exception {
        Script s = new Script();
        s.open = () -> {
            throw new IllegalStateException("boom");
        };
        onEdt(() -> new AppQuitHandler(s).handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "cancelQuit"), s.log,
                "never drop sessions the user wasn't asked about");
    }

    @Test
    void aSecondRequestWhileAskingIsAnsweredByTheFirst() throws Exception {
        Script s = new Script();
        s.answer = true;
        AppQuitHandler handler = new AppQuitHandler(s);
        // The JDK passes the same response again for a repeated Cmd+Q; the modal dialog keeps
        // dispatching events, so that call arrives re-entrantly while the first is still asking.
        s.duringConfirm = () -> handler.handleQuitRequestWith(null, s);
        onEdt(() -> handler.handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "confirm", "shutdown", "performQuit"), s.log);
    }

    @Test
    void aRequestOffTheEdtIsDecidedOnIt() throws Exception {
        Script s = new Script();
        new AppQuitHandler(s).handleQuitRequestWith(null, s); // the test thread, not the EDT
        onEdt(() -> { }); // drain the invokeLater
        assertEquals(List.of("prepare", "count", "confirm", "cancelQuit"), s.log);
    }

    @Test
    void canBeAskedAgainAfterACancel() throws Exception {
        Script s = new Script();
        AppQuitHandler handler = new AppQuitHandler(s);
        onEdt(() -> handler.handleQuitRequestWith(null, s));
        onEdt(() -> handler.handleQuitRequestWith(null, s));
        assertEquals(List.of("prepare", "count", "confirm", "cancelQuit",
                "prepare", "count", "confirm", "cancelQuit"), s.log);
    }
}
