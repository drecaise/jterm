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
package com.katmoda.jterm.ui.component;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

/**
 * A save dialog that puts an extra option component <em>below</em> the file browser rather than
 * beside it — what {@link JFileChooser#setAccessory} cannot do.
 *
 * <p>An accessory is laid out as a full-height column at the chooser's trailing edge
 * ({@code MetalFileChooserUI.installComponents} adds it to the chooser's own
 * {@code BorderLayout.AFTER_LINE_ENDS}, and FlatLaf's {@code FlatFileChooserUI} extends that
 * class), so a single checkbox squeezes the file list into a fraction of the dialog width. The
 * panels that would allow a bottom insert ({@code getBottomPanel()}/{@code getButtonPanel()}) are
 * {@code protected} on the UI class, so the only way out is to host the chooser ourselves.
 * <strong>Don't "simplify" this back to {@code setAccessory}.</strong>
 *
 * <p>The chooser's built-in button row is hidden so ours can sit under the footer, but approve and
 * cancel still run the chooser UI's <em>own</em> actions — those are what commit the "File Name"
 * text field into a {@code File} and traverse into a highlighted directory.
 */
public final class FooterFileChooser {

    /** Keys the file-chooser UI installs its approve/cancel actions under (sun.swing.FilePane). */
    private static final String APPROVE_ACTION = "approveSelection";
    private static final String CANCEL_ACTION = "cancelSelection";

    private FooterFileChooser() {
    }

    /**
     * Shows {@code chooser} as a modal save dialog with {@code footer} on its own row between the
     * file-name fields and the Save/Cancel buttons, and returns the same
     * {@code APPROVE_OPTION}/{@code CANCEL_OPTION} constants as
     * {@link JFileChooser#showSaveDialog}. The caller keeps its reference to {@code footer} and
     * reads the choice off it after this returns.
     */
    public static int showSaveDialog(Component parent, JFileChooser chooser, JComponent footer) {
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setControlButtonsAreShown(false);

        JButton approve = new JButton(chooser.getUI().getApproveButtonText(chooser));
        JButton cancel = new JButton(UIManager.getString("FileChooser.cancelButtonText"));
        approve.addActionListener(e -> fire(chooser, APPROVE_ACTION));
        cancel.addActionListener(e -> fire(chooser, CANCEL_ACTION));

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                chooser.getDialogTitle(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content(chooser, footer, approve, cancel));
        dialog.getRootPane().setDefaultButton(approve);

        // The chooser binds ESCAPE itself, but only WHEN_ANCESTOR_OF_FOCUSED_COMPONENT — that never
        // fires while focus sits on the footer, which is outside the chooser.
        dialog.getRootPane().registerKeyboardAction(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                fire(chooser, CANCEL_ACTION);
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Both buttons, ESCAPE and Enter all end up in the chooser's approve/cancel actions, so
        // listening for what those fire is the single exit path. Closing the window fires nothing
        // and leaves the seeded CANCEL_OPTION.
        int[] result = {JFileChooser.CANCEL_OPTION};
        ActionListener exit = e -> {
            result[0] = JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())
                    ? JFileChooser.APPROVE_OPTION : JFileChooser.CANCEL_OPTION;
            dialog.dispose();
        };
        chooser.addActionListener(exit);
        try {
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
        } finally {
            chooser.removeActionListener(exit);
            dialog.dispose();
        }
        return result[0];
    }

    /**
     * Chooser above, then the footer row, then the buttons — all full width.
     *
     * <p>The insets and gaps here are deliberately <em>not</em> put through {@code UIScale}, which
     * the usual rule for hand-written pixels would demand: they mirror the chooser's own
     * {@code EmptyBorder(12, 12, 11, 11)} and {@code ButtonAreaLayout.hGap} from
     * {@code MetalFileChooserUI}, and those are raw pixels (FlatLaf's UI just calls
     * {@code super.installComponents}). Scaling ours was measured to push the footer 6 px right of
     * the file list at 150%.
     */
    private static JPanel content(JFileChooser chooser, JComponent footer,
            JButton approve, JButton cancel) {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
        buttons.add(approve);
        buttons.add(cancel);

        JPanel footerRow = new JPanel(new BorderLayout());
        footerRow.add(footer, BorderLayout.WEST);

        JPanel south = new JPanel(new BorderLayout(0, 11));
        south.setBorder(BorderFactory.createEmptyBorder(0, 12, 11, 11));
        south.add(footerRow, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.add(chooser, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        return content;
    }

    /**
     * Runs one of the chooser UI's own actions. The event carries no modifiers on purpose:
     * {@code BasicFileChooserUI.ApproveSelectionAction} reads the menu-shortcut modifier as
     * "traverse into the selection instead of accepting it".
     */
    private static void fire(JFileChooser chooser, String key) {
        Action action = chooser.getActionMap().get(key);
        if (action != null) {
            action.actionPerformed(new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED, key));
        }
    }
}
