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
package com.katmoda.jterm.ui.preferences;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultKeys;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.component.HighlightListCombo;
import com.katmoda.jterm.ui.component.HighlightListsForm;
import com.katmoda.jterm.ui.component.KeyFileField;
import com.katmoda.jterm.ui.component.TabColorPicker;
import com.katmoda.jterm.ui.component.TerminalSettingsForm;
import com.katmoda.jterm.ui.component.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Application preferences, presented as tabs:
 * <ul>
 *   <li><b>General</b> — small terminal-behaviour toggles (read live, so they affect already-open
 *       terminals).</li>
 *   <li><b>Terminal Settings</b> — the default terminal type, font, font size and charset applied
 *       to the local terminal and to any saved session that leaves a field unset. These take effect
 *       for newly opened panes/tabs (running JediTerm widgets bake in their font at creation).</li>
 * </ul>
 * All choices are persisted in {@link AppSettings}.
 */
public final class PreferencesDialog {

    private PreferencesDialog() {
    }

    /** Shows the modal preferences dialog; applies and persists the choices on OK. */
    public static void show(Component parent) {
        AppSettings settings = AppSettings.get();

        ToggleSwitch copyOnSelect = new ToggleSwitch(settings.isCopyOnSelect());
        ToggleSwitch pasteOnRightClick = new ToggleSwitch(settings.isPasteOnRightClick());
        ToggleSwitch openTerminalOnStartup = new ToggleSwitch(settings.isOpenTerminalOnStartup());
        ToggleSwitch autoAcceptNewHostKeys = new ToggleSwitch(settings.isAutoAcceptNewHostKeys());
        JPanel general = new JPanel(new GridBagLayout());
        int row = 0;
        addToggleRow(general, row++, "Copy to clipboard on select:", copyOnSelect);
        addToggleRow(general, row++, "Paste on right click:", pasteOnRightClick);
        addHint(general, row++, "With this on, right-click pastes; use Ctrl+right-click for the menu.");
        addToggleRow(general, row++, "Open a terminal on startup:", openTerminalOnStartup);
        addHint(general, row++, "With this off, jterm starts with no open tabs.");
        addToggleRow(general, row++, "Auto-accept new host keys:", autoAcceptNewHostKeys);
        addHint(general, row++, "Trust first-seen SSH hosts without prompting. You're still warned"
                + " if a host's key changes.");

        TerminalSettingsForm terminalDefaults = new TerminalSettingsForm(false,
                settings.getDefaultTerminalType(), settings.getDefaultCharset(),
                settings.getDefaultFontFamily(), settings.getDefaultFontSize());
        JPanel terminal = new JPanel(new BorderLayout(0, 6));
        terminal.add(terminalDefaults.component(), BorderLayout.NORTH);
        JLabel defaultsHint = hint("Defaults for the local terminal and saved sessions that don't"
                + " override them. Applies to newly opened terminals.");
        terminal.add(defaultsHint, BorderLayout.SOUTH);

        // Highlighting: a global-default selector above the named-list editor.
        HighlightListsForm highlightForm = new HighlightListsForm();
        JComboBox<HighlightListCombo.Option> highlightDefault =
                HighlightListCombo.global(settings.getGlobalHighlightListId(), highlightForm.currentLists());
        // Keep the default selector's items in sync as lists are added/renamed/removed.
        highlightForm.setOnListsChanged(() -> HighlightListCombo.rebuildGlobal(highlightDefault,
                HighlightListCombo.selectedId(highlightDefault), highlightForm.currentLists()));
        JPanel highlightTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        highlightTop.add(new JLabel("Active list (global default):"));
        highlightTop.add(highlightDefault);
        JPanel highlighting = new JPanel(new BorderLayout(0, 8));
        highlighting.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        highlighting.add(highlightTop, BorderLayout.NORTH);
        highlighting.add(highlightForm.component(), BorderLayout.CENTER);
        highlighting.add(hint("Colors matching text in new output. Sessions can override this."
                + " Applies to newly opened terminals."), BorderLayout.SOUTH);

        // Session defaults: the username and tab color inherited by folders/sessions that leave
        // them unset. (Sessions and folders can still override these.)
        JTextField defaultUser = new JTextField(settings.getDefaultUsername(), 16);
        TabColorPicker defaultTabColor = new TabColorPicker(settings.getDefaultTabColorHex(), "Default");
        KeyFileField defaultKeyFile = new KeyFileField(settings.getDefaultKeyPath());
        defaultKeyFile.setPlaceholder("(none — use ~/.ssh identities)");
        var vault = VaultManager.get().vault();
        JPasswordField defaultKeyPassphrase = new JPasswordField(16);
        defaultKeyPassphrase.putClientProperty("JTextField.placeholderText",
                vault.hasPassword(VaultKeys.GLOBAL_KEY_PASSPHRASE)
                        ? "(leave blank to keep saved)" : "(none)");
        JPasswordField defaultPassword = new JPasswordField(16);
        defaultPassword.putClientProperty("JTextField.placeholderText",
                vault.hasPassword(VaultKeys.GLOBAL_PASSWORD)
                        ? "(leave blank to keep saved)" : "(none)");
        // Global keep-alive default (two-state — it's the root of the inheritance chain): an
        // on/off toggle plus an interval spinner enabled only when on. 0 = off.
        int defaultKeepAlive = settings.getDefaultKeepAliveSeconds();
        ToggleSwitch keepAlive = new ToggleSwitch(defaultKeepAlive > 0);
        JSpinner keepAliveInterval = new JSpinner(new SpinnerNumberModel(
                defaultKeepAlive > 0 ? defaultKeepAlive : 300, 30, 86400, 30));
        Runnable syncKeepAlive = () -> keepAliveInterval.setEnabled(keepAlive.isSelected());
        keepAlive.addActionListener(a -> syncKeepAlive.run());
        syncKeepAlive.run();

        JPanel sessionDefaults = new JPanel(new GridBagLayout());
        int sdRow = 0;
        addFieldRow(sessionDefaults, sdRow++, "Default username:", defaultUser);
        addFieldRow(sessionDefaults, sdRow++, "Default tab color:", defaultTabColor.component());
        addWideFieldRow(sessionDefaults, sdRow++, "Default key file:", defaultKeyFile.component());
        addFieldRow(sessionDefaults, sdRow++, "Default key passphrase:", defaultKeyPassphrase);
        addFieldRow(sessionDefaults, sdRow++, "Default password:", defaultPassword);
        addToggleRow(sessionDefaults, sdRow++, "Keep connection alive:", keepAlive);
        addFieldRow(sessionDefaults, sdRow++, "Keep-alive interval (s):", keepAliveInterval);
        addHint(sessionDefaults, sdRow++, "Used by folders and sessions that don't set their own."
                + " Passphrase and password are stored encrypted in the credential vault."
                + " Applies to newly opened sessions.");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", general);
        tabs.addTab("Session Defaults", sessionDefaults);
        tabs.addTab("Terminal Settings", terminal);
        tabs.addTab("Highlighting", highlighting);

        int result = JOptionPane.showConfirmDialog(parent, tabs, "Preferences",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setCopyOnSelect(copyOnSelect.isSelected());
        settings.setPasteOnRightClick(pasteOnRightClick.isSelected());
        settings.setOpenTerminalOnStartup(openTerminalOnStartup.isSelected());
        settings.setAutoAcceptNewHostKeys(autoAcceptNewHostKeys.isSelected());
        settings.setDefaultTerminalType(terminalDefaults.terminalType());
        settings.setDefaultCharset(terminalDefaults.charset());
        settings.setDefaultFontFamily(terminalDefaults.fontFamily());
        settings.setDefaultFontSize(terminalDefaults.fontSize());
        highlightForm.commit();
        settings.setGlobalHighlightListId(HighlightListCombo.selectedId(highlightDefault));
        settings.setDefaultUsername(defaultUser.getText());
        settings.setDefaultTabColorHex(defaultTabColor.hex());
        settings.setDefaultKeyPath(defaultKeyFile.path());
        settings.setDefaultKeepAliveSeconds(
                keepAlive.isSelected() ? (Integer) keepAliveInterval.getValue() : 0);
        applyVaultSecret(parent, VaultKeys.GLOBAL_KEY_PASSPHRASE, defaultKeyPassphrase.getPassword());
        applyVaultSecret(parent, VaultKeys.GLOBAL_PASSWORD, defaultPassword.getPassword());
        settings.save();
    }

    /**
     * Saves a typed secret to the vault under {@code vaultKey}; a blank field keeps any already-saved
     * value. Clears {@code entered} afterwards. Unlocks the vault on demand (only when something is
     * actually being saved).
     */
    private static void applyVaultSecret(Component parent, String vaultKey, char[] entered) {
        if (entered.length > 0 && VaultManager.get().ensureUnlocked(parent)) {
            try {
                VaultManager.get().vault().setPassword(vaultKey, entered);
            } catch (VaultException e) {
                JOptionPane.showMessageDialog(parent,
                        "Could not save the secret:\n" + e.getMessage(),
                        "jterm", JOptionPane.ERROR_MESSAGE);
            }
        }
        java.util.Arrays.fill(entered, '\0');
    }

    /** A "Label:   [toggle]" row: label on the left, toggle at its natural size on the right. */
    private static void addToggleRow(JPanel form, int row, String label, ToggleSwitch toggle) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 10);
        form.add(new JLabel(label), g);

        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(4, 0, 4, 4);
        form.add(toggle, g);
    }

    /** A "Label:   [component]" row: label on the left, the component at its natural size. */
    private static void addFieldRow(JPanel form, int row, String label, Component field) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 10);
        form.add(new JLabel(label), g);

        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(4, 0, 4, 4);
        form.add(field, g);
    }

    /** As {@link #addFieldRow}, but stretches {@code field} to fill the column (for wide inputs). */
    private static void addWideFieldRow(JPanel form, int row, String label, Component field) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 10);
        form.add(new JLabel(label), g);

        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(4, 0, 4, 4);
        form.add(field, g);
    }

    /** A full-width, de-emphasised explanatory line spanning both columns. */
    private static void addHint(JPanel form, int row, String text) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 4, 4, 4);
        form.add(hint(text), g);
    }

    /** A de-emphasised explanatory label. */
    private static JLabel hint(String text) {
        JLabel hint = new JLabel(text);
        hint.setEnabled(false);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        hint.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return hint;
    }
}
