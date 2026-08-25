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

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroCrypto;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultKeys;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.component.HighlightListCombo;
import com.katmoda.jterm.ui.component.HighlightListsForm;
import com.katmoda.jterm.ui.component.KeyFileField;
import com.katmoda.jterm.ui.component.TabColorPicker;
import com.katmoda.jterm.ui.component.TerminalSettingsForm;
import com.katmoda.jterm.ui.component.ToggleSwitch;
import com.katmoda.jterm.ui.theme.ThemeManager;

import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
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
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Application preferences, presented as tabs:
 * <ul>
 *   <li><b>General</b> — small terminal-behaviour toggles (read live, so they affect already-open
 *       terminals).</li>
 *   <li><b>Appearance</b> — the scale and font of the application chrome (sidebar, tabs, menus,
 *       dialogs), applied by {@code ThemeManager} at startup, so these take effect on the next
 *       launch and the dialog says so on OK.</li>
 *   <li><b>Terminal Settings</b> — the default terminal type, font, font size and charset applied
 *       to the local terminal and to any saved session that leaves a field unset. These take effect
 *       for newly opened panes/tabs (running JediTerm widgets bake in their font at creation). The
 *       caret-blink toggle sits here too, but is read live, so it does affect open terminals.</li>
 * </ul>
 * All choices are persisted in {@link AppSettings}.
 */
public final class PreferencesDialog {

    /** The UI scale percentages offered; the combo is not editable, so these are the only choices. */
    private static final String[] UI_SCALE_CHOICES =
            {"75%", "100%", "125%", "150%", "175%", "200%", "250%", "300%"};

    /** The "keep the look-and-feel's own font" sentinel leading the UI font chooser. */
    private static final String SYSTEM_FONT_LABEL = "(System default)";

    private PreferencesDialog() {
    }

