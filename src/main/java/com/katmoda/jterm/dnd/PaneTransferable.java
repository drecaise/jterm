package com.katmoda.jterm.dnd;

import com.katmoda.jterm.ui.pane.TerminalPane;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries a live {@link TerminalPane} during an intra-JVM drag — either the pane grabbed by its
 * title bar, or the sole pane of a single-pane tab grabbed by its header. The receiver re-parents
 * the same pane (its terminal stays live), so a local-object flavor is used (no serialization).
 */
public final class PaneTransferable implements Transferable {

    public static final DataFlavor PANE_FLAVOR = createFlavor();

    private final TerminalPane pane;

    public PaneTransferable(TerminalPane pane) {
        this.pane = pane;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + TerminalPane.class.getName());
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
