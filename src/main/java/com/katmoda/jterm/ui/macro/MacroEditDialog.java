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
import com.katmoda.jterm.macro.MacroStep;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Editor for a single {@link Macro} (MobaXterm's macro-edition dialog): a name field, the
 * ordered list of step lines with <i>Edit / Insert above / Insert below / Delete</i>, and a
 * captured global hotkey. A captured hotkey is rejected if it already belongs to a keyboard
 * shortcut or another macro.
 *
 * <p>Hotkey capture relies on the caller having suppressed the global terminal-shortcut
 * dispatcher (see {@code MainWindow.openMacroManager}), the same way the keyboard-shortcuts
 * editor does, so the combination is recorded rather than firing its action.</p>
 */
public final class MacroEditDialog extends JDialog {

    private final Macro macro;
    private final Keymap keymap;
    private final List<Macro> otherMacros;

    private final JTextField nameField = new JTextField(20);
    private final DefaultListModel<MacroStep> stepsModel = new DefaultListModel<>();
    private final JList<MacroStep> stepsList = new JList<>(stepsModel);
    private final JButton hotkeyButton = new JButton();

    private KeyStroke hotkey;
    private KeyEventDispatcher recorder;
    private boolean accepted;

    private MacroEditDialog(Window owner, Macro macro, Keymap keymap, List<Macro> otherMacros) {
        super(owner, "Macro edition", ModalityType.APPLICATION_MODAL);
        this.macro = macro;
        this.keymap = keymap;
        this.otherMacros = otherMacros;

        nameField.setText(macro.getName());
        for (MacroStep step : macro.getSteps()) {
            stepsModel.addElement(step);
        }
        hotkey = (macro.getHotkey() != null) ? KeyStroke.getKeyStroke(macro.getHotkey()) : null;

        stepsList.setCellRenderer(new StepRenderer());
        setContentPane(buildContent());
        updateHotkeyButton();
        pack();
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the modal editor on a copy of {@code macro}. {@code otherMacros} are all macros
     * except this one (for hotkey-conflict checks). Returns the edited macro, or {@code null}
     * if cancelled.
     */
    public static Macro edit(Window owner, Macro macro, Keymap keymap, List<Macro> otherMacros) {
        MacroEditDialog dialog = new MacroEditDialog(owner, macro.copy(), keymap, otherMacros);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.macro : null;
    }

    private JPanel buildContent() {
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        top.add(new JLabel("Name:"), BorderLayout.WEST);
        top.add(nameField, BorderLayout.CENTER);

        JScrollPane listScroll = new JScrollPane(stepsList);
        listScroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 6));

        JPanel lineButtons = new JPanel(new GridLayout(0, 1, 0, 6));
        lineButtons.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        lineButtons.add(button("Edit selected line", e -> editSelected()));
        lineButtons.add(button("Insert new line above", e -> insertLine(true)));
        lineButtons.add(button("Insert new line below", e -> insertLine(false)));
        lineButtons.add(button("Delete line", e -> deleteSelected()));

        JPanel center = new JPanel(new BorderLayout());
        center.add(listScroll, BorderLayout.CENTER);
        center.add(lineButtons, BorderLayout.EAST);

        JPanel hotkeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        hotkeyRow.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        hotkeyRow.add(new JLabel("Hotkey:"));
        hotkeyButton.addActionListener(e -> startRecording());
        hotkeyRow.add(hotkeyButton);
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            stopRecording();
            hotkey = null;
            updateHotkeyButton();
        });
        hotkeyRow.add(clear);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> onOk());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        buttons.add(cancel);
        buttons.add(ok);

        JPanel south = new JPanel(new BorderLayout());
        south.add(hotkeyRow, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        return content;
    }

    private JButton button(String label, java.awt.event.ActionListener action) {
        JButton b = new JButton(label);
        b.addActionListener(action);
        return b;
    }

    // ---- step list editing ----

    private void editSelected() {
        int i = stepsList.getSelectedIndex();
        if (i < 0) {
            return;
        }
        MacroStep edited = MacroLineDialog.edit(this, stepsModel.get(i));
        if (edited != null) {
            stepsModel.set(i, edited);
        }
    }

    private void insertLine(boolean above) {
        MacroStep step = MacroLineDialog.edit(this, null);
        if (step == null) {
            return;
        }
        int sel = stepsList.getSelectedIndex();
        int at;
        if (sel < 0) {
            at = stepsModel.size(); // append when nothing is selected
        } else {
            at = above ? sel : sel + 1;
        }
        stepsModel.add(at, step);
        stepsList.setSelectedIndex(at);
    }

    private void deleteSelected() {
        int i = stepsList.getSelectedIndex();
        if (i >= 0) {
            stepsModel.remove(i);
            if (!stepsModel.isEmpty()) {
                stepsList.setSelectedIndex(Math.min(i, stepsModel.size() - 1));
            }
        }
    }

    // ---- hotkey capture ----

    private void startRecording() {
        stopRecording();
        hotkeyButton.setText("Press hotkey…");
        recorder = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) {
                return true;
            }
            if (isModifierKey(e.getKeyCode())) {
                return true; // wait for a non-modifier key
            }
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                stopRecording();
                updateHotkeyButton();
                return true;
            }
            KeyStroke captured = KeyStroke.getKeyStrokeForEvent(e);
            stopRecording();
            applyCaptured(captured);
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(recorder);
    }

    private void applyCaptured(KeyStroke captured) {
        String conflict = conflictFor(captured);
        if (conflict != null) {
            JOptionPane.showMessageDialog(this,
                    "That shortcut is already used by " + conflict + ".",
                    "Hotkey in use", JOptionPane.WARNING_MESSAGE);
            updateHotkeyButton();
            return;
        }
        hotkey = captured;
        updateHotkeyButton();
    }

    /** A human description of what already owns {@code stroke}, or {@code null} if free. */
    private String conflictFor(KeyStroke stroke) {
        if (keymap.actionFor(stroke) != null) {
            return "a keyboard shortcut (" + keymap.actionFor(stroke).label() + ")";
        }
        String asString = stroke.toString();
        for (Macro other : otherMacros) {
            if (asString.equals(other.getHotkey())) {
                return "macro \"" + other.getName() + "\"";
            }
        }
        return null;
    }

    private void stopRecording() {
        if (recorder != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(recorder);
            recorder = null;
        }
    }

    private void updateHotkeyButton() {
        hotkeyButton.setText(format(hotkey));
    }

    private void onOk() {
        stopRecording();
        String name = nameField.getText().trim();
        macro.setName(name.isEmpty() ? "Macro" : name);
        macro.getSteps().clear();
        for (int i = 0; i < stepsModel.size(); i++) {
            macro.getSteps().add(stepsModel.get(i));
        }
        macro.setHotkey(hotkey != null ? hotkey.toString() : null);
        accepted = true;
        dispose();
    }

    @Override
    public void dispose() {
        stopRecording();
        super.dispose();
    }

    // ---- helpers ----

    private static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META;
    }

    private static String format(KeyStroke ks) {
        if (ks == null) {
            return "(none)";
        }
        String mods = InputEvent.getModifiersExText(ks.getModifiers());
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        return mods.isEmpty() ? key : mods + "+" + key;
    }

    /** Renders a step by its {@link MacroStep#displayLine()}. */
    private static final class StepRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof MacroStep step) {
                setText(step.displayLine());
            }
            return this;
        }
    }
}
