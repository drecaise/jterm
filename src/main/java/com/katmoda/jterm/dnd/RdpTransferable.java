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

import com.katmoda.jterm.session.RdpSessionConfig;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries an {@link RdpSessionConfig} during an intra-JVM drag inside the sidebar (moving it to
 * another folder). A flavor of its own, separate from {@link SessionTransferable}'s, on purpose:
 * pane grids accept only flavors they can open, so an RDP drag is simply not offered to a pane
 * (opening RDP by drop is not supported yet) instead of being accepted and then failing.
 */
public final class RdpTransferable implements Transferable {

    public static final DataFlavor RDP_FLAVOR = createFlavor();

    private final RdpSessionConfig config;

    public RdpTransferable(RdpSessionConfig config) {
        this.config = config;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + RdpSessionConfig.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{RDP_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return RDP_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!RDP_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return config;
    }
}
