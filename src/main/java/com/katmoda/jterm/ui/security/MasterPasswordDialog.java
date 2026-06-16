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
package com.katmoda.jterm.ui.security;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.File;
import java.util.Arrays;

/**
 * Modal prompts for the vault master password: one to create it (with confirmation) and one
 * to enter it (with an optional error message after a failed attempt).
 */
public final class MasterPasswordDialog {

    private MasterPasswordDialog() {
    }

    /** Create a new master password; returns it, or {@code null} if cancelled. */
    public static char[] promptCreate(Component parent) {
        JPasswordField pw1 = new JPasswordField(20);
        JPasswordField pw2 = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Set a master password to protect saved SSH passwords."));
        form.add(new JLabel("New master password:"));
        form.add(pw1);
        form.add(new JLabel("Confirm:"));
        form.add(pw2);

        while (true) {
            int result = JOptionPane.showConfirmDialog(parent, form, "Create Master Password",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            char[] a = pw1.getPassword();
            char[] b = pw2.getPassword();
            if (a.length == 0) {
                JOptionPane.showMessageDialog(parent, "Master password cannot be empty.");
                continue;
            }
            if (!Arrays.equals(a, b)) {
                Arrays.fill(a, '\0');
                Arrays.fill(b, '\0');
                pw2.setText("");
                JOptionPane.showMessageDialog(parent, "Passwords do not match.");
                continue;
            }
            Arrays.fill(b, '\0');
            return a;
        }
    }

    /** Enter the existing master password; returns it, or {@code null} if cancelled. */
    public static char[] promptEnter(Component parent, String errorMessage) {
        JPasswordField pw = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        if (errorMessage != null) {
            JLabel error = new JLabel(errorMessage);
            error.putClientProperty("FlatLaf.styleClass", "h4");
            form.add(error);
        }
        form.add(new JLabel("Master password:"));
        form.add(pw);
        form.putClientProperty("initialFocus", pw);

        int result = JOptionPane.showConfirmDialog(parent, form, "Unlock Saved Passwords",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        return value.length == 0 ? null : value;
    }

    /** Outcome of a key-passphrase prompt: the entered passphrase and whether to remember it. */
    public record KeyPassphraseResult(char[] passphrase, boolean remember) {
    }

    /**
     * Connect-time prompt for the passphrase of an encrypted key file. The dialog names the key
     * unambiguously (file name + full path), shows {@code errorMessage} after a failed attempt,
     * and — when {@code allowRemember} is true — offers to save the passphrase. Returns
     * {@code null} if cancelled or left blank.
     */
    public static KeyPassphraseResult promptKeyPassphrase(Component parent, String keyPath,
            String errorMessage, boolean allowRemember) {
        JPasswordField pw = new JPasswordField(20);
        JCheckBox remember = new JCheckBox("Remember this passphrase");
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        if (errorMessage != null) {
            JLabel error = new JLabel(errorMessage);
            error.putClientProperty("FlatLaf.styleClass", "h4");
            form.add(error);
        }
        form.add(new JLabel("Enter the passphrase for SSH key \"" + new File(keyPath).getName() + "\":"));
        JLabel pathLabel = new JLabel(keyPath);
        pathLabel.setEnabled(false);
        form.add(pathLabel);
        form.add(pw);
        if (allowRemember) {
            form.add(remember);
        }
        form.putClientProperty("initialFocus", pw);

        int result = JOptionPane.showConfirmDialog(parent, form, "Key Passphrase",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        if (value.length == 0) {
            return null;
        }
        return new KeyPassphraseResult(value, allowRemember && remember.isSelected());
    }

    /** Connect-time prompt for a (non-saved) session password. */
    public static char[] promptSessionPassword(Component parent, String sessionName) {
        JPasswordField pw = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Password for " + sessionName + ":"));
        form.add(pw);
        int result = JOptionPane.showConfirmDialog(parent, form, "SSH Password",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        return value.length == 0 ? null : value;
    }
}
