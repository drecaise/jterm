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
package com.katmoda.jterm.dnd;

import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.grid.PaneGrid;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries a tab (its {@link PaneGrid}) during a drag from the tab strip. Always offers
 * {@link #TAB_FLAVOR} for reordering on the strip. A <em>single-pane</em> tab additionally offers
 * {@link PaneTransferable#PANE_FLAVOR} carrying that sole pane, so it can be dropped into another
 * tab's grid; a multi-pane tab only reorders (it can't be squeezed into one cell).
 */
public final class TabTransferable implements Transferable {

    public static final DataFlavor TAB_FLAVOR = createFlavor();

    private final PaneGrid grid;
    private final GridContent solePane;

    public TabTransferable(PaneGrid grid) {
        this.grid = grid;
        this.solePane = grid.solePane();
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + PaneGrid.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return solePane != null
                ? new DataFlavor[]{TAB_FLAVOR, PaneTransferable.PANE_FLAVOR}
                : new DataFlavor[]{TAB_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        if (TAB_FLAVOR.equals(flavor)) {
            return true;
        }
        return solePane != null && PaneTransferable.PANE_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (TAB_FLAVOR.equals(flavor)) {
            return grid;
        }
        if (solePane != null && PaneTransferable.PANE_FLAVOR.equals(flavor)) {
            return solePane;
        }
        throw new UnsupportedFlavorException(flavor);
    }
}
