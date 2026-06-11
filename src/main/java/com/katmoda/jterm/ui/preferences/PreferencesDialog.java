package com.katmoda.jterm.ui.preferences;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.ui.component.ToggleSwitch;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * General preferences: small terminal-behaviour toggles persisted in {@link AppSettings}.
 * Both options read live, so applying them affects already-open terminals.
 */
public final class PreferencesDialog {

    private PreferencesDialog() {
    }

    /** Shows the modal preferences form; applies and persists the choices on OK. */
    public static void show(Component parent) {
        AppSettings settings = AppSettings.get();
        ToggleSwitch copyOnSelect = new ToggleSwitch(settings.isCopyOnSelect());
        ToggleSwitch pasteOnRightClick = new ToggleSwitch(settings.isPasteOnRightClick());

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addToggleRow(form, row++, "Copy to clipboard on select:", copyOnSelect);
        addToggleRow(form, row++, "Paste on right click:", pasteOnRightClick);
        addHint(form, row++, "With this on, right-click pastes; use Ctrl+right-click for the menu.");

        int result = JOptionPane.showConfirmDialog(parent, form, "Preferences",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setCopyOnSelect(copyOnSelect.isSelected());
        settings.setPasteOnRightClick(pasteOnRightClick.isSelected());
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

    /** A full-width, de-emphasised explanatory line spanning both columns. */
    private static void addHint(JPanel form, int row, String text) {
        JLabel hint = new JLabel(text);
        hint.setEnabled(false);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 4, 4, 4);
        form.add(hint, g);
    }
}
