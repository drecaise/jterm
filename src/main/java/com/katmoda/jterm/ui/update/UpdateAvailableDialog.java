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
package com.katmoda.jterm.ui.update;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.app.BrowserLauncher;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.update.ReleaseInfo;
import com.katmoda.jterm.update.UpdateChecker;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * "A new version is available" dialog: what is available, the release notes, and a button that
 * opens the release page in the system browser.
 *
 * <p>The release notes are shown in a read-only {@link JTextArea}, <b>not</b> in the HTML
 * {@code JEditorPane} the About dialog uses. The notes are Markdown written by whoever published
 * the release, and Swing's HTML renderer will happily fetch a remote {@code <img src>} — which
 * would leak the user's IP to a third party the moment the dialog appeared, and render arbitrary
 * markup besides. Plain text sidesteps both.</p>
 *
 * <p>The opt-out checkbox is applied on <em>every</em> exit path, including the window close
 * button, so a user who ticks it and then dismisses the dialog is not asked again.</p>
 */
public final class UpdateAvailableDialog {

    private static final String VIEW = "View Release";
    private static final String SKIP = "Skip This Version";
    private static final String LATER = "Later";

    private UpdateAvailableDialog() {
    }

    /**
     * Shows the dialog and applies whatever the user chose (skip / opt out) to
     * {@link AppSettings}, saving once at the end. Must be called on the EDT.
     *
     * @param currentVersion the running build's version, shown for context
     * @param release        the newer release, as returned by the update check
     */
    public static void show(Component parent, String currentVersion, ReleaseInfo release) {
        AppSettings settings = AppSettings.get();

        JLabel headline = new JLabel("<html><b>jterm " + escape(release.tagName())
                + "</b> is available. You are running " + escape(currentVersion) + ".</html>");
        headline.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(8), 0));

        JTextArea notes = new JTextArea(notesText(release));
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIScale.scale(12)));
        notes.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(notes);
        scroll.setPreferredSize(UIScale.scale(new Dimension(520, 260)));

        JCheckBox optOut = new JCheckBox("Don't check for updates automatically",
                !settings.isUpdateCheckEnabled());
        optOut.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(headline, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(optOut, BorderLayout.SOUTH);

        int choice = JOptionPane.showOptionDialog(parent, panel, "Update Available",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                new Object[]{VIEW, SKIP, LATER}, VIEW);

        // Applied regardless of which button was pressed, and regardless of the dialog being
        // closed outright — the checkbox is a statement of intent, not a button modifier.
        settings.setUpdateCheckEnabled(!optOut.isSelected());
        if (choice == 1) {
            settings.setSkippedUpdateVersion(release.tagName());
        }
        settings.save();

        if (choice == 0) {
            // Never the raw html_url: safeReleaseUrl re-checks that it points at this project
            // before it can reach Desktop.browse / xdg-open.
            BrowserLauncher.open(parent, UpdateChecker.safeReleaseUrl(release.htmlUrl()));
        }
    }

    private static String notesText(ReleaseInfo release) {
        String notes = UpdateChecker.trimNotes(release.body());
        if (notes.isEmpty()) {
            return "This release has no notes. See the release page for details.";
        }
        String title = release.displayName();
        return title.equals(release.tagName()) ? notes : title + "\n\n" + notes;
    }

    /** The headline is HTML (for the bold run), so version text must not be able to inject tags. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
