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
package com.katmoda.jterm.ui.component;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.io.File;
import java.nio.file.Path;

/**
 * A private-key-file picker: a path text field plus a "Browse…" button that opens a file chooser
 * rooted at {@code ~/.ssh}. Used by the SSH session dialog and each jump-host row to point at an
 * on-disk key to authenticate with; an empty path means "no explicit key" (agent/default keys
 * still apply).
 */
public final class KeyFileField {

    private final JPanel panel;
    private final JTextField path = new JTextField();

    public KeyFileField(String initial) {
        path.setText(initial != null ? initial : "");
        path.putClientProperty("JTextField.placeholderText", "(none)");

        JButton browse = new JButton("Browse…");
        browse.addActionListener(a -> chooseFile());

        this.panel = new JPanel(new BorderLayout(4, 0));
        panel.add(path, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
    }

    /** The Swing component to drop into a form row. */
    public JPanel component() {
        return panel;
    }

    /** Overrides the empty-field placeholder text (e.g. to show an inherited default). */
    public void setPlaceholder(String text) {
        path.putClientProperty("JTextField.placeholderText", text);
        path.repaint(); // so a live update (e.g. on folder change) redraws the placeholder
    }

    /** The entered key-file path, trimmed; empty for none. */
    public String path() {
        return path.getText().trim();
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select SSH Private Key");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File start = currentDirectory();
        if (start != null) {
            chooser.setCurrentDirectory(start);
        }
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            path.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** Opens at the field's current path's folder if set, else {@code ~/.ssh}, else home. */
    private File currentDirectory() {
        String current = path.getText().trim();
        if (!current.isEmpty()) {
            File parent = new File(current).getParentFile();
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }
        File ssh = Path.of(System.getProperty("user.home", "."), ".ssh").toFile();
        return ssh.isDirectory() ? ssh : null;
    }
}
