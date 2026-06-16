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
package com.katmoda.jterm.ui.macro;

import com.katmoda.jterm.macro.MacroKey;
import com.katmoda.jterm.macro.MacroStep;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/**
 * Add/edit a single macro line (MobaXterm's line editor): a radio chooses the step type —
 * <b>Text</b> (with a per-keystroke delay), <b>Key press</b>, or <b>Sleep</b> — and the
 * matching input is enabled. "Wait for pattern" is intentionally omitted (out of scope).
 */
public final class MacroLineDialog extends JDialog {

    private final JRadioButton textRadio = new JRadioButton("Text:");
    private final JRadioButton keyRadio = new JRadioButton("Key press:");
    private final JRadioButton sleepRadio = new JRadioButton("Sleep:");

    private final JTextField textField = new JTextField(24);
    private final JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 10));
    private final JComboBox<MacroKey> keyCombo = new JComboBox<>(MacroKey.values());
    private final JSpinner sleepSpinner = new JSpinner(new SpinnerNumberModel(200, 0, 3600000, 100));

    private MacroStep result;

    private MacroLineDialog(Window owner, MacroStep initial) {
        super(owner, "Macro line", ModalityType.APPLICATION_MODAL);
        keyCombo.setRenderer(new MacroKeyRenderer());
        populate(initial);
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(440, getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the modal editor. {@code initial} pre-fills the form (or {@code null} for a new
     * line). Returns the built step, or {@code null} if cancelled.
     */
    public static MacroStep edit(Window owner, MacroStep initial) {
        MacroLineDialog dialog = new MacroLineDialog(owner, initial);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void populate(MacroStep initial) {
        switch (initial) {
            case MacroStep.TextStep t -> {
                textRadio.setSelected(true);
                textField.setText(t.text());
                delaySpinner.setValue(t.keystrokeDelayMs());
            }
            case MacroStep.KeyStep k -> {
                keyRadio.setSelected(true);
                keyCombo.setSelectedItem(k.key());
            }
            case MacroStep.SleepStep s -> {
                sleepRadio.setSelected(true);
                sleepSpinner.setValue(s.ms());
            }
            case null -> textRadio.setSelected(true);
        }
        syncEnabled();
    }

    private JPanel buildContent() {
        ButtonGroup group = new ButtonGroup();
        group.add(textRadio);
        group.add(keyRadio);
        group.add(sleepRadio);
        textRadio.addActionListener(e -> syncEnabled());
        keyRadio.addActionListener(e -> syncEnabled());
        sleepRadio.addActionListener(e -> syncEnabled());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;

        // Text row + delay
        g.gridx = 0;
        g.gridy = 0;
        form.add(textRadio, g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        form.add(textField, g);
        g.gridx = 0;
        g.gridy = 1;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        form.add(new javax.swing.JLabel("Delay between keystrokes (ms):"), g);
        g.gridx = 1;
        form.add(delaySpinner, g);

        // Key press row
        g.gridx = 0;
        g.gridy = 2;
        form.add(keyRadio, g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        form.add(keyCombo, g);

        // Sleep row
        g.gridx = 0;
        g.gridy = 3;
        g.fill = GridBagConstraints.NONE;
        form.add(sleepRadio, g);
        g.gridx = 1;
        form.add(sleepSpinner, g);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> onOk());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        return content;
    }

    private void syncEnabled() {
        textField.setEnabled(textRadio.isSelected());
        delaySpinner.setEnabled(textRadio.isSelected());
        keyCombo.setEnabled(keyRadio.isSelected());
        sleepSpinner.setEnabled(sleepRadio.isSelected());
    }

    private void onOk() {
        if (textRadio.isSelected()) {
            result = new MacroStep.TextStep(textField.getText(), (Integer) delaySpinner.getValue());
        } else if (keyRadio.isSelected()) {
            result = new MacroStep.KeyStep((MacroKey) keyCombo.getSelectedItem());
        } else {
            result = new MacroStep.SleepStep((Integer) sleepSpinner.getValue());
        }
        dispose();
    }

    /** Renders {@link MacroKey} options by their human label. */
    private static final class MacroKeyRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof MacroKey key) {
                setText(key.displayLabel());
            }
            return this;
        }
    }
}
