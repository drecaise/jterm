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

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries a live {@link GridContent} pane during an intra-JVM drag — a terminal or SFTP pane
 * grabbed by its bottom bar, or the sole pane of a single-pane tab grabbed by its header. The
 * receiver re-parents the same pane (its session/connection stays live), so a local-object flavor
 * is used (no serialization).
 */
public final class PaneTransferable implements Transferable {

    public static final DataFlavor PANE_FLAVOR = createFlavor();

    private final GridContent pane;

    public PaneTransferable(GridContent pane) {
        this.pane = pane;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + GridContent.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{PANE_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return PANE_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!PANE_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return pane;
    }
}
