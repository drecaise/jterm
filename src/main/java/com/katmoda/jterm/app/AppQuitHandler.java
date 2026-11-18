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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.desktop.QuitEvent;
import java.awt.desktop.QuitHandler;
import java.awt.desktop.QuitResponse;

/**
 * The application quit handler: Cmd+Q, the Dock's Quit and logging out on macOS (design:
 * {@code docs/superpowers/specs/2026-11-10-rdp-full-screen-design.md}, open item "Cmd+Q on macOS").
 *
 * <p>Without one, the JDK's default quit strategy is {@code QuitStrategy.NORMAL_EXIT}, documented as
 * {@code System.exit(0)}: every session dropped without a word, and {@code MainWindow}'s
 * {@code windowClosing} teardown (window state, sessions, tunnels) skipped. With it, a quit asks
 * first when something remote is open ({@link QuitSummary}), leaves RDP full screen before asking
 * (jterm never shows a dialog while full screen), and on "Quit" runs the same teardown as closing
 * the main window before letting the quit go ahead. The strategy itself is left at
 * {@code NORMAL_EXIT}: by the time {@link QuitResponse#performQuit()} runs, the teardown is done, and
 * {@code CLOSE_ALL_WINDOWS} would instead depend on undocumented details (which window events it
 * sends, in what order) that can't be measured without a Mac.</p>
 *
 * <p>Installed only where the platform supports it ({@link #install}); in practice macOS. Windows
 * and Linux have no application-level quit request, so nothing changes there.</p>
 */
final class AppQuitHandler implements QuitHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AppQuitHandler.class);

    /** The window side of a quit, implemented by {@code MainWindow}. All calls are on the EDT. */
    interface Host {
        /**
         * Brings every full-screen RDP desktop back into its pane and returns the component the
         * confirmation should be centred on (the window that showed the desktop, else the focused
         * jterm window).
         */
        Component prepareForDialog();

        /** What is open right now, across every window. */
        QuitSummary openSessions();

        /** Asks the user; {@code true} to quit. */
        boolean confirm(Component parent, QuitSummary summary);

        /** The teardown shared with closing the main window. */
        void shutdown();
    }

    private final Host host;
    /**
     * A quit is being decided. The JDK may call the handler again with the same response while the
     * confirmation is open (a second Cmd+Q; the modal dialog keeps dispatching events, so that call
     * lands here re-entrantly on the EDT); the first call answers it, so the later ones only return.
     */
    private boolean deciding;

    AppQuitHandler(Host host) {
        this.host = host;
    }

    /**
     * Installs the handler when the platform supports one and returns it, else {@code null}.
     * {@code Desktop.isDesktopSupported()} comes first because {@code getDesktop()} throws without
     * it (headless, or no desktop on some Linux setups).
     */
    static AppQuitHandler install(Host host) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return null;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                return null;
            }
            AppQuitHandler handler = new AppQuitHandler(host);
            desktop.setQuitHandler(handler);
            return handler;
        } catch (UnsupportedOperationException | SecurityException e) {
            LOG.debug("no application quit handler on this platform", e);
            return null;
        }
    }

    /**
     * File → Quit where this handler is installed (macOS): the same question and the same teardown
     * as Cmd+Q, so the menu item is not a way to drop every session unasked while its own shortcut
     * asks. {@code exit} runs after the teardown when the user goes ahead; nothing runs on Cancel.
     * EDT.
     */
    void quitFromMenu(Runnable exit) {
        decide(new QuitResponse() {
            @Override
            public void performQuit() {
                exit.run();
            }

            @Override
            public void cancelQuit() {
                // stays open
            }
        });
    }

    /**
     * The JDK's docs don't say which thread this runs on (macOS dispatches it on the EDT, as far as
     * is known, but that is an implementation detail), and allow the response to come from any
     * thread. So the work always moves to the EDT, and the response is given exactly once there.
     */
    @Override
    public void handleQuitRequestWith(QuitEvent e, QuitResponse response) {
        if (SwingUtilities.isEventDispatchThread()) {
            decide(response);
        } else {
            SwingUtilities.invokeLater(() -> decide(response));
        }
    }

    private void decide(QuitResponse response) {
        if (deciding) {
            return; // the same response, already being decided by the call further up this stack
        }
        deciding = true;
        boolean quit = false;
        try {
            Component parent = host.prepareForDialog();
            QuitSummary open = host.openSessions();
            quit = !open.shouldConfirm() || host.confirm(parent, open);
            if (quit) {
                // Inside the try only so a failure is logged; once the user has said Quit, a failed
                // teardown still quits (below). Before that point a failure cancels instead, so a bug
                // here can never drop open sessions the user wasn't asked about. File > Quit and
                // closing the window still work then.
                host.shutdown();
            }
        } catch (RuntimeException ex) {
            LOG.warn("quit request failed ({})", quit ? "quitting anyway" : "cancelled", ex);
        } finally {
            deciding = false;
            if (quit) {
                response.performQuit();
            } else {
                response.cancelQuit();
            }
        }
    }

    /**
     * The confirmation. A plain question with no field, so {@code JOptionPane} (not {@code
     * DialogFocus}); the default button is Cancel, so a stray Return after Cmd+Q doesn't quit.
     */
    static boolean ask(Component parent, QuitSummary summary) {
        Object[] options = {"Quit", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, summary.question(), "Quit jterm",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0;
    }
}
