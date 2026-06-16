package com.katmoda.jterm.ui.preferences;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.ui.component.HighlightListCombo;
import com.katmoda.jterm.ui.component.HighlightListsForm;
import com.katmoda.jterm.ui.component.TabColorPicker;
import com.katmoda.jterm.ui.component.TerminalSettingsForm;
import com.katmoda.jterm.ui.component.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
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
        JPanel general = new JPanel(new GridBagLayout());
        int row = 0;
        addToggleRow(general, row++, "Copy to clipboard on select:", copyOnSelect);
        addToggleRow(general, row++, "Paste on right click:", pasteOnRightClick);
        addHint(general, row++, "With this on, right-click pastes; use Ctrl+right-click for the menu.");
        addToggleRow(general, row++, "Open a terminal on startup:", openTerminalOnStartup);
        addHint(general, row++, "With this off, jterm starts with no open tabs.");

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
        JPanel sessionDefaults = new JPanel(new GridBagLayout());
        int sdRow = 0;
        addFieldRow(sessionDefaults, sdRow++, "Default username:", defaultUser);
        addFieldRow(sessionDefaults, sdRow++, "Default tab color:", defaultTabColor.component());
        addHint(sessionDefaults, sdRow++, "Used by folders and sessions that don't set their own."
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
        settings.setDefaultTerminalType(terminalDefaults.terminalType());
        settings.setDefaultCharset(terminalDefaults.charset());
        settings.setDefaultFontFamily(terminalDefaults.fontFamily());
        settings.setDefaultFontSize(terminalDefaults.fontSize());
        highlightForm.commit();
        settings.setGlobalHighlightListId(HighlightListCombo.selectedId(highlightDefault));
        settings.setDefaultUsername(defaultUser.getText());
        settings.setDefaultTabColorHex(defaultTabColor.hex());
        settings.save();
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
