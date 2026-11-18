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

import com.formdev.flatlaf.util.UIScale;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * A two-column label/field form. Unlike a {@code GridLayout(0, 2)}, rows keep their natural
 * height (a GridLayout stretches every row to fill whatever height the tallest tab of a dialog
 * gives it), the label column is only as wide as its labels, text fields and combo boxes take
 * the rest of the width, and buttons and switches keep their own size.
 */
public final class FormGrid extends JPanel {

    private final List<JLabel> labels = new ArrayList<>();
    private int rows;

    public FormGrid() {
        super(new GridBagLayout());
    }

    /** Adds a row and returns this form, for chaining. */
    public FormGrid row(String label, JComponent field) {
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        labels.add(l);
        int gap = rows == 0 ? 0 : UIScale.scale(6);

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = rows;
        lc.anchor = GridBagConstraints.LINE_START;
        lc.insets = new Insets(gap, 0, 0, UIScale.scale(12));
        add(l, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = rows;
        fc.weightx = 1;
        fc.anchor = GridBagConstraints.LINE_START;
        fc.fill = field instanceof AbstractButton ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(gap, 0, 0, 0);
        add(field, fc);
        rows++;
        return this;
    }

    /**
     * Gives every form the same label-column width, so fields on different tabs of one dialog
     * start at the same x. Call after all rows are added.
     */
    public static void alignLabels(FormGrid... forms) {
        int width = 0;
        for (FormGrid f : forms) {
            for (JLabel l : f.labels) {
                width = Math.max(width, l.getPreferredSize().width);
            }
        }
        for (FormGrid f : forms) {
            for (JLabel l : f.labels) {
                // Already-scaled preferred widths: not run through UIScale again.
                l.setPreferredSize(new Dimension(width, l.getPreferredSize().height));
            }
        }
    }

    /**
     * Wraps a tab's content: pinned to the top (so it isn't stretched to the dialog's tallest
     * tab) and inset from the tab row and the tab area's edges.
     */
    public static JPanel tab(JComponent content) {
        return tab(content, false);
    }

    /** As {@link #tab(JComponent)}, but {@code fill} lets the content take the tab's spare height. */
    public static JPanel tab(JComponent content, boolean fill) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), UIScale.scale(8),
                UIScale.scale(4), UIScale.scale(8)));
        p.add(content, fill ? BorderLayout.CENTER : BorderLayout.NORTH);
        return p;
    }
}
