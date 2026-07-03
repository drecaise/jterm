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

import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.border.Border;
import java.awt.Color;

/**
 * Draws the drag-and-drop hint borders on a grid cell (a {@link TerminalPane} or SFTP pane), saving
 * and restoring the cell's real border so the highlight is transient. Shared by every {@code
 * GridContent} that is a drop target so the border logic lives in one place.
 */
public final class DropHighlighter {

    private final JComponent target;
    private Border savedBorder;

    public DropHighlighter(JComponent target) {
        this.target = target;
    }

    /** Full-border highlight shown while a dragged pane hovers this one (swap / move target). */
    public void showMoveHint() {
        saveBorder();
        target.setBorder(BorderFactory.createLineBorder(ThemeManager.accentColor(), 3));
    }

    /** Highlight the edge where a dropped session would open (right=column, bottom=row). */
    public void showDropHint(DropRegion region) {
        saveBorder();
        Color accent = ThemeManager.accentColor();
        target.setBorder(region == DropRegion.COLUMN
                ? BorderFactory.createMatteBorder(0, 0, 0, 4, accent)
                : BorderFactory.createMatteBorder(0, 0, 4, 0, accent));
    }

    /** Restore the cell's real border, ending any hint. */
    public void clearDropHint() {
        if (savedBorder != null) {
            target.setBorder(savedBorder);
            savedBorder = null;
        }
    }

    private void saveBorder() {
        if (savedBorder == null) {
            savedBorder = target.getBorder();
        }
    }
}
