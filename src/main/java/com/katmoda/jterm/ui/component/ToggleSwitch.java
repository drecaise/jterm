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

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * A modern on/off toggle switch (rounded track + sliding thumb) that behaves like a
 * {@link JCheckBox}: {@code isSelected()}, item/action listeners and keyboard toggling all
 * work unchanged. The look is rendered through a custom {@link Icon} so it integrates with
 * the button model and FlatLaf (the "on" color follows the OS/theme accent).
 */
public final class ToggleSwitch extends JCheckBox {

    public ToggleSwitch(boolean selected) {
        super((String) null, selected);
        setOpaque(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setMargin(new Insets(0, 0, 0, 0));
        setIconTextGap(0);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        Icon icon = new SwitchIcon();
        setIcon(icon);
        setSelectedIcon(icon);
        setRolloverIcon(icon);
        setRolloverSelectedIcon(icon);
        setPressedIcon(icon);
    }

    private static final class SwitchIcon implements Icon {

        private static final int WIDTH = 38;
        private static final int HEIGHT = 20;
        private static final int PADDING = 2;

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton button = (AbstractButton) c;
            boolean on = button.isSelected();
            boolean enabled = button.isEnabled();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color track = on ? accent() : offTrack();
            if (!enabled) {
                track = blend(track, background(), 0.5f);
            }
            g2.setColor(track);
            g2.fillRoundRect(x, y, WIDTH, HEIGHT, HEIGHT, HEIGHT);

            int diameter = HEIGHT - 2 * PADDING;
            int thumbX = on ? x + WIDTH - diameter - PADDING : x + PADDING;
            int thumbY = y + PADDING;
            g2.setColor(thumb());
            g2.fillOval(thumbX, thumbY, diameter, diameter);
            g2.setColor(blend(Color.GRAY, track, 0.4f));
            g2.drawOval(thumbX, thumbY, diameter, diameter);

            g2.dispose();
        }

        private static Color accent() {
            Color c = UIManager.getColor("Component.accentColor");
            if (c == null) {
                c = UIManager.getColor("Component.focusColor");
            }
            return c != null ? c : new Color(0x4A90D9);
        }

        private static Color offTrack() {
            Color c = UIManager.getColor("Component.borderColor");
            return c != null ? c : new Color(0x9AA0A6);
        }

        private static Color thumb() {
            Color c = UIManager.getColor("Slider.thumbColor");
            return c != null ? c : Color.WHITE;
        }

        private static Color background() {
            Color c = UIManager.getColor("Panel.background");
            return c != null ? c : Color.LIGHT_GRAY;
        }

        private static Color blend(Color a, Color b, float t) {
            return new Color(
                    Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                    Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                    Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
        }
    }
}
