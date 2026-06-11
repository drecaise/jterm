package com.katmoda.jterm.dnd;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Marker payload for dragging the fixed "Local Terminal" entry onto a pane. Carries no
 * data — its presence tells the drop target to split and open a local shell.
 */
public final class LocalTransferable implements Transferable {

    public static final DataFlavor LOCAL_FLAVOR = createFlavor();

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + LocalTransferable.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{LOCAL_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return LOCAL_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!LOCAL_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return this;
    }
}
