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

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.FocusTraversalPolicy;

/**
 * An OK/Cancel {@link JOptionPane} that starts with the caret in a chosen field instead of on the
 * OK button — so a password prompt can be typed into the moment it appears.
 */
public final class DialogFocus {

    private DialogFocus() {
    }

    /**
     * Shows {@code message} as a modal OK/Cancel dialog with {@code initialFocus} focused, and
     * returns the same {@code OK_OPTION}/{@code CANCEL_OPTION}/{@code CLOSED_OPTION} constants as
     * {@link JOptionPane#showConfirmDialog}.
     *
     * <p>Focus is claimed twice on purpose, because the two paths that decide it are independent
     * and either one alone loses a race: the {@link FocusTraversalPolicy} supplies the component
     * focused when the window is first activated, while {@code JOptionPane.selectInitialValue()}
     * (called from the pane's own window-focus listener) then re-focuses the default button. A
     * {@code requestFocusInWindow()} posted with {@code invokeLater} is <em>not</em> enough — it
     * runs before that listener as often as after.
     */
    public static int showConfirm(Component parent, Object message, String title,
            JComponent initialFocus) {
        JOptionPane pane = new JOptionPane(message, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION) {
            @Override
            public void selectInitialValue() {
                initialFocus.requestFocusInWindow();
            }
        };
        JDialog dialog = pane.createDialog(parent, title);
        dialog.setFocusTraversalPolicy(new InitialComponentPolicy(
                dialog.getFocusTraversalPolicy(), initialFocus));
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
        }
        Object value = pane.getValue();
        if (value instanceof Integer option) {
            return option;
        }
        return JOptionPane.CLOSED_OPTION;
    }

    /** Delegating policy that redirects only the window's initial/default component. */
    private static final class InitialComponentPolicy extends FocusTraversalPolicy {

        private final FocusTraversalPolicy delegate;
        private final Component initial;

        InitialComponentPolicy(FocusTraversalPolicy delegate, Component initial) {
            this.delegate = delegate;
            this.initial = initial;
        }

        @Override
        public Component getInitialComponent(java.awt.Window window) {
            return initial;
        }

        @Override
        public Component getDefaultComponent(Container container) {
            return initial;
        }

        @Override
        public Component getComponentAfter(Container container, Component component) {
            return delegate.getComponentAfter(container, component);
        }

        @Override
        public Component getComponentBefore(Container container, Component component) {
            return delegate.getComponentBefore(container, component);
        }

        @Override
        public Component getFirstComponent(Container container) {
            return delegate.getFirstComponent(container);
        }

        @Override
        public Component getLastComponent(Container container) {
            return delegate.getLastComponent(container);
        }
    }
}
