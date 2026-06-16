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

import com.katmoda.jterm.session.FolderNode;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries a {@link FolderNode} during an intra-JVM drag within the sidebar tree, so a folder
 * can be re-parented onto another folder. Uses a local-object flavor (no serialization needed).
 *
 * <p>This is a distinct flavor from {@link SessionTransferable#SESSION_FLAVOR} on purpose: only
 * the sidebar tree recognises it, so a folder cannot be dropped onto a terminal pane (there is
 * nothing to launch).
 */
public final class FolderTransferable implements Transferable {

    public static final DataFlavor FOLDER_FLAVOR = createFlavor();

    private final FolderNode folder;

    public FolderTransferable(FolderNode folder) {
        this.folder = folder;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + FolderNode.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{FOLDER_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FOLDER_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!FOLDER_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return folder;
    }
}
