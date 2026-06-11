package com.katmoda.jterm.dnd;

import com.katmoda.jterm.session.SshSessionConfig;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Carries an {@link SshSessionConfig} during an intra-JVM drag from the sidebar to a
 * pane. Uses a local-object flavor (no serialization needed).
 */
public final class SessionTransferable implements Transferable {

    public static final DataFlavor SESSION_FLAVOR = createFlavor();

    private final SshSessionConfig config;

    public SessionTransferable(SshSessionConfig config) {
        this.config = config;
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType
                    + ";class=" + SshSessionConfig.class.getName());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{SESSION_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return SESSION_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!SESSION_FLAVOR.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return config;
    }
}
