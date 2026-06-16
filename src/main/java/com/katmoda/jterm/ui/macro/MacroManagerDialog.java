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

import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the macro collection: a list of all macros with <i>New / Edit / Delete</i>.
 * Mutates and saves {@link MacroLibrary} directly; the caller rebuilds the Macros menu after
 * this modal dialog closes.
 */
public final class MacroManagerDialog extends JDialog {

    private final Keymap keymap;
    private final DefaultListModel<Macro> model = new DefaultListModel<>();
    private final JList<Macro> list = new JList<>(model);

    private MacroManagerDialog(Window owner, Keymap keymap) {
        super(owner, "Macros", ModalityType.APPLICATION_MODAL);
        this.keymap = keymap;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        reload();
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(420, 320));
        setLocationRelativeTo(owner);
    }

    /** Shows the modal manager. */
    public static void show(Window owner, Keymap keymap) {
        new MacroManagerDialog(owner, keymap).setVisible(true);
    }

    private void reload() {
        model.clear();
        for (Macro m : MacroLibrary.get().macros()) {
            model.addElement(m);
        }
    }

    private JPanel buildContent() {
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 6));

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 12));
        JButton add = new JButton("New…");
        add.addActionListener(e -> newMacro());
        JButton edit = new JButton("Edit…");
        edit.addActionListener(e -> editMacro());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> deleteMacro());
        buttons.add(add);
        buttons.add(edit);
        buttons.add(delete);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel closeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        closeBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        closeBar.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.EAST);
        content.add(closeBar, BorderLayout.SOUTH);
        return content;
    }

    private void newMacro() {
        Macro created = MacroEditDialog.edit(this, new Macro(), keymap, others(null));
        if (created != null) {
            MacroLibrary.get().add(created);
            MacroLibrary.get().save();
            reload();
            list.setSelectedValue(created, true);
        }
    }

    private void editMacro() {
        Macro selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        Macro edited = MacroEditDialog.edit(this, selected, keymap, others(selected));
        if (edited != null) {
            MacroLibrary.get().replace(edited);
            MacroLibrary.get().save();
            reload();
            list.setSelectedValue(edited, true);
        }
    }

    private void deleteMacro() {
        Macro selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete macro \"" + selected.getName() + "\"?", "Macros",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.OK_OPTION) {
            MacroLibrary.get().remove(selected);
            MacroLibrary.get().save();
            reload();
        }
    }

    /** All macros except {@code exclude} (by id), for hotkey-conflict checks. */
    private List<Macro> others(Macro exclude) {
        List<Macro> result = new ArrayList<>();
        for (Macro m : MacroLibrary.get().macros()) {
            if (exclude == null || !m.getId().equals(exclude.getId())) {
                result.add(m);
            }
        }
        return result;
    }
}
