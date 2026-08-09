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

import com.katmoda.jterm.ui.component.DialogFocus;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.File;
import java.util.Arrays;

/**
 * Modal prompts for the vault master password (create, with confirmation; enter, with an optional
 * error message after a failed attempt) plus the connect-time SSH credential prompts: session
 * password, key passphrase and keyboard-interactive challenges.
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
            int result = DialogFocus.showConfirm(parent, form, "Create Master Password", pw1);
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
                pw1.setText("");
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

        int result = DialogFocus.showConfirm(parent, form, "Unlock Saved Passwords", pw);
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

        int result = DialogFocus.showConfirm(parent, form, "Key Passphrase", pw);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        if (value.length == 0) {
            return null;
        }
        return new KeyPassphraseResult(value, allowRemember && remember.isSelected());
    }

    /**
     * Create a passphrase to encrypt a session export (with confirmation). This is independent of
     * the vault master password so the exported file stays portable. Returns the passphrase, or
     * {@code null} if cancelled.
     */
    public static char[] promptCreateExportPassphrase(Component parent) {
        JPasswordField pw1 = new JPasswordField(20);
        JPasswordField pw2 = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("This export includes saved passwords, so it will be encrypted."));
        form.add(new JLabel("Export passphrase:"));
        form.add(pw1);
        form.add(new JLabel("Confirm:"));
        form.add(pw2);

        while (true) {
            int result = DialogFocus.showConfirm(parent, form, "Encrypt Export", pw1);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            char[] a = pw1.getPassword();
            char[] b = pw2.getPassword();
            if (a.length == 0) {
                JOptionPane.showMessageDialog(parent, "Export passphrase cannot be empty.");
                continue;
            }
            if (!Arrays.equals(a, b)) {
                Arrays.fill(a, '\0');
                Arrays.fill(b, '\0');
                pw1.setText("");
                pw2.setText("");
                JOptionPane.showMessageDialog(parent, "Passphrases do not match.");
                continue;
            }
            Arrays.fill(b, '\0');
            return a;
        }
    }

    /**
     * Enter the passphrase for an encrypted session import; shows {@code errorMessage} after a
     * failed attempt. Returns the passphrase, or {@code null} if cancelled or left blank.
     */
    public static char[] promptExportPassphrase(Component parent, String errorMessage) {
        JPasswordField pw = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        if (errorMessage != null) {
            JLabel error = new JLabel(errorMessage);
            error.putClientProperty("FlatLaf.styleClass", "h4");
            form.add(error);
        }
        form.add(new JLabel("This export is encrypted. Enter its passphrase:"));
        form.add(pw);

        int result = DialogFocus.showConfirm(parent, form, "Decrypt Import", pw);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        return value.length == 0 ? null : value;
    }

    /** Outcome of a session-password prompt: the entered password and whether to remember it. */
    public record SessionPasswordResult(char[] password, boolean remember) {
    }

    /**
     * Connect-time prompt for a session password. Used both before a connect (when password auth
     * is configured but nothing is stored) and as the interactive fallback once agent/key auth has
     * failed — {@code errorMessage} carries "authentication failed, try again" in the latter case,
     * and {@code allowRemember} offers to save the password for the session. {@code hostLabel}
     * ({@code user@host}) disambiguates jump hosts from the target; it may be {@code null}.
     * Returns {@code null} if cancelled or left blank.
     */
    public static SessionPasswordResult promptSessionPassword(Component parent, String sessionName,
            String hostLabel, String errorMessage, boolean allowRemember) {
        JPasswordField pw = new JPasswordField(20);
        JCheckBox remember = new JCheckBox("Remember this password");
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        if (errorMessage != null) {
            JLabel error = new JLabel(errorMessage);
            error.putClientProperty("FlatLaf.styleClass", "h4");
            form.add(error);
        }
        form.add(new JLabel("Password for " + sessionName + ":"));
        if (hostLabel != null && !hostLabel.isBlank() && !hostLabel.equals(sessionName)) {
            JLabel hostLine = new JLabel(hostLabel);
            hostLine.setEnabled(false);
            form.add(hostLine);
        }
        form.add(pw);
        if (allowRemember) {
            form.add(remember);
        }

        int result = DialogFocus.showConfirm(parent, form, "SSH Password", pw);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        if (value.length == 0) {
            return null;
        }
        return new SessionPasswordResult(value, allowRemember && remember.isSelected());
    }

    /**
     * Answers a server-driven {@code keyboard-interactive} challenge (PAM, 2FA/OTP): one field per
     * prompt, masked unless the server asked for the reply to be echoed. Returns one answer per
     * prompt, or {@code null} if cancelled.
     */
    public static String[] promptChallenge(Component parent, String hostLabel, String instruction,
            String[] prompts, boolean[] echo) {
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        if (hostLabel != null && !hostLabel.isBlank()) {
            JLabel host = new JLabel(hostLabel);
            host.putClientProperty("FlatLaf.styleClass", "h4");
            form.add(host);
        }
        if (instruction != null && !instruction.isBlank()) {
            form.add(new JLabel(instruction));
        }
        JTextComponent[] fields = new JTextComponent[prompts.length];
        for (int i = 0; i < prompts.length; i++) {
            form.add(new JLabel(prompts[i]));
            fields[i] = echo[i] ? new JTextField(20) : new JPasswordField(20);
            form.add((Component) fields[i]);
        }

        // A challenge with no prompts (servers use one to push an informational banner) has no
        // field to focus, so it falls back to the plain pane.
        int result = fields.length > 0
                ? DialogFocus.showConfirm(parent, form, "SSH Authentication", fields[0])
                : JOptionPane.showConfirmDialog(parent, form, "SSH Authentication",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        String[] answers = new String[prompts.length];
        for (int i = 0; i < fields.length; i++) {
            answers[i] = fields[i] instanceof JPasswordField p
                    ? new String(p.getPassword()) : fields[i].getText();
        }
        return answers;
    }
}
