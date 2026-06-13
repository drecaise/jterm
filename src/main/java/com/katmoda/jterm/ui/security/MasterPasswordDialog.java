package com.katmoda.jterm.ui.security;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.Component;
import java.awt.GridLayout;
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

    /** Connect-time prompt for the passphrase of an encrypted key file; null if cancelled/blank. */
    public static char[] promptKeyPassphrase(Component parent, String keyFileName) {
        JPasswordField pw = new JPasswordField(20);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Passphrase for key \"" + keyFileName + "\":"));
        form.add(pw);
        int result = JOptionPane.showConfirmDialog(parent, form, "Key Passphrase",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        char[] value = pw.getPassword();
        return value.length == 0 ? null : value;
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
