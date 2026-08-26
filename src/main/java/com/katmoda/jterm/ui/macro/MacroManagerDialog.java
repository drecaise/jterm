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

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroCrypto;
import com.katmoda.jterm.macro.MacroDuplicator;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.ErrorDialog;

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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the macro collection: a list of all macros with <i>New / Edit / Duplicate / Delete</i>
 * plus <i>Export / Import</i>. Mutates and saves {@link MacroLibrary} directly; the caller rebuilds the
 * Macros menu after this modal dialog closes.
 *
 * <p>The list is multi-select so an export can cover a chosen subset; Edit, Duplicate and Delete
 * guard on the selection size rather than assuming one. When macro encryption is on, every write goes through
 * {@link #persist()}, which unlocks the vault first — {@code MacroLibrary.save()} refuses to write
 * plaintext when the setting says otherwise, so an un-unlocked save would silently lose the edit.</p>
 */
public final class MacroManagerDialog extends JDialog {

    private final Keymap keymap;
    private final DefaultListModel<Macro> model = new DefaultListModel<>();
    private final JList<Macro> list = new JList<>(model);

    private MacroManagerDialog(Window owner, Keymap keymap) {
        super(owner, "Macros", ModalityType.APPLICATION_MODAL);
        this.keymap = keymap;
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        reload();
        setContentPane(buildContent());
        pack();
        setMinimumSize(UIScale.scale(new Dimension(420, 320)));
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
        // A JList's preferred width is its widest cell, so without a cap one long macro name would
        // decide the window's width through pack(). Plain Swing pixels — scale them by hand.
        scroll.setPreferredSize(UIScale.scale(new Dimension(380, 280)));

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 12));
        JButton add = new JButton("New…");
        add.addActionListener(e -> newMacro());
        JButton edit = new JButton("Edit…");
        edit.addActionListener(e -> editMacro());
        JButton duplicate = new JButton("Duplicate…");
        duplicate.setToolTipText("Opens a copy of the selected macro, without its hotkey");
        duplicate.addActionListener(e -> duplicateMacro());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> deleteMacro());
        JButton export = new JButton("Export…");
        export.setToolTipText("Exports the selected macros, or all of them when nothing is selected");
        export.addActionListener(e -> exportMacros());
        JButton importButton = new JButton("Import…");
        importButton.addActionListener(e -> importMacros());
        buttons.add(add);
        buttons.add(edit);
        buttons.add(duplicate);
        buttons.add(delete);
        buttons.add(export);
        buttons.add(importButton);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        // Line Close up with the button column above it rather than leaving it 6px short and
        // narrower: GridLayout sizes every cell to the widest button, so mirror that width here,
        // and drop the FlowLayout hgap that would otherwise inset the right edge. The width comes
        // from already-scaled preferred sizes, so it must not be run through UIScale again.
        int columnWidth = 0;
        for (Component b : buttons.getComponents()) {
            columnWidth = Math.max(columnWidth, b.getPreferredSize().width);
        }
        close.setPreferredSize(new Dimension(columnWidth, close.getPreferredSize().height));
        JPanel closeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
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
        if (created == null) {
            return;
        }
        MacroLibrary.get().add(created);
        if (!persist()) {
            MacroLibrary.get().remove(created);
        }
        reload();
        list.setSelectedValue(created, true);
    }

    private void editMacro() {
        Macro selected = singleSelection();
        if (selected == null) {
            return;
        }
        // The editor needs readable steps, so open a decrypted copy. The library keeps the sealed
        // original until the edit is committed, so cancelling changes nothing on disk or in memory.
        Macro plain = resolved(selected);
        if (plain == null) {
            return;
        }
        Macro edited = MacroEditDialog.edit(this, plain, keymap, others(selected));
        if (edited == null) {
            return;
        }
        MacroLibrary.get().replace(edited);
        if (!persist()) {
            MacroLibrary.get().replace(selected); // put the sealed original back
        }
        reload();
        list.setSelectedValue(edited, true);
    }

    /**
     * Opens the editor on a copy of the selected macro, and adds it only if that is accepted — so a
     * cancelled duplicate leaves the library exactly as it was, the same as <i>New</i>.
     *
     * <p>The macro is {@link #resolved} first even though nothing here reads the steps: a sealed
     * blob is bound to its macro's id (see {@link MacroDuplicator#duplicate}), so the copy has to
     * start from plaintext and be re-sealed under its own id by {@link #persist()}.</p>
     */
    private void duplicateMacro() {
        Macro selected = singleSelection();
        if (selected == null) {
            return;
        }
        Macro plain = resolved(selected);
        if (plain == null) {
            return;
        }
        Macro copy = MacroDuplicator.duplicate(plain, MacroLibrary.get().macros());
        // The copy is not in the library yet, so every saved macro is an "other" for conflict checks.
        Macro created = MacroEditDialog.edit(this, copy, keymap, others(null));
        if (created == null) {
            return;
        }
        MacroLibrary.get().add(created);
        if (!persist()) {
            MacroLibrary.get().remove(created);
        }
        reload();
        list.setSelectedValue(created, true);
    }

    private void deleteMacro() {
        List<Macro> selected = list.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        String what = (selected.size() == 1)
                ? "macro \"" + selected.get(0).getName() + "\""
                : selected.size() + " macros";
        int confirm = JOptionPane.showConfirmDialog(this, "Delete " + what + "?", "Macros",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        List<Macro> previous = new ArrayList<>(MacroLibrary.get().macros());
        for (Macro macro : selected) {
            MacroLibrary.get().remove(macro);
        }
        if (!persist()) {
            MacroLibrary.get().replaceAll(previous);
        }
        reload();
    }

    /** Exports the selected macros, or the whole library when the selection is empty. */
    private void exportMacros() {
        List<Macro> selected = list.getSelectedValuesList();
        MacroTransfer.export(this,
                selected.isEmpty() ? new ArrayList<>(MacroLibrary.get().macros()) : selected);
    }

    private void importMacros() {
        if (MacroTransfer.importMacros(this, keymap)) {
            reload();
        }
    }

    /** The selected macro when exactly one is selected, else {@code null}. */
    private Macro singleSelection() {
        List<Macro> selected = list.getSelectedValuesList();
        return (selected.size() == 1) ? selected.get(0) : null;
    }

    /** A decrypted copy of {@code macro}, or {@code null} if that was cancelled or failed. */
    private Macro resolved(Macro macro) {
        if (!macro.isSealed()) {
            return macro;
        }
        if (!VaultManager.get().ensureUnlocked(this)) {
            return null;
        }
        try {
            return MacroCrypto.resolved(macro, VaultManager.get().vault());
        } catch (VaultException e) {
            ErrorDialog.show(this, "Macros", "Could not decrypt this macro:", e);
            return null;
        }
    }

    /**
     * Saves the library, unlocking the vault first when macros are encrypted. Returns {@code false}
     * (having told the user) if the write did not happen, so callers can roll their change back
     * rather than leave memory and disk disagreeing.
     */
    private boolean persist() {
        if (AppSettings.get().isEncryptMacros() && !VaultManager.get().ensureUnlocked(this)) {
            return false;
        }
        if (MacroLibrary.get().save()) {
            return true;
        }
        JOptionPane.showMessageDialog(this,
                "Your macros could not be saved, so the change was undone.",
                "Macros", JOptionPane.ERROR_MESSAGE);
        return false;
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
