package com.katmoda.jterm.dnd;

import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.pane.TerminalPane;

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
    private final TerminalPane solePane;

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
