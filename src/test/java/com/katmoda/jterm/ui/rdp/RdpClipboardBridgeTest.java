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
import com.katmoda.jterm.terminal.rdp.files.RemoteClipboardFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bridge against a private {@link Clipboard} (so no display is needed) and a recording sink.
 * The bridge works on its own thread, so each assertion about something that must happen waits for
 * it, and each about something that must <em>not</em> happen first drains the queues ({@link #quiesce}).
 */
class RdpClipboardBridgeTest {

    private static final int TEXT = RdpProtocol.CLIPKIND_TEXT;
    private static final int IMAGE = RdpProtocol.CLIPKIND_IMAGE;
    private static final int HTML = RdpProtocol.CLIPKIND_HTML;

    private record Data(int kind, boolean ok, byte[] bytes) {
    }

    private record FileData(int streamId, boolean ok, byte[] bytes) {
    }

    private static final class Recorder implements RdpClipboardBridge.Sink {
        final List<Integer> offers = new CopyOnWriteArrayList<>();
        final List<Integer> fetches = new CopyOnWriteArrayList<>();
        final List<Data> data = new CopyOnWriteArrayList<>();
        final List<FileData> fileData = new CopyOnWriteArrayList<>();

        @Override
        public void offer(int kinds) {
            offers.add(kinds);
        }

        @Override
        public void data(int kind, boolean ok, byte[] bytes) {
            data.add(new Data(kind, ok, bytes));
        }

        @Override
        public void fetch(int kind) {
            fetches.add(kind);
        }

        @Override
        public void fileData(int streamId, boolean ok, byte[] bytes) {
            fileData.add(new FileData(streamId, ok, bytes));
        }

        final List<String> locks = new CopyOnWriteArrayList<>();
        final List<Integer> fileFetches = new CopyOnWriteArrayList<>();

        @Override
        public void lockRemote(boolean lock, int clipDataId) {
            locks.add((lock ? "lock " : "unlock ") + clipDataId);
        }

        @Override
        public void fetchFile(int streamId, int listIndex, int flags, long position, int cb,
                              boolean haveClipDataId, int clipDataId) {
            fileFetches.add(streamId);
        }
    }

    /** Records each question; the test answers through {@link #answers}. */
    private static final class Consent implements RdpClipboardBridge.FileConsent {
        final List<FileSnapshot.Summary> asked = new CopyOnWriteArrayList<>();
        final List<Consumer<Boolean>> answers = new CopyOnWriteArrayList<>();
        final List<String> unavailable = new CopyOnWriteArrayList<>();

        @Override
        public void ask(FileSnapshot.Summary summary, Consumer<Boolean> answer) {
            asked.add(summary);
            answers.add(answer);
        }

        @Override
        public void unavailable(String reason) {
            unavailable.add(reason);
        }
    }

    /** What Explorer, Finder and Nautilus put on the clipboard: a list of files. */
    private record FileList(List<File> files) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    @TempDir
    Path tmp;
    private final Consent consent = new Consent();

    private final Clipboard clipboard = new Clipboard("test");
    private final Recorder sink = new Recorder();
    private RdpClipboardBridge bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
    }

    private RdpClipboardBridge start(boolean remoteToLocal, boolean localToRemote) {
        return start(remoteToLocal, localToRemote, 150);
    }

    /** A bridge whose session the user is already using (typed or clicked in), as most tests want. */
    private RdpClipboardBridge start(boolean remoteToLocal, boolean localToRemote, long fetchTimeoutMillis) {
        startUnused(remoteToLocal, localToRemote, fetchTimeoutMillis);
        bridge.onUserInput();
        return bridge;
    }

    /** A bridge for a session just opened: no key or button has reached it yet. */
    private RdpClipboardBridge startUnused(boolean remoteToLocal, boolean localToRemote, long fetchTimeoutMillis) {
        bridge = new RdpClipboardBridge(sink, clipboard, remoteToLocal, localToRemote, fetchTimeoutMillis);
        bridge.start();
        return bridge;
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting");
    }

    /**
     * Waits until everything already set in motion has run, so a "nothing happened" assertion is
     * exact rather than timed. Two hops, in order: {@link Clipboard} delivers flavor events through
     * {@code EventQueue.invokeLater}, and the bridge then queues its work on its own thread.
     */
    private void quiesce() throws Exception {
        java.awt.EventQueue.invokeAndWait(() -> { });
        try {
            bridge.flush();
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // a closed bridge runs nothing
        }
    }

    private static byte[] textWire(String s) {
        return RdpClipboardCodec.encodeText(s);
    }

    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0x336699);
        return img;
    }

    private static class ImageSelection implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.imageFlavor.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) {
                throw new UnsupportedFlavorException(f);
            }
            return image();
        }
    }

    // ------------------------------------------------------------------------ local -> remote

    @Test
    void announcesTheLocalClipboardOnStartAndOnEveryChange() throws Exception {
        clipboard.setContents(new StringSelection("hello"), null);
        start(false, true);
        waitFor(() -> sink.offers.equals(List.of(TEXT)));

        clipboard.setContents(new ImageSelection(), null);
        waitFor(() -> sink.offers.equals(List.of(TEXT, IMAGE)));

        clipboard.setContents(new StringSelection("again"), null); // same kind, new content
        waitFor(() -> sink.offers.size() == 3 && sink.offers.get(2) == TEXT);
    }

    @Test
    void anEmptyClipboardIsAnnouncedOnceNotRepeatedly() throws Exception {
        start(false, true);
        waitFor(() -> sink.offers.equals(List.of(0)));
        clipboard.setContents(new StringSelection("x"), null);
        waitFor(() -> sink.offers.equals(List.of(0, TEXT)));
    }

    @Test
    void nothingIsReadOrSentUntilTheServerPastes() throws Exception {
        clipboard.setContents(new StringSelection("secret"), null);
        start(false, true);
        waitFor(() -> !sink.offers.isEmpty());
        quiesce();
        assertTrue(sink.data.isEmpty(), "an offer carries no content");

        bridge.onRequest(TEXT);
        waitFor(() -> sink.data.size() == 1);
        Data d = sink.data.get(0);
        assertTrue(d.ok());
        assertEquals("secret", RdpClipboardCodec.decodeText(d.bytes()));
    }

    @Test
    void anImageIsSentAsADib() throws Exception {
        clipboard.setContents(new ImageSelection(), null);
        start(false, true);
        bridge.onRequest(IMAGE);
        waitFor(() -> sink.data.size() == 1);
        assertTrue(sink.data.get(0).ok());
        BufferedImage back = RdpClipboardCodec.decodeImage(sink.data.get(0).bytes());
        assertNotNull(back);
        assertEquals(0x336699, back.getRGB(1, 1) & 0xFFFFFF);
    }

    @Test
    void aRequestForAKindThatIsNotThereIsStillAnswered() throws Exception {
        clipboard.setContents(new StringSelection("only text"), null);
        start(false, true);
        bridge.onRequest(IMAGE);
        waitFor(() -> sink.data.size() == 1);
        assertFalse(sink.data.get(0).ok());
    }

    @Test
    void withLocalToRemoteOffNothingIsAnnouncedAndRequestsAreRefused() throws Exception {
        clipboard.setContents(new StringSelection("private"), null);
        start(true, false);
        clipboard.setContents(new StringSelection("still private"), null);
        bridge.onRequest(TEXT);
        waitFor(() -> sink.data.size() == 1);
        assertFalse(sink.data.get(0).ok());
        quiesce();
        assertTrue(sink.offers.isEmpty());
    }

    // ------------------------------------------------------------------------ remote -> local

    @Test
    void aRemoteOfferIsFetchedAndLandsOnTheClipboard() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1); // the (empty) initial announcement
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.equals(List.of(TEXT)));

        bridge.onRemoteData(TEXT, true, textWire("from the server\nsecond line"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        assertEquals("from the server\nsecond line", clipboard.getData(DataFlavor.stringFlavor));
    }

    @Test
    void whatTheServerSentIsNotAnnouncedBackToIt() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1);
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, true, textWire("echo?"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        quiesce();
        assertEquals(1, sink.offers.size(), "the clipboard event caused by our own write is not an offer");

        clipboard.setContents(new ImageSelection(), null); // a genuine local copy
        waitFor(() -> sink.offers.size() == 2);
        assertEquals(IMAGE, sink.offers.get(1));
    }

    @Test
    void refreshReannouncesAClipboardWhoseKindDidNotChange() throws Exception {
        clipboard.setContents(new StringSelection("one"), null);
        start(false, true);
        waitFor(() -> sink.offers.size() == 1);

        // The JDK sends no event for text replaced by text; refresh() is what tells the server.
        clipboard.setContents(new StringSelection("two"), null);
        quiesce();
        assertEquals(1, sink.offers.size());
        bridge.refresh();
        waitFor(() -> sink.offers.size() == 2);
        assertEquals(TEXT, sink.offers.get(1));
    }

    @Test
    void refreshDoesNotEchoWhatTheServerSentOrAnnounceNothingTwice() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1); // empty
        bridge.refresh();
        quiesce();
        assertEquals(1, sink.offers.size(), "an unchanged empty clipboard is not announced again");

        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, true, textWire("from the server"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        bridge.refresh();
        quiesce();
        assertEquals(1, sink.offers.size(), "the server's own content is not offered back");
    }

    @Test
    void refocusingDoesNotTakeTheClipboardBackFromAServerCopyItCouldNotFetch() throws Exception {
        // Files copied in the remote Explorer: the server claims the clipboard with formats this
        // bridge does not fetch. Re-announcing the unchanged local text would replace the files.
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, true);
        waitFor(() -> sink.offers.equals(List.of(TEXT)));
        bridge.onRemoteOffer(0);
        bridge.refresh();
        quiesce();
        assertEquals(List.of(TEXT), sink.offers);
        assertTrue(sink.fetches.isEmpty());
    }

    @Test
    void aNewLocalCopyOfTheSameKindAfterTheServerTookTheClipboardIsStillAnnounced() throws Exception {
        clipboard.setContents(new StringSelection("one"), null);
        start(true, true);
        waitFor(() -> sink.offers.equals(List.of(TEXT)));
        bridge.onRemoteOffer(0);
        quiesce();

        clipboard.setContents(new StringSelection("two"), null); // no event: same kind
        bridge.refresh();
        quiesce();
        assertEquals(List.of(TEXT, TEXT), sink.offers);

        // Ours again: a refresh announces a change, not the same content a second time.
        bridge.refresh();
        quiesce();
        assertEquals(2, sink.offers.size());
        clipboard.setContents(new StringSelection("three"), null);
        bridge.refresh();
        quiesce();
        assertEquals(3, sink.offers.size());
    }

    @Test
    void focusComingBackDoesNotReplaceWhatTheServerAlreadyHas() throws Exception {
        clipboard.setContents(new StringSelection("same"), null);
        start(true, true);
        waitFor(() -> sink.offers.size() == 1);
        for (int i = 0; i < 3; i++) { // e.g. a jterm dialog closing over the pane
            bridge.refresh();
        }
        quiesce();
        assertEquals(1, sink.offers.size(), "an announcement replaces the server's clipboard");
    }

    @Test
    void aClipboardManagerReowningTheServersTextDoesNotBounceItBack() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1);
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, true, textWire("rich text on the server"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        quiesce();

        // Same text, new owner (identity no longer matches what the bridge wrote).
        clipboard.setContents(new StringSelection("rich text on the server"), null);
        bridge.refresh();
        quiesce();
        assertEquals(1, sink.offers.size(), "announcing it would replace the server's formatted copy");
    }

    @Test
    void aLocalClearWhileTheServerOwnsTheClipboardDoesNotEmptyIt() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, true);
        waitFor(() -> sink.offers.equals(List.of(TEXT)));
        bridge.onRemoteOffer(0);
        quiesce();
        clipboard.setContents(new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[0];
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor f) {
                return false;
            }

            @Override
            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                throw new UnsupportedFlavorException(f);
            }
        }, null);
        bridge.refresh();
        quiesce();
        assertEquals(List.of(TEXT), sink.offers);
    }

    @Test
    void aSameSizedImageWithOtherPixelsCountsAsANewCopy() {
        BufferedImage other = image();
        other.setRGB(2, 2, 0xFF0000);
        Transferable a = new ImageSelection();
        Transferable b = new ImageSelection() {
            @Override
            public Object getTransferData(DataFlavor f) {
                return other;
            }
        };
        assertEquals(RdpClipboardBridge.fingerprint(a), RdpClipboardBridge.fingerprint(new ImageSelection()));
        assertFalse(RdpClipboardBridge.fingerprint(a).equals(RdpClipboardBridge.fingerprint(b)));
    }

    @Test
    void refreshIsInertWithoutLocalToRemote() throws Exception {
        clipboard.setContents(new StringSelection("x"), null);
        start(true, false);
        bridge.refresh();
        quiesce();
        assertTrue(sink.offers.isEmpty());
    }

    // ------------------------------------------------------------------------ on connect

    @Test
    void whatTheServerHeldBeforeTheSessionWasUsedDoesNotOverwriteTheLocalClipboard() throws Exception {
        clipboard.setContents(new StringSelection("copied before connecting"), null);
        startUnused(true, true, 150);
        waitFor(() -> sink.offers.equals(List.of(TEXT)));

        bridge.onRemoteOffer(TEXT); // the server's start-up announcement
        quiesce();
        assertTrue(sink.fetches.isEmpty(), "not fetched");
        assertEquals("copied before connecting", clipboard.getData(DataFlavor.stringFlavor));
        assertEquals(List.of(TEXT, TEXT), sink.offers, "ours is announced again, so it wins remotely too");

        // The user starts using the session; their copy there comes over.
        bridge.onUserInput();
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.equals(List.of(TEXT)));
        bridge.onRemoteData(TEXT, true, textWire("copied in the session"));
        waitFor(() -> "copied in the session".equals(stringOnClipboard()));
    }

    @Test
    void anEmptyLocalClipboardStillWinsAtConnect() throws Exception {
        startUnused(true, true, 150);
        waitFor(() -> sink.offers.equals(List.of(0)));
        bridge.onRemoteOffer(TEXT);
        quiesce();
        assertTrue(sink.fetches.isEmpty());
        assertEquals(List.of(0, 0), sink.offers, "the server's old content is not left pasteable as ours");
    }

    @Test
    void oneWayRemoteToLocalStillWaitsForTheFirstInput() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        startUnused(true, false, 150);
        bridge.onRemoteOffer(TEXT);
        quiesce();
        assertTrue(sink.fetches.isEmpty());
        assertTrue(sink.offers.isEmpty(), "nothing is announced when local-to-remote is off");
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));
    }

    private String stringOnClipboard() {
        try {
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void textIsPreferredOverAnImageAndAnImageAloneIsFetched() throws Exception {
        start(true, false);
        bridge.onRemoteOffer(TEXT | IMAGE);
        waitFor(() -> sink.fetches.equals(List.of(TEXT)));
        bridge.onRemoteData(TEXT, false, new byte[0]); // finish that one

        bridge.onRemoteOffer(IMAGE);
        waitFor(() -> sink.fetches.equals(List.of(TEXT, IMAGE)));
        bridge.onRemoteData(IMAGE, true, RdpClipboardCodec.encodeImage(image()));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor));
        BufferedImage got = (BufferedImage) clipboard.getData(DataFlavor.imageFlavor);
        assertEquals(0x336699, got.getRGB(1, 1) & 0xFFFFFF);
    }

    @Test
    void anOfferDuringAFetchIsRememberedAndFetchedAfterwards() throws Exception {
        start(true, false, 60_000);
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteOffer(TEXT); // the server copied again meanwhile
        quiesce();
        assertEquals(1, sink.fetches.size(), "one fetch at a time");

        bridge.onRemoteData(TEXT, true, textWire("first"));
        waitFor(() -> sink.fetches.size() == 2);
    }

    @Test
    void anUnsolicitedOrFailedAnswerChangesNothing() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, false);
        bridge.onRemoteData(TEXT, true, textWire("nobody asked"));
        quiesce();
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));

        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, false, new byte[0]);
        quiesce();
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));
    }

    @Test
    void aMalformedPayloadIsDroppedWithoutDisturbingTheClipboard() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, false);
        bridge.onRemoteOffer(IMAGE);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(IMAGE, true, new byte[]{1, 2, 3, 4});
        quiesce();
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));
    }

    @Test
    void aServerThatNeverAnswersIsGivenUpOn() throws Exception {
        start(true, false);
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        // No answer. After the (150 ms, in this test) timeout a new offer may be fetched.
        Thread.sleep(400);
        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 2);
    }

    @Test
    void withRemoteToLocalOffTheServerCannotTouchTheClipboard() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(false, true);
        bridge.onRemoteOffer(TEXT);
        bridge.onRemoteData(TEXT, true, textWire("overwrite"));
        quiesce();
        assertTrue(sink.fetches.isEmpty());
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));
    }

    @Test
    void closingStopsEverything() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1);
        bridge.close();
        clipboard.setContents(new StringSelection("after close"), null);
        bridge.onRemoteOffer(TEXT);
        bridge.onRequest(TEXT);
        quiesce();
        assertEquals(1, sink.offers.size());
        assertTrue(sink.fetches.isEmpty());
        assertTrue(sink.data.isEmpty());
    }

    // ------------------------------------------------------------------------------------ HTML

    /** What a browser leaves on the local clipboard: plain text plus the HTML document. */
    private static final class HtmlSelection implements Transferable {
        private final String text;
        private final String html;

        HtmlSelection(String text, String html) {
            this.text = text;
            this.html = html;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.stringFlavor, DataFlavor.allHtmlFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return DataFlavor.stringFlavor.equals(f) || DataFlavor.allHtmlFlavor.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (DataFlavor.stringFlavor.equals(f)) {
                return text;
            }
            if (DataFlavor.allHtmlFlavor.equals(f)) {
                return html;
            }
            throw new UnsupportedFlavorException(f);
        }
    }

    private static byte[] htmlWire(String fragment) {
        return RdpClipboardCodec.encodeHtml(fragment);
    }

    @Test
    void localHtmlIsAnnouncedWithTheTextAndSentAsCfHtml() throws Exception {
        clipboard.setContents(new HtmlSelection("bold", "<html><body><b>bold</b></body></html>"), null);
        start(false, true);
        waitFor(() -> sink.offers.equals(List.of(TEXT | HTML)));

        bridge.onRequest(HTML);
        waitFor(() -> sink.data.size() == 1);
        assertTrue(sink.data.get(0).ok());
        RdpClipboardCodec.Html sent = RdpClipboardCodec.decodeHtml(sink.data.get(0).bytes());
        assertNotNull(sent);
        assertEquals("<b>bold</b>", sent.fragment(), "the body's content is the fragment");

        bridge.onRequest(TEXT);
        waitFor(() -> sink.data.size() == 2);
        assertEquals("bold", RdpClipboardCodec.decodeText(sink.data.get(1).bytes()));
    }

    @Test
    void aRemoteCopyWithHtmlLandsAsTextAndHtmlTogether() throws Exception {
        clipboard.setContents(new StringSelection("before"), null);
        start(true, false);
        bridge.onRemoteOffer(TEXT | HTML | IMAGE);
        waitFor(() -> sink.fetches.equals(List.of(TEXT)));
        bridge.onRemoteData(TEXT, true, textWire("cell"));
        waitFor(() -> sink.fetches.equals(List.of(TEXT, HTML)));
        quiesce();
        assertEquals("before", clipboard.getData(DataFlavor.stringFlavor), "nothing lands until every part is in");

        bridge.onRemoteData(HTML, true, htmlWire("<td>cell</td>"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.allHtmlFlavor));
        assertEquals("cell", clipboard.getData(DataFlavor.stringFlavor));
        assertEquals("<td>cell</td>", clipboard.getData(DataFlavor.fragmentHtmlFlavor));
        assertEquals("<td>cell</td>", clipboard.getData(DataFlavor.selectionHtmlFlavor));
        assertTrue(((String) clipboard.getData(DataFlavor.allHtmlFlavor)).contains("<!--StartFragment--><td>cell</td>"));
        quiesce();
        assertEquals(List.of(TEXT, HTML), sink.fetches, "the image is not fetched alongside text");
    }

    @Test
    void anHtmlPartThatNeverArrivesStillLeavesTheText() throws Exception {
        start(true, false);
        bridge.onRemoteOffer(TEXT | HTML);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, true, textWire("plain"));
        waitFor(() -> sink.fetches.size() == 2);
        // No HTML answer: after the (150 ms) timeout the text is applied on its own.
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        assertEquals("plain", clipboard.getData(DataFlavor.stringFlavor));
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.allHtmlFlavor));
    }

    @Test
    void aFailedTextPartStillLetsTheHtmlThrough() throws Exception {
        start(true, false);
        bridge.onRemoteOffer(TEXT | HTML);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, false, null);
        waitFor(() -> sink.fetches.size() == 2);
        bridge.onRemoteData(HTML, true, htmlWire("<p>only html</p>"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.allHtmlFlavor));
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
    }

    @Test
    void htmlAloneIsFetchedAndNotEchoedBack() throws Exception {
        start(true, true);
        waitFor(() -> sink.offers.size() == 1);
        bridge.onRemoteOffer(HTML);
        waitFor(() -> sink.fetches.equals(List.of(HTML)));
        bridge.onRemoteData(HTML, true, htmlWire("<p>x</p>"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.fragmentHtmlFlavor));
        assertEquals("<p>x</p>", clipboard.getData(DataFlavor.fragmentHtmlFlavor));
        quiesce();
        assertEquals(1, sink.offers.size(), "our own write is not announced back");
    }

    @Test
    void anOfferDuringAMultiPartFetchWaitsForThePlanToFinish() throws Exception {
        start(true, false);
        bridge.onRemoteOffer(TEXT | HTML);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteOffer(IMAGE);
        bridge.onRemoteData(TEXT, true, textWire("t"));
        waitFor(() -> sink.fetches.equals(List.of(TEXT, HTML)));
        bridge.onRemoteData(HTML, true, htmlWire("<b>t</b>"));
        waitFor(() -> sink.fetches.equals(List.of(TEXT, HTML, IMAGE)));
    }

    @Test
    void anHtmlOnlyLocalClipboardCountsAsANewCopyWhenItChanges() {
        Transferable a = new HtmlOnly("<p>a</p>");
        Transferable b = new HtmlOnly("<p>b</p>");
        assertNotNull(RdpClipboardBridge.fingerprint(a));
        assertFalse(RdpClipboardBridge.fingerprint(a).equals(RdpClipboardBridge.fingerprint(b)));
        assertFalse("empty".equals(RdpClipboardBridge.fingerprint(a)));
    }

    /** An HTML flavor the JDK did not predefine: found by MIME type, not by constant. */
    private record HtmlOnly(String html) implements Transferable {
        private static final DataFlavor FLAVOR = new DataFlavor("text/html;class=java.lang.String", "html");

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor f) {
            return FLAVOR.equals(f);
        }

        @Override
        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) {
                throw new UnsupportedFlavorException(f);
            }
            return html;
        }
    }

    // ------------------------------------------------------------------- files, local -> remote

    private static final int FILES = RdpProtocol.CLIPKIND_FILES;

    private RdpClipboardBridge startFiles(boolean filesToRemote) {
        bridge = new RdpClipboardBridge(sink, clipboard, true, true, filesToRemote, consent, List.of(), null, 150);
        bridge.start();
        bridge.onUserInput();
        return bridge;
    }

    private Path copyFile(String name, String content) throws Exception {
        Path f = Files.writeString(tmp.resolve(name), content);
        clipboard.setContents(new FileList(List.of(f.toFile())), null);
        return f;
    }

    private static RdpProtocol.FileRequest range(int stream, Integer clipDataId) {
        return new RdpProtocol.FileRequest(stream, 0, RdpProtocol.FILECONTENTS_RANGE, 0, 100,
                clipDataId != null, clipDataId != null ? clipDataId : 0);
    }

    @Test
    void filesAreOfferedOnlyWhenTheSessionAllowsThem() throws Exception {
        copyFile("a.txt", "a");
        startFiles(false);
        waitFor(() -> !sink.offers.isEmpty());
        assertEquals(0, sink.offers.get(0) & FILES);
        bridge.close();

        sink.offers.clear();
        startFiles(true);
        waitFor(() -> !sink.offers.isEmpty());
        assertEquals(FILES, sink.offers.get(0) & FILES);
    }

    @Test
    void aPasteAsksOnceAndSendsOnlyTheListOnYes() throws Exception {
        copyFile("report.pdf", "pdf!");
        startFiles(true);
        waitFor(() -> !sink.offers.isEmpty());

        bridge.onRequest(FILES);
        waitFor(() -> consent.asked.size() == 1);
        quiesce();
        assertTrue(sink.data.isEmpty(), "nothing is described before the answer");
        FileSnapshot.Summary summary = consent.asked.get(0);
        assertEquals(1, summary.files());
        assertEquals(4, summary.bytes());
        assertEquals(List.of("report.pdf"), summary.topNames());

        consent.answers.get(0).accept(true);
        waitFor(() -> sink.data.size() == 1);
        Data d = sink.data.get(0);
        assertEquals(FILES, d.kind());
        assertTrue(d.ok());
        assertEquals(1, ByteBuffer.wrap(d.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt());

        bridge.onRequest(FILES); // pasted again: the answer stands for this copy
        waitFor(() -> sink.data.size() == 2);
        assertTrue(sink.data.get(1).ok());
        assertEquals(1, consent.asked.size());

        bridge.onFileRequest(range(3, null));
        waitFor(() -> sink.fileData.size() == 1);
        assertEquals(new String(sink.fileData.get(0).bytes(), StandardCharsets.UTF_8), "pdf!");
        assertEquals(3, sink.fileData.get(0).streamId());
    }

    @Test
    void aRefusalHoldsForTheCopyAndNothingCanBeRead() throws Exception {
        copyFile("secret.txt", "s");
        startFiles(true);
        waitFor(() -> !sink.offers.isEmpty());

        bridge.onFileRequest(range(1, null));
        waitFor(() -> sink.fileData.size() == 1);
        assertFalse(sink.fileData.get(0).ok(), "no reading before a paste was allowed");

        bridge.onRequest(FILES);
        waitFor(() -> consent.asked.size() == 1);
        consent.answers.get(0).accept(false);
        waitFor(() -> sink.data.size() == 1);
        assertFalse(sink.data.get(0).ok());

        bridge.onRequest(FILES);
        waitFor(() -> sink.data.size() == 2);
        assertFalse(sink.data.get(1).ok());
        assertEquals(1, consent.asked.size(), "a server can't ask again and again");

        bridge.onFileRequest(range(2, null));
        waitFor(() -> sink.fileData.size() == 2);
        assertFalse(sink.fileData.get(1).ok());

        bridge.refresh(); // focus comes back (the prompt closing does that): still the same copy
        quiesce();
        bridge.onRequest(FILES);
        waitFor(() -> sink.data.size() == 3);
        assertFalse(sink.data.get(2).ok());
        assertEquals(1, consent.asked.size());

        copyFile("other.txt", "o"); // a new copy is a new question
        bridge.refresh();
        quiesce();
        bridge.onRequest(FILES);
        waitFor(() -> consent.asked.size() == 2);
    }

    @Test
    void anAllowedCopyIsReadAsItIsAtEachPaste() throws Exception {
        Path f = copyFile("draft.txt", "first");
        startFiles(true);
        waitFor(() -> !sink.offers.isEmpty());
        bridge.onRequest(FILES);
        waitFor(() -> consent.asked.size() == 1);
        consent.answers.get(0).accept(true);
        waitFor(() -> sink.data.size() == 1);

        Files.writeString(f, "second, longer"); // edited and copied again under the same path
        bridge.refresh();
        bridge.onRequest(FILES); // the next paste asks for the list again
        waitFor(() -> sink.data.size() == 2);
        assertTrue(sink.data.get(1).ok());
        assertEquals(1, consent.asked.size(), "the same files: the answer stands");
        bridge.onFileRequest(range(1, null));
        waitFor(() -> sink.fileData.size() == 1);
        assertEquals("second, longer", new String(sink.fileData.get(0).bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aLockedPasteKeepsReadingTheFilesItStartedWith() throws Exception {
        copyFile("first.txt", "first");
        startFiles(true);
        waitFor(() -> !sink.offers.isEmpty());
        bridge.onLock(42);
        bridge.onRequest(FILES);
        waitFor(() -> consent.asked.size() == 1);
        consent.answers.get(0).accept(true);
        waitFor(() -> sink.data.size() == 1);

        copyFile("second.txt", "second"); // the user copies something else meanwhile...
        bridge.refresh(); // ...and clicks back into the pane (same flavors: no clipboard event)
        waitFor(() -> sink.offers.size() == 2);

        bridge.onFileRequest(range(1, 42));
        waitFor(() -> sink.fileData.size() == 1);
        assertEquals("first", new String(sink.fileData.get(0).bytes(), StandardCharsets.UTF_8));

        bridge.onFileRequest(range(2, null));
        waitFor(() -> sink.fileData.size() == 2);
        assertFalse(sink.fileData.get(1).ok(), "the new copy hasn't been allowed");

        bridge.onUnlock(42);
        bridge.onFileRequest(range(3, 42));
        waitFor(() -> sink.fileData.size() == 3);
        assertFalse(sink.fileData.get(2).ok());
    }

    @Test
    void aCopyThatCannotBeSentIsExplainedOnce() throws Exception {
        Path ssh = Files.createDirectories(tmp.resolve(".ssh"));
        clipboard.setContents(new FileList(List.of(ssh.toFile())), null);
        bridge = new RdpClipboardBridge(sink, clipboard, true, true, true, consent, List.of(ssh), null, 150);
        bridge.start();
        bridge.onUserInput();
        waitFor(() -> !sink.offers.isEmpty());

        bridge.onRequest(FILES);
        waitFor(() -> sink.data.size() == 1);
        assertFalse(sink.data.get(0).ok());
        assertEquals(1, consent.unavailable.size());
        bridge.onRequest(FILES);
        waitFor(() -> sink.data.size() == 2);
        assertEquals(1, consent.unavailable.size());
        assertTrue(consent.asked.isEmpty());
    }

    @Test
    void withFilesOffEveryFileRequestIsRefusedUnasked() throws Exception {
        copyFile("a.txt", "a");
        startFiles(false);
        waitFor(() -> !sink.offers.isEmpty());
        bridge.onRequest(FILES);
        bridge.onFileRequest(range(1, null));
        bridge.onLock(1);
        waitFor(() -> sink.data.size() == 1 && sink.fileData.size() == 1);
        assertFalse(sink.data.get(0).ok());
        assertFalse(sink.fileData.get(0).ok());
        assertTrue(consent.asked.isEmpty());
    }

    @Test
    void aFileListIsFingerprintedByItsPaths() throws Exception {
        Path a = Files.writeString(tmp.resolve("a"), "x");
        Path b = Files.writeString(tmp.resolve("b"), "x");
        String fa = RdpClipboardBridge.fingerprint(new FileList(List.of(a.toFile())));
        assertNotNull(fa);
        assertEquals(fa, RdpClipboardBridge.fingerprint(new FileList(List.of(a.toFile()))));
        assertFalse(fa.equals(RdpClipboardBridge.fingerprint(new FileList(List.of(b.toFile())))));
        assertFalse("empty".equals(fa));
    }

    // ------------------------------------------------------------------- files, remote -> local

    private final List<RemoteClipboardFiles.Offer> remoteOffers = new CopyOnWriteArrayList<>();

    private RdpClipboardBridge startRemoteFiles(boolean filesToLocal) {
        bridge = new RdpClipboardBridge(sink, clipboard, true, false, false, null, List.of(),
                filesToLocal ? remoteOffers::add : null, 150);
        bridge.start();
        bridge.onUserInput();
        return bridge;
    }

    private static byte[] remoteList(String... names) throws Exception {
        java.nio.file.Path d = Files.createTempDirectory("rl");
        List<java.nio.file.Path> paths = new java.util.ArrayList<>();
        for (String n : names) {
            paths.add(Files.writeString(d.resolve(n), n));
        }
        return FileSnapshot.build(paths, List.of()).descriptors();
    }

    @Test
    void aServerCopyOfFilesIsLockedListedAndOfferedButNothingIsWritten() throws Exception {
        clipboard.setContents(new StringSelection("local"), null);
        startRemoteFiles(true);
        bridge.onClipCaps(new RdpProtocol.ClipCaps(RdpProtocol.CB_CAN_LOCK_CLIPDATA));

        bridge.onRemoteOffer(FILES);
        waitFor(() -> sink.fetches.equals(List.of(FILES)));
        assertEquals(List.of("lock 1"), sink.locks, "locked before the list was even asked for");
        bridge.onRemoteData(FILES, true, remoteList("a.txt", "b.txt"));
        waitFor(() -> remoteOffers.stream().anyMatch(o -> o != null));

        RemoteClipboardFiles.Offer offer = remoteOffers.stream().filter(o -> o != null).findFirst().orElseThrow();
        assertEquals(2, offer.list().files());
        assertEquals(1, offer.lockId());
        assertEquals("local", clipboard.getData(DataFlavor.stringFlavor), "the local clipboard is untouched");
        assertTrue(sink.fileFetches.isEmpty(), "no file is read until the user saves");

        remoteOffers.clear();
        bridge.onRemoteOffer(TEXT); // the user copies text on the remote desktop
        waitFor(() -> !remoteOffers.isEmpty());
        assertEquals(null, remoteOffers.get(0), "the button goes stale");
        waitFor(() -> sink.locks.contains("unlock 1"));
    }

    @Test
    void aGarbledListOffersNothing() throws Exception {
        startRemoteFiles(true);
        bridge.onRemoteOffer(FILES);
        waitFor(() -> sink.fetches.equals(List.of(FILES)));
        bridge.onRemoteData(FILES, true, new byte[]{1, 0, 0, 0, 9});
        quiesce();
        assertTrue(remoteOffers.stream().allMatch(o -> o == null));
    }

    @Test
    void withFilesToLocalOffTheListIsNeverFetched() throws Exception {
        startRemoteFiles(false);
        bridge.onRemoteOffer(FILES);
        quiesce();
        assertTrue(sink.fetches.isEmpty());
        assertTrue(sink.locks.isEmpty());
        assertEquals(null, bridge.remoteFiles());
    }

    @Test
    void theServersStartupClipboardOfFilesIsNotListed() throws Exception {
        bridge = new RdpClipboardBridge(sink, clipboard, true, false, false, null, List.of(), remoteOffers::add, 150);
        bridge.start(); // no input yet: what the server held before is not a copy the user made
        bridge.onClipCaps(new RdpProtocol.ClipCaps(RdpProtocol.CB_CAN_LOCK_CLIPDATA));
        bridge.onRemoteOffer(FILES);
        quiesce();
        assertTrue(sink.fetches.isEmpty());
        assertTrue(sink.locks.isEmpty());
    }
}
