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

import javax.swing.JButton;
import javax.swing.JColorChooser;
import java.awt.Color;
import java.awt.Dimension;

/**
 * A compact color swatch that always holds a concrete (non-null) {@link Color}, paints it, and
 * opens a {@link JColorChooser} on click. Used to build the terminal palette editor, where every
 * slot has a definite color (and "reset" restores a known default rather than clearing to null).
 *
 * <p>Sibling to {@link TabColorPicker}, which shares the {@code JColorChooser} idiom but carries
 * inherit/clear ({@code null}) semantics that don't apply to palette slots.</p>
 */
public final class PaletteSwatch {

    private final JButton button = new JButton();
    private final String title;
    private Color color;

    public PaletteSwatch(Color initial, String title) {
        this.title = title;
        this.color = initial;
        button.setPreferredSize(new Dimension(46, 22));
        button.setToolTipText(title + " — click to change");
        applyColor();
        button.addActionListener(a -> {
            Color picked = JColorChooser.showDialog(button, title, color);
            if (picked != null) {
                setColor(picked);
            }
        });
    }

    /** The control to add to a form. */
    public JButton component() {
        return button;
    }

    public Color color() {
        return color;
    }

    public void setColor(Color c) {
        this.color = c;
        applyColor();
    }

    private void applyColor() {
        button.setBackground(color);
        button.setOpaque(true);
        button.setBorderPainted(false);
    }
}
