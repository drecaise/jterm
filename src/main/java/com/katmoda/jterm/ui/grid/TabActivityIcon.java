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
package com.katmoda.jterm.ui.grid;

import com.katmoda.jterm.ui.pane.PaneActivity;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A tab icon for split-pane tabs: a small dot grid mirroring the {@link PaneGrid}'s
 * {@code rows × cols} layout. Each occupied cell is a dot colored by its
 * {@link PaneActivity} — {@link #NEW_OUTPUT_COLOR light blue} for unread output,
 * {@link #DISCONNECTED_COLOR red} for a disconnected pane, and the theme foreground
 * (white in dark mode, black in light) when unchanged. Empty cells are left blank so the
 * dot pattern matches the actual split layout.
 *
 * <p>Built fresh on each tab re-decoration and reads the grid live (all on the EDT), so it
 * always reflects the current geometry and per-pane state.</p>
 */
public final class TabActivityIcon implements Icon {

    /** Shared with the single-pane title-recolor path so both indicators use one palette. */
    public static final Color NEW_OUTPUT_COLOR = new Color(0x4FA3E3);
    public static final Color DISCONNECTED_COLOR = new Color(0xE05252);

    private static final int DOT = 3;
    private static final int GAP = 2;
    private static final int PAD = 1;

    private final PaneGrid grid;
    private final int rows;
    private final int cols;

    public TabActivityIcon(PaneGrid grid) {
        this.grid = grid;
        this.rows = Math.max(1, grid.rows());
        this.cols = Math.max(1, grid.cols());
    }

    /** The dot color for an activity state, or the theme base for unchanged/occupied cells. */
    public static Color colorFor(PaneActivity activity) {
        return switch (activity) {
            case NEW_OUTPUT -> NEW_OUTPUT_COLOR;
            case DISCONNECTED -> DISCONNECTED_COLOR;
            case NONE -> ThemeManager.get().isDark() ? Color.WHITE : Color.BLACK;
        };
    }

    @Override
    public int getIconWidth() {
        return PAD * 2 + cols * DOT + (cols - 1) * GAP;
    }

    @Override
    public int getIconHeight() {
        return PAD * 2 + rows * DOT + (rows - 1) * GAP;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int r = 0; r < rows; r++) {
                for (int col = 0; col < cols; col++) {
                    if (!grid.isCellOccupied(r, col)) {
                        continue; // blank cell — leave a gap so the pattern matches the layout
                    }
                    int dx = x + PAD + col * (DOT + GAP);
                    int dy = y + PAD + r * (DOT + GAP);
                    g2.setColor(colorFor(grid.activityAt(r, col)));
                    g2.fillRect(dx, dy, DOT, DOT);
                }
            }
        } finally {
            g2.dispose();
        }
    }
}
