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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bridge against a private {@link Clipboard} (so no display is needed) and a recording sink.
 * The bridge works on its own thread, so each assertion about something that must happen waits for
 * it, and each about something that must <em>not</em> happen waits a beat first.
 */
class RdpClipboardBridgeTest {

    private static final int TEXT = RdpProtocol.CLIPKIND_TEXT;
    private static final int IMAGE = RdpProtocol.CLIPKIND_IMAGE;

    private record Data(int kind, boolean ok, byte[] bytes) {
    }

    private static final class Recorder implements RdpClipboardBridge.Sink {
        final List<Integer> offers = new CopyOnWriteArrayList<>();
        final List<Integer> fetches = new CopyOnWriteArrayList<>();
        final List<Data> data = new CopyOnWriteArrayList<>();

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
    }

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

    private RdpClipboardBridge start(boolean remoteToLocal, boolean localToRemote, long fetchTimeoutMillis) {
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

    private static void settle() throws InterruptedException {
        Thread.sleep(250);
    }

    private static byte[] textWire(String s) {
        return RdpClipboardCodec.encodeText(s);
    }

    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0x336699);
        return img;
    }

    private static final class ImageSelection implements Transferable {
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
        settle();
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
        settle();
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
        settle();
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
        settle();
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
        settle();
        assertEquals(1, sink.offers.size(), "an unchanged empty clipboard is not announced again");

        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, true, textWire("from the server"));
        waitFor(() -> clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor));
        bridge.refresh();
        settle();
        assertEquals(1, sink.offers.size(), "the server's own content is not offered back");
    }

    @Test
    void refreshIsInertWithoutLocalToRemote() throws Exception {
        clipboard.setContents(new StringSelection("x"), null);
        start(true, false);
        bridge.refresh();
        settle();
        assertTrue(sink.offers.isEmpty());
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
        settle();
        assertEquals(1, sink.fetches.size(), "one fetch at a time");

        bridge.onRemoteData(TEXT, true, textWire("first"));
        waitFor(() -> sink.fetches.size() == 2);
    }

    @Test
    void anUnsolicitedOrFailedAnswerChangesNothing() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, false);
        bridge.onRemoteData(TEXT, true, textWire("nobody asked"));
        settle();
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));

        bridge.onRemoteOffer(TEXT);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(TEXT, false, new byte[0]);
        settle();
        assertEquals("mine", clipboard.getData(DataFlavor.stringFlavor));
    }

    @Test
    void aMalformedPayloadIsDroppedWithoutDisturbingTheClipboard() throws Exception {
        clipboard.setContents(new StringSelection("mine"), null);
        start(true, false);
        bridge.onRemoteOffer(IMAGE);
        waitFor(() -> sink.fetches.size() == 1);
        bridge.onRemoteData(IMAGE, true, new byte[]{1, 2, 3, 4});
        settle();
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
        settle();
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
        settle();
        assertEquals(1, sink.offers.size());
        assertTrue(sink.fetches.isEmpty());
        assertTrue(sink.data.isEmpty());
    }
}
