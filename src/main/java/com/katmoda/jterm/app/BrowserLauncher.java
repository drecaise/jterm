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

import java.awt.Component;
import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a URL in the user's browser, falling back past {@link Desktop} where it can't work.
 *
 * <p>{@code Desktop.browse} is the normal path, but it is <em>unavailable inside the Flatpak
 * sandbox</em>: AWT's peer resolves the handler through gio, which sees only the runtime's
 * filesystem — no browser desktop file — so {@code isSupported(BROWSE)} returns false and the
 * Help → User Manual link silently did nothing. (Measured: {@code BROWSE=false}, and calling
 * {@code browse} throws {@code UnsupportedOperationException}.) The freedesktop runtime does
 * ship flatpak-xdg-utils' {@code xdg-open}, which hands the URI to the desktop portal, so that
 * is the first fallback; {@code flatpak-spawn --host xdg-open} covers a runtime without it and
 * is simply a missing command elsewhere. If everything fails the URL is shown in a dialog so it
 * can at least be copied.
 *
 * <p>Every attempt runs off the EDT: {@code browse} and the portal round trip can both block.
 */
public final class BrowserLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserLauncher.class);

    /** Generous cap on a launcher process; xdg-open returns as soon as the portal accepts. */
    private static final int LAUNCH_TIMEOUT_SECONDS = 20;

    private BrowserLauncher() {
    }

    /**
     * Opens {@code url}, reporting failure to the user rather than swallowing it.
     *
     * @param parent component whose window owns the failure dialog; may be {@code null}
     */
    public static void open(Component parent, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> {
            if (!tryOpen(url)) {
                SwingUtilities.invokeLater(() -> showFailure(parent, url));
            }
        }, "browser-launch");
        t.setDaemon(true);
        t.start();
    }

    /** Runs the fallback chain; {@code true} as soon as one handler takes the URL. */
    private static boolean tryOpen(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
        } catch (Exception ex) {
            LOG.debug("Desktop.browse failed for {}", url, ex);
        }
        return spawn(List.of("xdg-open", url))
                || spawn(List.of("flatpak-spawn", "--host", "xdg-open", url));
    }

    /** True when {@code command} started and exited cleanly (or is still running past the cap). */
    private static boolean spawn(List<String> command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // Still alive: it isn't going to report anything useful, and killing it could
                // take the browser with it. Treat a launcher that hasn't failed as a success.
                return true;
            }
            if (p.exitValue() == 0) {
                return true;
            }
            LOG.debug("{} exited with {}", command.get(0), p.exitValue());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            LOG.debug("Could not run {}", command.get(0), ex);
        }
        return false;
    }

    /** Last resort: show the URL in a selectable field so the user can copy it. */
    private static void showFailure(Component parent, String url) {
        LOG.warn("No handler could open {}", url);
        JTextField field = new JTextField(url);
        field.setEditable(false);
        field.setCaretPosition(0);
        JOptionPane.showMessageDialog(
                parent,
                new Object[] {"Could not open a browser. The address is:", field},
                "Open Link",
                JOptionPane.WARNING_MESSAGE);
    }
}
