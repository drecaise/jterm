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
package com.katmoda.jterm.ui.rdp;

import com.katmoda.jterm.terminal.rdp.RdpClipboardCodec;
import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Joins the system clipboard to one RDP session's clipboard channel — text and images, each
 * direction enabled separately by the session's settings.
 *
 * <p><b>Local to remote.</b> A change of the local clipboard is <em>announced</em> to the server
 * ({@code offer}); the content is read and sent only when the server pastes ({@link #onRequest}).
 * Nothing is read from the clipboard in between, so whatever sits on it (a password manager's
 * entry, say) goes nowhere unless a remote application actually asks for it.</p>
 *
 * <p><b>A new copy is not always an event.</b> The JDK fires a flavor event only when the <em>set of
 * formats</em> changes ({@code SunClipboard.checkChange} returns early otherwise), so copying new text
 * over old text is silent, and a Windows server caches what it has rendered until it is sent a new
 * format list. {@link #refresh()} therefore re-announces on demand; the pane calls it whenever the
 * remote desktop gains focus, which is when someone who copied locally is about to paste.</p>
 *
 * <p><b>Remote to local.</b> The server announces what it holds ({@link #onRemoteOffer}); this
 * fetches it straight away and puts it on the local clipboard, text preferred over an image. One
 * fetch is in flight at a time; an announcement arriving meanwhile is remembered and fetched after.
 * A server that never answers is given up on after {@link #FETCH_TIMEOUT_SECONDS}.</p>
 *
 * <p><b>No echo.</b> What is written for the server is remembered by identity, and the resulting
 * clipboard event is not announced back to the server it came from.</p>
 *
 * <p>All work runs on one private thread; the entry points only queue. The class touches the
 * clipboard but no component, and takes the clipboard and the connection as parameters, so it is
 * tested against a private {@link Clipboard} and a recording {@link Sink}.</p>
 */
public final class RdpClipboardBridge implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RdpClipboardBridge.class);
    static final int FETCH_TIMEOUT_SECONDS = 20;

    /** The connection, as far as the bridge cares. */
    public interface Sink {
        void offer(int kinds);

        void data(int kind, boolean ok, byte[] bytes);

        void fetch(int kind);
    }

    private final Sink sink;
    private final Clipboard clipboard;
    private final boolean remoteToLocal;
    private final boolean localToRemote;
    private final long fetchTimeoutMillis;
    private final FlavorListener flavorListener = e -> submit(this::offerLocal);

    private ScheduledExecutorService executor; // created by start(), so a failed start leaks nothing
    private volatile boolean closed;

    // Everything below is touched only on the executor thread.
    private volatile Transferable written; // the last thing this bridge put on the clipboard
    private int lastOffered = -1;
    private int fetchInFlight;
    private int queuedKinds = -1;
    private ScheduledFuture<?> fetchWatchdog;

    public RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote) {
        this(sink, clipboard, remoteToLocal, localToRemote, TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS));
    }

    RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote,
                       long fetchTimeoutMillis) {
        this.sink = sink;
        this.clipboard = clipboard;
        this.remoteToLocal = remoteToLocal;
        this.localToRemote = localToRemote;
        this.fetchTimeoutMillis = fetchTimeoutMillis;
    }

    /** Starts watching the local clipboard (when it is shared outwards) and announces its content. */
    public synchronized void start() {
        if (closed || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rdp-clipboard");
            t.setDaemon(true);
            return t;
        });
        if (localToRemote) {
            clipboard.addFlavorListener(flavorListener);
            submit(this::offerLocal);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            clipboard.removeFlavorListener(flavorListener);
            executor.shutdownNow();
        }
    }

    private synchronized void submit(Runnable task) {
        if (!closed && executor != null) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        LOG.debug("RDP clipboard task failed", e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // closed concurrently
            }
        }
    }

    // ------------------------------------------------------------------------ local -> remote

    /**
     * Announces the local clipboard again, even if it looks unchanged (see the class note). Cheap:
     * an offer carries no content. Does nothing when local-to-remote is off.
     */
    public void refresh() {
        if (localToRemote) {
            submit(this::offerLocal);
        }
    }

    /** Announces the local clipboard's kinds, unless it is something this bridge itself just wrote. */
    private void offerLocal() {
        Transferable t = contents();
        if (t != null && t == written) {
            return; // the server's own content coming back: not news to it
        }
        int kinds = kindsOf(t);
        if (kinds == 0 && lastOffered == 0) {
            return;
        }
        lastOffered = kinds;
        sink.offer(kinds);
    }

    /** The server pastes {@code kind}: read the clipboard now and answer. Always answers. */
    public void onRequest(int kind) {
        submit(() -> {
            byte[] out = null;
            try {
                out = localToRemote ? read(kind) : null;
            } catch (RuntimeException | OutOfMemoryError e) {
                LOG.debug("could not read the clipboard for the server", e);
            }
            sink.data(kind, out != null, out);
        });
    }

    private byte[] read(int kind) {
        Transferable t = contents();
        if (t == null) {
            return null;
        }
        try {
            if (kind == RdpProtocol.CLIPKIND_TEXT && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return RdpClipboardCodec.encodeText((String) t.getTransferData(DataFlavor.stringFlavor));
            }
            if (kind == RdpProtocol.CLIPKIND_IMAGE && t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                return RdpClipboardCodec.encodeImage(loaded(
                        (Image) t.getTransferData(DataFlavor.imageFlavor)));
            }
        } catch (UnsupportedFlavorException | java.io.IOException | IllegalStateException e) {
            LOG.debug("clipboard changed while it was being read", e);
        }
        return null;
    }

    private static int kindsOf(Transferable t) {
        if (t == null) {
            return 0;
        }
        int kinds = 0;
        if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            kinds |= RdpProtocol.CLIPKIND_TEXT;
        }
        if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            kinds |= RdpProtocol.CLIPKIND_IMAGE;
        }
        return kinds;
    }

    // ------------------------------------------------------------------------ remote -> local

    /** The server's clipboard changed; {@code kinds} is a {@code CLIPKIND_*} mask (0 = emptied). */
    public void onRemoteOffer(int kinds) {
        if (!remoteToLocal) {
            return;
        }
        submit(() -> {
            if (fetchInFlight != 0) {
                queuedKinds = kinds;
            } else {
                startFetch(kinds);
            }
        });
    }

    private void startFetch(int kinds) {
        int kind = (kinds & RdpProtocol.CLIPKIND_TEXT) != 0 ? RdpProtocol.CLIPKIND_TEXT
                : (kinds & RdpProtocol.CLIPKIND_IMAGE) != 0 ? RdpProtocol.CLIPKIND_IMAGE : 0;
        if (kind == 0) {
            return;
        }
        fetchInFlight = kind;
        fetchWatchdog = executor.schedule(() -> {
            LOG.debug("the server did not answer a clipboard fetch; giving up on it");
            fetchFinished();
        }, fetchTimeoutMillis, TimeUnit.MILLISECONDS);
        sink.fetch(kind);
    }

    /** The server's answer to a fetch. A stale or unsolicited one is dropped. */
    public void onRemoteData(int kind, boolean ok, byte[] data) {
        submit(() -> {
            if (fetchInFlight != kind) {
                return;
            }
            if (ok) {
                apply(kind, data);
            }
            fetchFinished();
        });
    }

    private void fetchFinished() {
        fetchInFlight = 0;
        if (fetchWatchdog != null) {
            fetchWatchdog.cancel(false);
            fetchWatchdog = null;
        }
        if (queuedKinds >= 0) {
            int next = queuedKinds;
            queuedKinds = -1;
            startFetch(next);
        }
    }

    private void apply(int kind, byte[] data) {
        Transferable t = null;
        if (kind == RdpProtocol.CLIPKIND_TEXT) {
            String text = RdpClipboardCodec.decodeText(data);
            if (text != null) {
                t = new RemoteText(text);
            }
        } else if (kind == RdpProtocol.CLIPKIND_IMAGE) {
            BufferedImage image = RdpClipboardCodec.decodeImage(data);
            if (image != null) {
                t = new RemoteImage(image);
            }
        }
        if (t == null) {
            return;
        }
        written = t; // before the write: the clipboard event can fire before setContents returns
        try {
            clipboard.setContents(t, null);
        } catch (IllegalStateException e) {
            LOG.debug("the clipboard was busy; the server's content was not copied", e);
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private Transferable contents() {
        try {
            return clipboard.getContents(null);
        } catch (IllegalStateException e) {
            return null; // another application holds the clipboard open; the next event retries
        }
    }

    /** A fully loaded image: toolkit images report their size only once loaded. */
    private static Image loaded(Image image) {
        return image instanceof BufferedImage ? image : new ImageIcon(image).getImage();
    }

    /** Text that came from the server. A distinct type, so identity marks it as ours. */
    private static final class RemoteText extends StringSelection {
        RemoteText(String text) {
            super(text);
        }
    }

    /** An image that came from the server. */
    private static final class RemoteImage implements Transferable {
        private final BufferedImage image;

        RemoteImage(BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
