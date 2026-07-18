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

import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * Lays out a {@link PaneGrid}'s children in row-major add order (index {@code i} → row
 * {@code i/cols}, column {@code i%cols} — exactly the order {@code relayout()} adds them),
 * dividing the container between rows/columns in proportion to per-axis weights and leaving
 * a fixed gutter between bands as grab space for divider dragging. Like
 * {@link java.awt.GridLayout} — and deliberately unlike {@code GridBagLayout} — children's
 * preferred sizes are ignored, so a cell with a large preferred width (e.g. the SFTP
 * browser's wide toolbar/table) cannot hog its row.
 *
 * <p>The weight arrays are shared with {@link PaneGrid}, which owns and mutates them; only
 * the first {@code rows}/{@code cols} entries are consulted. Besides laying out, this class
 * is the single home of the grid's pixel geometry: band positions/sizes, divider hit-testing
 * and the gutter rectangle used for hover painting.</p>
 */
final class WeightedGridLayout implements LayoutManager {

    enum Axis { ROW, COLUMN }

    /** A grabbable divider: the gutter before row/column {@code index} (1..count-1). */
    record Divider(Axis axis, int index) {
    }

    private final double[] rowWeights;
    private final double[] colWeights;
    private final int gutter;
    private int rows = 1;
    private int cols = 1;

    WeightedGridLayout(double[] rowWeights, double[] colWeights, int gutter) {
        this.rowWeights = rowWeights;
        this.colWeights = colWeights;
        this.gutter = gutter;
    }

    /** Called from {@code PaneGrid.relayout()} before the children are re-added. */
    void setGrid(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    // ---- pixel geometry (used by layout, hit-testing and hover painting) ----

    /** Left x of each column's content band. */
    int[] colX(Container parent) {
        Insets in = parent.getInsets();
        return bands(parent.getWidth() - in.left - in.right, cols, colWeights, in.left)[0];
    }

    /** Content width of each column. */
    int[] colW(Container parent) {
        Insets in = parent.getInsets();
        return bands(parent.getWidth() - in.left - in.right, cols, colWeights, in.left)[1];
    }

    /** Top y of each row's content band. */
    int[] rowY(Container parent) {
        Insets in = parent.getInsets();
        return bands(parent.getHeight() - in.top - in.bottom, rows, rowWeights, in.top)[0];
    }

    /** Content height of each row. */
    int[] rowH(Container parent) {
        Insets in = parent.getInsets();
        return bands(parent.getHeight() - in.top - in.bottom, rows, rowWeights, in.top)[1];
    }

    /**
     * The divider under a point, or {@code null} over cell content or outside the grid.
     * Column dividers win at row/column gutter crossings.
     */
    Divider hitTest(int x, int y, Container parent) {
        Insets in = parent.getInsets();
        if (x < in.left || y < in.top
                || x >= parent.getWidth() - in.right || y >= parent.getHeight() - in.bottom) {
            return null;
        }
        int[] cx = colX(parent);
        for (int c = 1; c < cols; c++) {
            if (x >= cx[c] - gutter && x < cx[c]) {
                return new Divider(Axis.COLUMN, c);
            }
        }
        int[] ry = rowY(parent);
        for (int r = 1; r < rows; r++) {
            if (y >= ry[r] - gutter && y < ry[r]) {
                return new Divider(Axis.ROW, r);
            }
        }
        return null;
    }

    /** The full-span gutter strip of a divider (for the hover highlight). */
    Rectangle gutterRect(Divider d, Container parent) {
        Insets in = parent.getInsets();
        if (d.axis() == Axis.COLUMN) {
            int x = colX(parent)[d.index()] - gutter;
            return new Rectangle(x, in.top, gutter, parent.getHeight() - in.top - in.bottom);
        }
        int y = rowY(parent)[d.index()] - gutter;
        return new Rectangle(in.left, y, parent.getWidth() - in.left - in.right, gutter);
    }

    /**
     * Split {@code span} pixels into {@code count} weighted bands separated by gutters.
     * Returns {@code [positions, sizes]}. The rounding remainder goes to the last band so the
     * bands always sum exactly to the available space (no per-pass 1px drift).
     */
    private int[][] bands(int span, int count, double[] weights, int origin) {
        int content = Math.max(0, span - (count - 1) * gutter);
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += weights[i];
        }
        int[] size = new int[count];
        int used = 0;
        for (int i = 0; i < count - 1; i++) {
            size[i] = (int) Math.round(content * (sum > 0 ? weights[i] / sum : 1.0 / count));
            used += size[i];
        }
        size[count - 1] = Math.max(0, content - used);
        int[] pos = new int[count];
        int p = origin;
        for (int i = 0; i < count; i++) {
            pos[i] = p;
            p += size[i] + gutter;
        }
        return new int[][]{pos, size};
    }

    // ---- LayoutManager ----

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            int[] cx = colX(parent);
            int[] cw = colW(parent);
            int[] ry = rowY(parent);
            int[] rh = rowH(parent);
            int n = Math.min(parent.getComponentCount(), rows * cols);
            for (int i = 0; i < n; i++) {
                parent.getComponent(i).setBounds(cx[i % cols], ry[i / cols], cw[i % cols], rh[i / cols]);
            }
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return gridSize(parent, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return gridSize(parent, false);
    }

    /** As {@link java.awt.GridLayout}: every cell as big as the largest child, plus gutters. */
    private Dimension gridSize(Container parent, boolean preferred) {
        synchronized (parent.getTreeLock()) {
            int cellW = 0;
            int cellH = 0;
            for (int i = 0; i < parent.getComponentCount(); i++) {
                Dimension d = preferred
                        ? parent.getComponent(i).getPreferredSize()
                        : parent.getComponent(i).getMinimumSize();
                cellW = Math.max(cellW, d.width);
                cellH = Math.max(cellH, d.height);
            }
            Insets in = parent.getInsets();
            return new Dimension(
                    in.left + in.right + cols * cellW + (cols - 1) * gutter,
                    in.top + in.bottom + rows * cellH + (rows - 1) * gutter);
        }
    }

    @Override
    public void addLayoutComponent(String name, java.awt.Component comp) {
        // Children are positioned purely by add order; nothing to record.
    }

    @Override
    public void removeLayoutComponent(java.awt.Component comp) {
        // Nothing to record.
    }
}
