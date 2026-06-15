package com.katmoda.jterm.dnd;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries a WSL2 distribution name during an intra-JVM drag from the sidebar to a pane.
 * Mirrors {@link LocalTransferable} (the drop opens a local pty, no network connect) but,
 * like {@link SessionTransferable}, carries the distro so the drop knows which one to launch.
 */
public final class WslTransferable implements Transferable {

    public static final DataFlavor WSL_FLAVOR = createFlavor();

    private final String distro;

    public WslTransferable(String distro) {
        this.distro = distro;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + WslTransferable.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{WSL_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return WSL_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!WSL_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return distro;
    }
}
