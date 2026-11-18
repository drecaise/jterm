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

import com.katmoda.jterm.session.UnknownSessionNode;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries an {@link UnknownSessionNode} (a node type from a newer jterm) during an intra-JVM drag
 * within the sidebar tree, so it can be moved to another folder. Like {@link FolderTransferable}
 * it has a flavor of its own that only the sidebar tree recognises: panes and the tab strip's "+"
 * never see something they could try to launch.
 */
public final class UnknownNodeTransferable implements Transferable {

    public static final DataFlavor UNKNOWN_NODE_FLAVOR = createFlavor();

    private final UnknownSessionNode node;

    public UnknownNodeTransferable(UnknownSessionNode node) {
        this.node = node;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + UnknownSessionNode.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{UNKNOWN_NODE_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return UNKNOWN_NODE_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!UNKNOWN_NODE_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return node;
    }
}