    /** Shows the modal preferences dialog; applies and persists the choices on OK. */
    public static void show(Component parent) {
        AppSettings settings = AppSettings.get();

        ToggleSwitch copyOnSelect = new ToggleSwitch(settings.isCopyOnSelect());
        ToggleSwitch pasteOnRightClick = new ToggleSwitch(settings.isPasteOnRightClick());
        ToggleSwitch middleClickPaste = new ToggleSwitch(settings.isMiddleClickPaste());
        ToggleSwitch openTerminalOnStartup = new ToggleSwitch(settings.isOpenTerminalOnStartup());
        ToggleSwitch autoAcceptNewHostKeys = new ToggleSwitch(settings.isAutoAcceptNewHostKeys());
        ToggleSwitch promptPasswordOnAuthFailure =
                new ToggleSwitch(settings.isPromptPasswordOnAuthFailure());
        ToggleSwitch showWorkingDirectory = new ToggleSwitch(settings.isShowWorkingDirectory());
        ToggleSwitch checkForUpdates = new ToggleSwitch(settings.isUpdateCheckEnabled());
        ToggleSwitch encryptMacros = new ToggleSwitch(settings.isEncryptMacros());
        JPanel general = new JPanel(new GridBagLayout());
        int row = 0;
        addToggleRow(general, row++, "Copy to clipboard on select:", copyOnSelect);
        addToggleRow(general, row++, "Paste on right click:", pasteOnRightClick);
        addHint(general, row++, "With this on, right-click pastes; use Ctrl+right-click for the menu.");
        addToggleRow(general, row++, "Middle-click paste (primary selection):", middleClickPaste);
        addHint(general, row++, "Linux style: selecting text copies it to the primary selection;"
                + " middle-click pastes it.");
        addToggleRow(general, row++, "Open a terminal on startup:", openTerminalOnStartup);
        addHint(general, row++, "With this off, jterm starts with no open tabs.");
        addToggleRow(general, row++, "Show working directory:", showWorkingDirectory);
        addHint(general, row++, "Adds the directory to pane labels and its name to tab titles."
                + " Local shells always show theirs. For SSH and WSL this needs the shell to report"
                + " it (OSC 7, or the window title most distributions set by default); a shell that"
                + " does neither shows no directory.");
        addToggleRow(general, row++, "Auto-accept new host keys:", autoAcceptNewHostKeys);
        addHint(general, row++, "Trust first-seen SSH hosts without prompting. You're still warned"
                + " if a host's key changes.");
        addToggleRow(general, row++, "Ask for a password if key auth fails:",
                promptPasswordOnAuthFailure);
        addHint(general, row++, "When ssh-agent and key authentication are rejected, prompt for a"
                + " password instead of failing — if the server offers password auth.");
        addToggleRow(general, row++, "Check for updates:", checkForUpdates);
        addHint(general, row++, "Ask github.com about once a day whether a newer jterm release is"
                + " available, and offer a link to it. Nothing about you or your sessions is sent."
                + " Help → Check for Updates… always checks, even with this off.");
        addToggleRow(general, row++, "Encrypt macros on disk:", encryptMacros);
        addHint(general, row++, "Stores macro contents in macros.json encrypted with your master"
                + " password, so they are not readable from a backup or a synced folder. Macro names"
                + " and hotkeys stay readable. Anything running as you can still unlock the vault,"
                + " and a macro's text is still typed into the terminal in the clear.");
        addHint(general, row++, "If you lose the master password, encrypted macros cannot be"
                + " recovered — export them first if you want a copy you can still read.");

        // Appearance: the scale and font of the application chrome (sidebar, tabs, menus, dialogs).
        // All three are read by ThemeManager at startup only, hence the restart notice on OK.
        JComboBox<String> uiScale = new JComboBox<>(UI_SCALE_CHOICES);
        uiScale.setSelectedItem(settings.getUiScalePercent() + "%");
        // The family chooser needs no on/off toggle: its leading "(System default)" entry already
        // expresses "no override". The size spinner has no such empty state, so it keeps one.
        JComboBox<String> uiFontFamily = uiFontFamilyCombo(settings.getUiFontFamily());
        ToggleSwitch overrideUiFontSize = new ToggleSwitch(settings.getUiFontSize() > 0);
        JSpinner uiFontSize = new JSpinner(new SpinnerNumberModel(
                settings.getUiFontSize() > 0 ? settings.getUiFontSize() : 13,
                AppSettings.MIN_UI_FONT_SIZE, AppSettings.MAX_UI_FONT_SIZE, 1));
        Runnable syncUiFontSize = () -> uiFontSize.setEnabled(overrideUiFontSize.isSelected());
        overrideUiFontSize.addActionListener(a -> syncUiFontSize.run());
        syncUiFontSize.run();

        JPanel appearance = new JPanel(new GridBagLayout());
        int apRow = 0;
        addFieldRow(appearance, apRow++, "UI scale:", uiScale);
        addHint(appearance, apRow++, "Scales the sessions sidebar, tabs, menus and dialogs — text,"
                + " spacing and icons together. The terminal font is set separately under Terminal"
                + " Settings.");
        addFieldRow(appearance, apRow++, "UI font:", uiFontFamily);
        addToggleRow(appearance, apRow++, "Override UI font size:", overrideUiFontSize);
        addFieldRow(appearance, apRow++, "UI font size:", uiFontSize);
        addHint(appearance, apRow++, "The size is in points at 100% scale; the UI scale multiplies"
                + " it. Takes effect after restarting jterm.");

        TerminalSettingsForm terminalDefaults = new TerminalSettingsForm(false,
                settings.getDefaultTerminalType(), settings.getDefaultCharset(),
                settings.getDefaultFontFamily(), settings.getDefaultFontSize());
        JSpinner scrollback = new JSpinner(new SpinnerNumberModel(
                settings.getScrollbackLines(),
                AppSettings.MIN_SCROLLBACK_LINES, AppSettings.MAX_SCROLLBACK_LINES, 500));
        ToggleSwitch blinkCursor = new ToggleSwitch(settings.isBlinkCursor());
        // Same two-equal-column grid as TerminalSettingsForm so the label and field line up with
        // the Type/Font/Size/Charset rows above it.
        JPanel scrollbackRow = new JPanel(new GridLayout(0, 2, 6, 6));
        scrollbackRow.add(new JLabel("Scrollback lines:"));
        scrollbackRow.add(scrollback);
        JPanel blinkRow = new JPanel(new GridLayout(0, 2, 6, 6));
        blinkRow.add(new JLabel("Blink cursor:"));
        // The switch paints a fixed-size icon, so left-align it in its own panel rather than
        // letting the grid cell stretch its click target across half the tab.
        JPanel blinkSwitch = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        blinkSwitch.add(blinkCursor);
        blinkRow.add(blinkSwitch);
        // Unlike the defaults above it, this one is read live by JTermSettingsProvider on every
        // repaint tick, so it needs its own hint contradicting the "newly opened" one below.
        JLabel blinkHint = hint("Takes effect immediately, including in terminals that are"
                + " already open.");
        // Stack the form and the extra rows at the top without stretching them vertically.
        JPanel terminalTop = new JPanel();
        terminalTop.setLayout(new BoxLayout(terminalTop, BoxLayout.PAGE_AXIS));
        terminalDefaults.component().setAlignmentX(Component.LEFT_ALIGNMENT);
        scrollbackRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        blinkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        blinkHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        terminalTop.add(terminalDefaults.component());
        terminalTop.add(Box.createVerticalStrut(6));
        terminalTop.add(scrollbackRow);
        terminalTop.add(Box.createVerticalStrut(6));
        terminalTop.add(blinkRow);
        terminalTop.add(blinkHint);
        JPanel terminal = new JPanel(new BorderLayout(0, 6));
        terminal.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        terminal.add(terminalTop, BorderLayout.NORTH);
        // Scoped to the fields above the blink row on purpose — that one is read live, and its own
        // hint sits directly above this one.
        JLabel defaultsHint = hint("Type, font, size, charset and scrollback are defaults for the"
                + " local terminal and saved sessions that don't override them, and apply to newly"
                + " opened terminals.");
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

        // Colors: per-scheme terminal palette editor (foreground/background/selection + 16 ANSI).
        ColorSchemeForm colorForm = new ColorSchemeForm();
        JPanel colors = new JPanel(new BorderLayout(0, 6));
        colors.add(colorForm.component(), BorderLayout.NORTH);
        colors.add(hint("Customizes the terminal palette for each theme. Open terminals recolor"
                + " immediately; an already-ended \"session stopped\" overlay keeps its old colors"
                + " until a new pane opens."), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", general);
        tabs.addTab("Appearance", appearance);
        tabs.addTab("Session Defaults", sessionDefaults);
        tabs.addTab("Terminal Settings", terminal);
        tabs.addTab("Highlighting", highlighting);
        tabs.addTab("Colors", colors);

        int result = JOptionPane.showConfirmDialog(parent, tabs, "Preferences",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setCopyOnSelect(copyOnSelect.isSelected());
        settings.setPasteOnRightClick(pasteOnRightClick.isSelected());
        settings.setMiddleClickPaste(middleClickPaste.isSelected());
        settings.setOpenTerminalOnStartup(openTerminalOnStartup.isSelected());
        settings.setAutoAcceptNewHostKeys(autoAcceptNewHostKeys.isSelected());
        settings.setPromptPasswordOnAuthFailure(promptPasswordOnAuthFailure.isSelected());
        settings.setShowWorkingDirectory(showWorkingDirectory.isSelected());
        settings.setUpdateCheckEnabled(checkForUpdates.isSelected());
        // Applied before the settings are saved: re-writing macros.json in the other form can fail
        // or be cancelled, and the flag must not end up claiming a state the file is not in.
        applyMacroEncryption(parent, settings, encryptMacros.isSelected());
        // Remember the pre-edit UI scale/font so we only nag about restarting when one changed.
        String previousUiAppearance = uiAppearanceKey(settings);
        settings.setUiScalePercent(percentValue(uiScale.getSelectedItem()));
        settings.setUiFontFamily(uiFontFamilyValue(uiFontFamily));
        settings.setUiFontSize(overrideUiFontSize.isSelected() ? (Integer) uiFontSize.getValue() : 0);
        boolean uiAppearanceChanged = !previousUiAppearance.equals(uiAppearanceKey(settings));
        settings.setDefaultTerminalType(terminalDefaults.terminalType());
        settings.setDefaultCharset(terminalDefaults.charset());
        settings.setDefaultFontFamily(terminalDefaults.fontFamily());
        settings.setDefaultFontSize(terminalDefaults.fontSize());
        settings.setScrollbackLines((Integer) scrollback.getValue());
        settings.setBlinkCursor(blinkCursor.isSelected());
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
        // Persist palette edits and recolor running terminals via the active theme.
        colorForm.commit();
        ThemeManager.get().reapplyColors();
        if (uiAppearanceChanged) {
            JOptionPane.showMessageDialog(parent,
                    "The UI scale and font are applied when jterm starts.\n"
                            + "Restart jterm to see the change.",
                    "jterm", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * A combo of the installed font families for the UI font override, seeded with {@code selected}.
     * {@link #SYSTEM_FONT_LABEL} leads the list and is selected when there is no override, so the
     * chooser never implies the first family alphabetically is the font in use. An unavailable saved
     * family is added so it stays selectable (as {@code TerminalSettingsForm} does), rather than
     * silently switching to another font.
     */
    private static JComboBox<String> uiFontFamilyCombo(String selected) {
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem(SYSTEM_FONT_LABEL);
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            combo.addItem(family);
        }
        if (selected.isBlank()) {
            combo.setSelectedItem(SYSTEM_FONT_LABEL);
        } else {
            if (((DefaultComboBoxModel<String>) combo.getModel()).getIndexOf(selected) < 0) {
                combo.addItem(selected);
            }
            combo.setSelectedItem(selected);
        }
        return combo;
    }

    /** The chosen UI font family, mapping {@link #SYSTEM_FONT_LABEL} back to "no override". */
    private static String uiFontFamilyValue(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return (value == null || SYSTEM_FONT_LABEL.equals(value)) ? "" : value.toString();
    }

    /** Identity of the startup-only appearance settings, used to detect an edit needing a restart. */
    private static String uiAppearanceKey(AppSettings settings) {
        return settings.getUiScalePercent() + "|" + settings.getUiFontFamily()
                + "|" + settings.getUiFontSize();
    }

    /** Parses a {@code "150%"} choice back to {@code 150}. */
    private static int percentValue(Object choice) {
        String text = (choice == null) ? "" : choice.toString().replace("%", "").trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 100;
        }
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
    /**
     * Turns macro encryption on or off, rewriting {@code macros.json} in the new form.
     *
     * <p>Both directions need the vault: sealing needs the key, and unsealing needs to read what is
     * already sealed. If the user cancels the unlock or the rewrite fails, the setting is left where
     * it was rather than pointing at a file in the other form — a flag that disagrees with the file
     * would either expose macros the user asked to protect or make protected ones unreadable.</p>
     */
    private static void applyMacroEncryption(Component parent, AppSettings settings, boolean enable) {
        if (enable == settings.isEncryptMacros()) {
            return;
        }
        List<Macro> macros = MacroLibrary.get().macros();
        if (MacroLibrary.get().isLoadFailed()) {
            JOptionPane.showMessageDialog(parent,
                    "macros.json could not be read, so this setting cannot be changed yet.",
                    "Encrypt Macros", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!macros.isEmpty() && !VaultManager.get().ensureUnlocked(parent)) {
            return; // cancelled — leave the setting as it was
        }

        settings.setEncryptMacros(enable);
        try {
            if (!enable) {
                // Decrypt first: with the flag already off, save() writes whatever form it finds.
                MacroCrypto.unsealAll(macros, VaultManager.get().vault());
            }
        } catch (VaultException e) {
            settings.setEncryptMacros(!enable);
            ErrorDialog.show(parent, "Encrypt Macros", "Could not decrypt your macros:", e);
            return;
        }
        if (!MacroLibrary.get().save()) {
            settings.setEncryptMacros(!enable);
            JOptionPane.showMessageDialog(parent,
                    "Your macros could not be rewritten, so this setting was left unchanged.",
                    "Encrypt Macros", JOptionPane.ERROR_MESSAGE);
        }
    }

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
    /**
     * Preferred width reported by a long hint, in unscaled px.
     *
     * <p>Not the wrap point. A plain {@link JLabel} never wraps, so a paragraph-length hint
     * reported its full single-line width as <em>preferred</em> — which set the width of the
     * whole {@code JTabbedPane}, and so of the dialog on <b>every</b> tab, not just its own. Two
     * such hints had stretched it about 40% past what the controls need.</p>
     *
     * <p>Re-rendering the hint as HTML makes it wrappable; the fixed-width div then caps what it
     * asks for, so hints stop driving the dialog's width and the wider tabs (the combo-box rows
     * on Terminal Settings and Session Defaults) decide it instead. At paint time each hint wraps
     * to whatever the layout actually allocates, which is why a hint can still render wider than
     * this value. Keep it below the width those tabs need, or it starts driving the size again.
     * </p>
     */
    private static final int HINT_PREFERRED_WIDTH = 820;

    private static JLabel hint(String text) {
        JLabel hint = new JLabel(text);
        hint.setEnabled(false);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        // Measure with the final font, and convert only when it is actually too wide — a
        // fixed-width div would otherwise make short one-line hints ask for the full width too.
        int max = UIScale.scale(HINT_PREFERRED_WIDTH);
        if (hint.getPreferredSize().width > max) {
            hint.setText("<html><div style='width:" + max + "px'>" + escapeHtml(text) + "</div></html>");
        }
        hint.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return hint;
    }

    /** Hint text is plain prose, but it becomes HTML above, so markup characters must not leak. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
