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
import com.katmoda.jterm.terminal.rdp.files.FileSnapshot;
import com.katmoda.jterm.terminal.rdp.files.RdpFileClipboard;
import com.katmoda.jterm.terminal.rdp.files.RemoteClipboardFiles;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Joins the system clipboard to one RDP session's clipboard channel — text, HTML and images, each
 * direction enabled separately by the session's settings.
 *
 * <p><b>Local to remote.</b> A change of the local clipboard is <em>announced</em> to the server
 * ({@code offer}); the content is sent only when the server pastes ({@link #onRequest}). Whatever
 * sits on the clipboard (a password manager's entry, say) goes nowhere unless a remote application
 * actually asks for it.</p>
 *
 * <p><b>A new copy is not always an event.</b> The JDK fires a flavor event only when the <em>set of
 * formats</em> changes ({@code SunClipboard.checkChange} returns early otherwise), so copying new text
 * over old text is silent, and a Windows server caches what it has rendered until it is sent a new
 * format list. {@link #refresh()} therefore re-announces on demand; the pane calls it whenever the
 * remote desktop gains focus, which is when someone who copied locally is about to paste.</p>
 *
 * <p><b>An announcement takes the remote clipboard.</b> In the RDP clipboard protocol a format list
 * is a claim of ownership: the server replaces its own clipboard with ours. So once the server has
 * announced <em>its</em> clipboard, this bridge stops re-announcing ours until the local content
 * really changed. Without that, copying files in the remote Explorer (a format this bridge does not
 * fetch), switching away and clicking back in would replace them with the local clipboard. "Really
 * changed" is decided by a digest of the local content taken when the server claimed the clipboard
 * ({@link #fingerprint}), compared locally and never sent; it is needed because a new copy of the
 * same kind raises no event (above), and it also keeps a local clipboard manager that re-owns the
 * server's own text from bouncing it back as plain text.</p>
 *
 * <p><b>Remote to local.</b> The server announces what it holds ({@link #onRemoteOffer}); this
 * fetches it straight away and puts it on the local clipboard. Text is preferred over an image, and
 * HTML comes along with the text when the server has both (a copy from a browser or Office), so a
 * rich editor pastes formatting while a terminal still gets plain text. One copy can therefore take
 * two fetches; they run one at a time and land on the clipboard together. An announcement arriving
 * meanwhile is remembered and fetched after. A server that leaves a fetch unanswered for
 * {@link #FETCH_TIMEOUT_SECONDS} has that part given up on.</p>
 *
 * <p><b>Connecting leaves the local clipboard alone.</b> The protocol cannot tell "what the server
 * held before you connected" from "what you just copied there": when the channel opens, the server
 * announces its existing clipboard exactly as it announces a new copy. A copy on the remote desktop
 * always takes a key press or a mouse click, though, and the start-up announcement comes before
 * either. So until the pane reports the first input to the session ({@link #onUserInput}), a
 * server announcement is not fetched; the local clipboard is announced again instead, so it wins on
 * both sides, as with other RDP clients, and "copy locally, connect, paste remotely" works. Mouse
 * moves do not count: hovering cannot copy anything. A server that announces only after the user
 * has started using the session gets the old behaviour (its content is fetched).</p>
 *
 * <p><b>No echo.</b> What is written for the server is remembered by identity, and the resulting
 * clipboard event is not announced back to the server it came from.</p>
 *
 * <p><b>Files, local to remote</b> (its own opt-in, {@code filesToRemote}). A file list on the local
 * clipboard is announced as {@link RdpProtocol#CLIPKIND_FILES}. Nothing about the files leaves the
 * machine until the server pastes: its first request for a copy builds a {@link FileSnapshot} and
 * asks the user ({@link FileConsent}), and only a yes sends the list of names. The server then reads
 * the files by index ({@link #onFileRequest}), on a second thread so a large file never holds up the
 * clipboard; {@link RdpFileClipboard} decides which copy a request may read, locks included.</p>
 *
 * <p><b>Files, remote to local</b> (its own opt-in, {@code filesToLocal}). A server copy that holds
 * files is locked at once (when the server can lock), its list is fetched with the rest of the
 * copy, parsed as hostile ({@link RemoteFileList}) and handed to a {@link RemoteFilesListener}:
 * nothing is written until the user saves. {@link RemoteClipboardFiles} keeps the lock and the
 * reads a transfer makes.</p>
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

        /** Answers a {@link RdpProtocol.FileRequest}; called for every one. */
        void fileData(int streamId, boolean ok, byte[] bytes);

        /** Reads a file of the server's list (remote to local). */
        default void fetchFile(int streamId, int listIndex, int flags, long position, int cb,
                               boolean haveClipDataId, int clipDataId) {
        }

        /** Locks or unlocks the server's clipboard files (remote to local). */
        default void lockRemote(boolean lock, int clipDataId) {
        }
    }

    /** Told which server copy of files may be saved now; called on the bridge's thread. */
    public interface RemoteFilesListener {
        /** {@code offer} is the server's current file list, or null when there is none (any more). */
        void remoteFiles(RemoteClipboardFiles.Offer offer);
    }

    /** Asks the user about a paste of local files; the UI side of {@link #onRequest} for files. */
    public interface FileConsent {
        /**
         * The remote desktop wants to paste the files {@code summary} describes. Call {@code answer}
         * exactly once, from any thread: true lets it read them, false refuses.
         */
        void ask(FileSnapshot.Summary summary, Consumer<Boolean> answer);

        /** The copied files can't be pasted remotely at all; {@code reason} says why, for the user. */
        void unavailable(String reason);
    }

    private final Sink sink;
    private final Clipboard clipboard;
    private final boolean remoteToLocal;
    private final boolean localToRemote;
    private final boolean filesToRemote;
    private final FileConsent consent;
    private final RemoteClipboardFiles remoteFiles; // null unless files may come to this computer
    private final RemoteFilesListener remoteFilesListener;
    private final List<Path> protectedFolders;
    private final long fetchTimeoutMillis;
    private final FlavorListener flavorListener = e -> submit(() -> offerLocal(false));

    private ScheduledExecutorService executor; // created by start(), so a failed start leaks nothing
    private ExecutorService fileReader;        // reads file contents for the server; with executor
    private volatile boolean closed;

    // Everything below is touched only on the executor thread.
    private volatile Transferable written; // the last thing this bridge put on the clipboard
    private int lastOffered = -1;
    private String lastOfferedFingerprint; // what that announcement held; null = unknown
    private boolean armed;           // the user has typed or clicked in the session (onUserInput)
    private boolean serverOwns;      // the server announced its clipboard after our last offer
    private String ownedBaseline;    // fingerprint of the local clipboard since then; null = unknown
    private int fetchInFlight;
    private int queuedKinds = -1;
    private ScheduledFuture<?> fetchWatchdog;
    private int[] fetchPlan = new int[0]; // the kinds one server copy is fetched as, in order
    private int fetchNext;                // index into fetchPlan
    private String fetchedText;           // what this plan has collected so far
    private RdpClipboardCodec.Html fetchedHtml;
    private BufferedImage fetchedImage;
    private final RdpFileClipboard files = new RdpFileClipboard();
    private long filesGeneration;  // the server copy a FILES fetch belongs to

    public RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote) {
        this(sink, clipboard, remoteToLocal, localToRemote, TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS));
    }

    RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote,
                       long fetchTimeoutMillis) {
        this(sink, clipboard, remoteToLocal, localToRemote, false, null, List.of(), null, fetchTimeoutMillis);
    }

    /**
     * With file copy to the remote: {@code filesToRemote} only counts together with
     * {@code localToRemote}, and needs {@code consent}. {@code protectedFolders} (the vault's
     * folder, {@code ~/.ssh}) are never sent, wherever they appear in what was copied.
     */
    public RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote,
                              boolean filesToRemote, FileConsent consent, List<Path> protectedFolders) {
        this(sink, clipboard, remoteToLocal, localToRemote, filesToRemote, consent, protectedFolders, null,
                TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS));
    }

    /**
     * Both file directions: {@code remoteFiles} (non-null when the session lets the server's files be
     * saved here, which also needs {@code remoteToLocal}) is told of each server copy of files.
     */
    public RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote,
                              boolean filesToRemote, FileConsent consent, List<Path> protectedFolders,
                              RemoteFilesListener remoteFiles) {
        this(sink, clipboard, remoteToLocal, localToRemote, filesToRemote, consent, protectedFolders, remoteFiles,
                TimeUnit.SECONDS.toMillis(FETCH_TIMEOUT_SECONDS));
    }

    RdpClipboardBridge(Sink sink, Clipboard clipboard, boolean remoteToLocal, boolean localToRemote,
                       boolean filesToRemote, FileConsent consent, List<Path> protectedFolders,
                       RemoteFilesListener remoteFilesListener, long fetchTimeoutMillis) {
        this.sink = sink;
        this.clipboard = clipboard;
        this.remoteToLocal = remoteToLocal;
        this.localToRemote = localToRemote;
        this.filesToRemote = localToRemote && filesToRemote && consent != null;
        this.consent = consent;
        this.protectedFolders = List.copyOf(protectedFolders);
        this.fetchTimeoutMillis = fetchTimeoutMillis;
        this.remoteFilesListener = remoteToLocal ? remoteFilesListener : null;
        this.remoteFiles = this.remoteFilesListener == null ? null : new RemoteClipboardFiles(
                new RemoteClipboardFiles.Wire() {
                    @Override
                    public void fetch(int streamId, int listIndex, int flags, long position, int cb,
                                      boolean haveId, int id) {
                        sink.fetchFile(streamId, listIndex, flags, position, cb, haveId, id);
                    }

                    @Override
                    public void lock(boolean lock, int id) {
                        sink.lockRemote(lock, id);
                    }
                });
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
        if (filesToRemote) {
            fileReader = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rdp-clipboard-files");
                t.setDaemon(true);
                return t;
            });
        }
        if (localToRemote) {
            clipboard.addFlavorListener(flavorListener);
            submit(() -> offerLocal(false));
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            clipboard.removeFlavorListener(flavorListener);
            executor.shutdownNow();
        }
        if (fileReader != null) {
            fileReader.shutdownNow();
        }
        if (remoteFiles != null) {
            remoteFiles.close();
        }
    }

    /** Tests: waits until every task queued before this call has run. */
    void flush() throws Exception {
        ScheduledExecutorService e;
        synchronized (this) {
            e = executor;
        }
        e.submit(() -> { }).get(5, TimeUnit.SECONDS);
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
     * Announces the local clipboard again if its content changed since the last announcement, even
     * when no clipboard event said so (see the class note). Does nothing when local-to-remote is off.
     *
     * <p>Only a <em>change</em>: the pane calls this on every focus gain, and focus also comes back
     * when one of jterm's own dialogs closes, such as the file-paste prompt. An announcement replaces
     * the server's clipboard, so re-announcing unchanged content then broke the paste that had just
     * been allowed (Windows unlocks the files it was starting to read), and for files it would start
     * a new copy that asks again.</p>
     */
    public void refresh() {
        if (localToRemote) {
            submit(() -> offerLocal(true));
        }
    }

    /**
     * Announces the local clipboard's kinds, unless it is something this bridge itself just wrote,
     * the server owns the clipboard and nothing new was copied locally since it took it, or
     * ({@code onlyIfChanged}) it holds what was announced last.
     */
    private void offerLocal(boolean onlyIfChanged) {
        Transferable t;
        try {
            t = clipboard.getContents(null);
        } catch (IllegalStateException e) {
            return; // another application holds the clipboard open; the next event or focus retries
        }
        if (t != null && t == written) {
            return; // the server's own content coming back: not news to it
        }
        List<Path> fileList = filesToRemote ? fileList(t) : null;
        int kinds = kindsOf(t) | (fileList != null ? RdpProtocol.CLIPKIND_FILES : 0);
        String now = fingerprint(t);
        if (serverOwns) {
            if (kinds == 0) {
                return; // a local clear is not a copy, and announcing it would empty the server's
            }
            if (now == null) {
                return; // unreadable right now: don't take the server's clipboard on a guess
            }
            if (ownedBaseline == null) {
                ownedBaseline = now; // the baseline could not be read earlier; this is it
                return;
            }
            if (now.equals(ownedBaseline)) {
                return; // nothing copied locally since the server took the clipboard
            }
        } else if (kinds == 0 && lastOffered == 0) {
            return;
        } else if (onlyIfChanged && kinds == lastOffered && now != null && now.equals(lastOfferedFingerprint)) {
            return; // the server already has this: announcing it again would only replace it
        }
        serverOwns = false;
        ownedBaseline = null;
        lastOffered = kinds;
        lastOfferedFingerprint = now;
        files.announced(fileList); // a new copy: the user is asked afresh when the server pastes it
        sink.offer(kinds);
    }

    /** The server pastes {@code kind}: read the clipboard now and answer. Always answers. */
    public void onRequest(int kind) {
        if (kind == RdpProtocol.CLIPKIND_FILES) {
            submit(this::serveFileList);
            return;
        }
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

    // ------------------------------------------------------------------- files, local -> remote

    /**
     * The server pastes files: snapshot the copy (locally, nothing is sent), ask the user once per
     * copy, and answer with the list of names only on a yes. Always answers.
     */
    private void serveFileList() {
        List<Path> roots = filesToRemote ? fileList(contents()) : null;
        if (roots == null) {
            sink.data(RdpProtocol.CLIPKIND_FILES, false, null);
            return;
        }
        RdpFileClipboard.Copy copy = files.copyFor(roots);
        if (Boolean.FALSE.equals(copy.allowed())) {
            sink.data(RdpProtocol.CLIPKIND_FILES, false, null); // refused for this copy: not asked again
            return;
        }
        // Walked afresh for every paste (each asks for the list again), so a file edited and copied
        // again under the same path is sent as it is now; a lock still reads the copy it pinned.
        try {
            copy.setSnapshot(FileSnapshot.build(roots, protectedFolders));
        } catch (FileSnapshot.SnapshotException e) {
            copy.decide(false); // one explanation per copy, not one per paste attempt
            sink.data(RdpProtocol.CLIPKIND_FILES, false, null);
            consent.unavailable(e.getMessage());
            return;
        }
        FileSnapshot snapshot = copy.snapshot();
        if (Boolean.TRUE.equals(copy.allowed())) {
            sink.data(RdpProtocol.CLIPKIND_FILES, true, snapshot.descriptors());
            return;
        }
        boolean[] answered = new boolean[1];
        consent.ask(snapshot.summary(), allow -> submit(() -> {
            if (answered[0]) {
                return; // a second answer is a bug in the caller; the first one stands
            }
            answered[0] = true;
            copy.decide(Boolean.TRUE.equals(allow));
            boolean ok = copy.allowed();
            sink.data(RdpProtocol.CLIPKIND_FILES, ok, ok ? snapshot.descriptors() : null);
        }));
    }

    /**
     * The server reads a file it was given in a list. Which copy it may read is decided on the
     * bridge's thread; the read itself runs on the file thread. Always answers.
     */
    public void onFileRequest(RdpProtocol.FileRequest r) {
        if (!filesToRemote) {
            sink.fileData(r.streamId(), false, null);
            return;
        }
        submit(() -> {
            FileSnapshot snapshot = files.snapshotFor(r);
            if (snapshot == null) {
                sink.fileData(r.streamId(), false, null);
                return;
            }
            try {
                fileReader.execute(() -> {
                    FileSnapshot.Reply reply;
                    try {
                        reply = snapshot.serve(r);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        LOG.debug("could not read a file for the server", e);
                        reply = new FileSnapshot.Reply(false, null);
                    }
                    sink.fileData(r.streamId(), reply.ok(), reply.data());
                });
            } catch (java.util.concurrent.RejectedExecutionException closed) {
                // the session is closing; the helper is going away with it
            }
        });
    }

    /** The server's answer to a read of its files (remote to local). Any thread. */
    public void onFileRemoteData(int streamId, boolean ok, byte[] data) {
        if (remoteFiles != null) {
            remoteFiles.dataArrived(streamId, ok, data);
        }
    }

    /** The clipboard capabilities both sides agreed on: whether jterm may lock the server's files. */
    public void onClipCaps(RdpProtocol.ClipCaps caps) {
        if (remoteFiles != null) {
            remoteFiles.setCanLock(caps.canLock());
        }
    }

    /** Reads the server's files for a transfer; null unless files may come to this computer. */
    public RemoteClipboardFiles remoteFiles() {
        return remoteFiles;
    }

    /** The server locks the clipboard's current files under {@code clipDataId}. */
    public void onLock(int clipDataId) {
        if (filesToRemote) {
            submit(() -> files.lock(clipDataId));
        }
    }

    public void onUnlock(int clipDataId) {
        if (filesToRemote) {
            submit(() -> files.unlock(clipDataId));
        }
    }

    /** The files on {@code t}, as absolute paths, or null when it holds no file list. */
    static List<Path> fileList(Transferable t) {
        if (t == null || !t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            List<Path> out = new ArrayList<>();
            for (Object o : (List<?>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
                if (o instanceof File f) {
                    out.add(f.toPath().toAbsolutePath().normalize());
                }
            }
            return out.isEmpty() ? null : out;
        } catch (UnsupportedFlavorException | java.io.IOException | RuntimeException e) {
            LOG.debug("could not read the copied file list", e);
            return null;
        }
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
            DataFlavor html = htmlFlavor(t);
            if (kind == RdpProtocol.CLIPKIND_HTML && html != null) {
                return RdpClipboardCodec.encodeHtml((String) t.getTransferData(html));
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
        if (htmlFlavor(t) != null) {
            kinds |= RdpProtocol.CLIPKIND_HTML;
        }
        return kinds;
    }

    /**
     * The flavor to read {@code t}'s HTML through, or null. The whole document is preferred: the
     * CF_HTML built from it marks the fragment itself, and keeps the context (styles) around it.
     */
    static DataFlavor htmlFlavor(Transferable t) {
        if (t == null) {
            return null;
        }
        for (DataFlavor f : new DataFlavor[]{DataFlavor.allHtmlFlavor, DataFlavor.selectionHtmlFlavor,
                DataFlavor.fragmentHtmlFlavor}) {
            if (t.isDataFlavorSupported(f)) {
                return f;
            }
        }
        for (DataFlavor f : t.getTransferDataFlavors()) {
            if ("text".equals(f.getPrimaryType()) && "html".equals(f.getSubType())
                    && String.class.equals(f.getRepresentationClass())) {
                return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------ remote -> local

    /** The server's clipboard changed; {@code kinds} is a {@code CLIPKIND_*} mask (0 = emptied). */
    /**
     * The user pressed a key or a mouse button in the session: from now on a server announcement is
     * a copy they made there, and is fetched. Cheap to call repeatedly, but the pane calls it once.
     */
    public void onUserInput() {
        submit(() -> armed = true);
    }

    public void onRemoteOffer(int kinds) {
        submit(() -> {
            boolean hasFiles = remoteFiles != null && (kinds & RdpProtocol.CLIPKIND_FILES) != 0;
            if (remoteFiles != null) {
                // Any server copy ends the previous list; one with files is locked before anything else.
                filesGeneration = remoteFiles.serverCopied(hasFiles && armed);
                remoteFilesListener.remoteFiles(null);
            }
            if (!armed) {
                keepLocalClipboard();
                return;
            }
            // The server claimed the clipboard, whatever it holds (files too, which are not fetched).
            serverOwns = true;
            ownedBaseline = localFingerprint();
            if (!remoteToLocal) {
                return;
            }
            if (fetchInFlight != 0) {
                queuedKinds = kinds;
            } else {
                startFetch(kinds);
            }
        });
    }

    /**
     * The server announced what it held before the session was used: leave the local clipboard as
     * it is and, when it is shared outwards, announce it again so the remote side has it too.
     */
    private void keepLocalClipboard() {
        LOG.debug("not fetching the server's clipboard from before the session was used");
        if (localToRemote) {
            serverOwns = false;
            ownedBaseline = null;
            lastOffered = -1; // announce even an empty clipboard: ours wins at connect
            offerLocal(false);
        }
    }

    /**
     * Starts fetching one server copy: text with its HTML when there is text, else an image, else
     * HTML alone. Nothing is put on the clipboard until every part has been answered.
     */
    private void startFetch(int kinds) {
        boolean text = (kinds & RdpProtocol.CLIPKIND_TEXT) != 0;
        boolean html = (kinds & RdpProtocol.CLIPKIND_HTML) != 0;
        int[] plan;
        if (text) {
            plan = html ? new int[]{RdpProtocol.CLIPKIND_TEXT, RdpProtocol.CLIPKIND_HTML}
                    : new int[]{RdpProtocol.CLIPKIND_TEXT};
        } else if ((kinds & RdpProtocol.CLIPKIND_IMAGE) != 0) {
            plan = new int[]{RdpProtocol.CLIPKIND_IMAGE};
        } else if (html) {
            plan = new int[]{RdpProtocol.CLIPKIND_HTML};
        } else {
            plan = new int[0];
        }
        if (remoteFiles != null && (kinds & RdpProtocol.CLIPKIND_FILES) != 0) {
            plan = java.util.Arrays.copyOf(plan, plan.length + 1); // only the list: nothing is written
            plan[plan.length - 1] = RdpProtocol.CLIPKIND_FILES;
        }
        if (plan.length == 0) {
            return;
        }
        fetchPlan = plan;
        fetchNext = 0;
        fetchedText = null;
        fetchedHtml = null;
        fetchedImage = null;
        fetchPart();
    }

    /** Asks for the plan's next part, or, when every part is in, applies the copy. */
    private void fetchPart() {
        if (fetchNext >= fetchPlan.length) {
            planFinished();
            return;
        }
        int kind = fetchPlan[fetchNext++];
        fetchInFlight = kind;
        fetchWatchdog = executor.schedule(() -> {
            if (fetchInFlight == kind) {
                LOG.debug("the server did not answer a clipboard fetch; giving up on it");
                partFinished();
            }
        }, fetchTimeoutMillis, TimeUnit.MILLISECONDS);
        sink.fetch(kind);
    }

    /** The server's answer to a fetch. A stale or unsolicited one is dropped. */
    public void onRemoteData(int kind, boolean ok, byte[] data) {
        submit(() -> {
            if (fetchInFlight != kind) {
                return;
            }
            try {
                if (ok) {
                    collect(kind, data);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // The codec caps sizes; this is the backstop, so a bad payload costs one part and
                // never wedges the fetch queue until the watchdog.
                LOG.debug("could not decode the server's clipboard content", e);
            } finally {
                partFinished();
            }
        });
    }

    private void partFinished() {
        fetchInFlight = 0;
        if (fetchWatchdog != null) {
            fetchWatchdog.cancel(false);
            fetchWatchdog = null;
        }
        fetchPart();
    }

    private void planFinished() {
        try {
            apply();
        } catch (RuntimeException | OutOfMemoryError e) {
            LOG.debug("could not apply the server's clipboard content", e);
        }
        fetchedText = null;
        fetchedHtml = null;
        fetchedImage = null;
        if (queuedKinds >= 0) {
            int next = queuedKinds;
            queuedKinds = -1;
            startFetch(next);
        }
    }

    private void collect(int kind, byte[] data) {
        if (kind == RdpProtocol.CLIPKIND_FILES) {
            try {
                RemoteClipboardFiles.Offer offer = remoteFiles.listArrived(filesGeneration, RemoteFileList.parse(data));
                if (offer != null) {
                    remoteFilesListener.remoteFiles(offer);
                }
            } catch (RemoteFileList.FormatException e) {
                LOG.debug("the server's file list was refused: {}", e.getMessage());
            }
        } else if (kind == RdpProtocol.CLIPKIND_TEXT) {
            fetchedText = RdpClipboardCodec.decodeText(data);
        } else if (kind == RdpProtocol.CLIPKIND_HTML) {
            fetchedHtml = RdpClipboardCodec.decodeHtml(data);
        } else if (kind == RdpProtocol.CLIPKIND_IMAGE) {
            fetchedImage = RdpClipboardCodec.decodeImage(data);
        }
    }

    private void apply() {
        Transferable t = null;
        if (fetchedHtml != null) {
            t = new RemoteHtml(fetchedText, fetchedHtml);
        } else if (fetchedText != null) {
            t = new RemoteText(fetchedText);
        } else if (fetchedImage != null) {
            t = new RemoteImage(fetchedImage);
        }
        if (t == null) {
            return;
        }
        written = t; // before the write: the clipboard event can fire before setContents returns
        try {
            clipboard.setContents(t, null);
            ownedBaseline = fingerprint(t); // the local clipboard now holds the server's content
        } catch (IllegalStateException e) {
            LOG.debug("the clipboard was busy; the server's content was not copied", e);
        }
    }

    // ------------------------------------------------------------------------------- helpers

    /** {@link #fingerprint} of the local clipboard, or {@code null} when it is busy. */
    private String localFingerprint() {
        try {
            return fingerprint(clipboard.getContents(null));
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private Transferable contents() {
        try {
            return clipboard.getContents(null);
        } catch (IllegalStateException e) {
            return null; // another application holds the clipboard open; the next event retries
        }
    }

    /**
     * A digest of what {@code t} holds, to tell "copied something new" from "unchanged" when no
     * event says so: its file list if it has one, else its text, else its image's pixels, else its
     * HTML. Compared locally, never sent.
     * {@code "empty"} for nothing usable, {@code null} when it cannot be read right now.
     */
    static String fingerprint(Transferable t) {
        int kinds = kindsOf(t);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<Path> fileList = fileList(t);
            if (fileList != null) {
                md.update((byte) 'F'); // a file list is identified by its paths
                for (Path p : fileList) {
                    md.update(p.toString().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                }
            } else if ((kinds & RdpProtocol.CLIPKIND_TEXT) != 0) {
                md.update((byte) 'T');
                md.update(((String) t.getTransferData(DataFlavor.stringFlavor)).getBytes(StandardCharsets.UTF_8));
            } else if ((kinds & RdpProtocol.CLIPKIND_IMAGE) != 0) {
                Image image = loaded((Image) t.getTransferData(DataFlavor.imageFlavor));
                int w = image.getWidth(null);
                int h = image.getHeight(null);
                md.update(("I" + w + "x" + h).getBytes(StandardCharsets.US_ASCII));
                if (w > 0 && h > 0 && (long) w * h <= RdpClipboardCodec.MAX_PIXELS) {
                    digestPixels(md, image, w, h);
                } // larger than could ever be sent: its size is all that matters
            } else if ((kinds & RdpProtocol.CLIPKIND_HTML) != 0) {
                md.update((byte) 'H');
                md.update(((String) t.getTransferData(htmlFlavor(t))).getBytes(StandardCharsets.UTF_8));
            } else {
                return "empty";
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (UnsupportedFlavorException | java.io.IOException | RuntimeException | OutOfMemoryError
                 | NoSuchAlgorithmException e) {
            LOG.debug("could not read the clipboard to compare it", e);
            return null;
        }
    }

    private static void digestPixels(MessageDigest md, Image image, int w, int h) {
        BufferedImage argb;
        if (image instanceof BufferedImage b && b.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = b;
        } else {
            argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        int[] row = new int[w];
        ByteBuffer bytes = ByteBuffer.allocate(w * 4);
        for (int y = 0; y < h; y++) {
            argb.getRGB(0, y, w, 1, row, 0, w);
            bytes.clear();
            bytes.asIntBuffer().put(row);
            md.update(bytes.array());
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

    /**
     * HTML that came from the server, with its plain text when the server had that too. The whole
     * document answers {@link DataFlavor#allHtmlFlavor}; the selected fragment answers the other two
     * HTML flavors, which is what the JDK hands an application asking for "the selection".
     */
    private static final class RemoteHtml implements Transferable {
        private final String text; // null when the server offered HTML only
        private final RdpClipboardCodec.Html html;
        private final DataFlavor[] flavors;

        RemoteHtml(String text, RdpClipboardCodec.Html html) {
            this.text = text;
            this.html = html;
            DataFlavor[] htmlFlavors = {DataFlavor.allHtmlFlavor, DataFlavor.selectionHtmlFlavor,
                    DataFlavor.fragmentHtmlFlavor};
            if (text == null) {
                flavors = htmlFlavors;
            } else {
                flavors = new DataFlavor[htmlFlavors.length + 1];
                flavors[0] = DataFlavor.stringFlavor;
                System.arraycopy(htmlFlavors, 0, flavors, 1, htmlFlavors.length);
            }
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return flavors.clone();
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor f : flavors) {
                if (f.equals(flavor)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (text != null && DataFlavor.stringFlavor.equals(flavor)) {
                return text;
            }
            if (DataFlavor.allHtmlFlavor.equals(flavor)) {
                return html.document();
            }
            if (DataFlavor.selectionHtmlFlavor.equals(flavor) || DataFlavor.fragmentHtmlFlavor.equals(flavor)) {
                return html.fragment();
            }
            throw new UnsupportedFlavorException(flavor);
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
