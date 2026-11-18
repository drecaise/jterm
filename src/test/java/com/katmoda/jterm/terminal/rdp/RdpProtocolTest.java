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
package com.katmoda.jterm.terminal.rdp;

import org.junit.jupiter.api.Test;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level checks of the jterm side of the helper protocol against the layout in
 * {@code native/rdp-helper/src/protocol.h}.
 */
class RdpProtocolTest {

    /** Splits a framed message into its type and a little-endian body. */
    private static ByteBuffer body(byte[] message, int expectedType) {
        ByteBuffer b = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(message.length - 4, b.getInt(), "length field covers type + payload");
        assertEquals(expectedType, b.get() & 0xFF);
        return b;
    }

    private static String str(ByteBuffer b) {
        byte[] s = new byte[b.getShort() & 0xFFFF];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    @Test
    void helloCarriesTheProtocolVersion() {
        ByteBuffer b = body(RdpProtocol.hello(), RdpProtocol.HELLO);
        assertEquals(RdpProtocol.VERSION, b.getShort());
        assertFalse(b.hasRemaining());
    }

    @Test
    void connectLaysOutEveryFieldInOrder() {
        byte[] msg = RdpProtocol.connect("orion.lan", 3390, "mark", "", "pä55".toCharArray(),
                "sha256:aa:bb", 1920, 1200, 150, RdpProtocol.clipboardFlags(true, true),
                RdpProtocol.SEC_ALLOW_TLS, "desktop.lan", RdpProtocol.Gateway.NONE);
        ByteBuffer b = body(msg, RdpProtocol.CONNECT);
        assertEquals("orion.lan", str(b));
        assertEquals(3390, b.getShort() & 0xFFFF);
        assertEquals("mark", str(b));
        assertEquals("", str(b));
        assertEquals("pä55", str(b), "UTF-8, not platform charset");
        assertEquals("sha256:aa:bb", str(b));
        assertEquals(1920, b.getShort() & 0xFFFF);
        assertEquals(1200, b.getShort() & 0xFFFF);
        assertEquals(150, b.getShort() & 0xFFFF);
        assertEquals(RdpProtocol.CLIP_REMOTE_TO_LOCAL | RdpProtocol.CLIP_LOCAL_TO_REMOTE, b.get());
        assertEquals(RdpProtocol.SEC_ALLOW_TLS, b.get());
        assertEquals("desktop.lan", str(b), "the certificate name when dialling an SSH forward");
        assertEquals(0, b.get(), "no gateway");
        assertEquals("", str(b));
        assertEquals(0, b.getShort());
        assertEquals("", str(b));
        assertEquals("", str(b));
        assertEquals("", str(b));
        assertEquals(0, b.get(), "no shared folders");
        assertEquals(0, b.get(), "no audio (old overload)");
        assertEquals(0, b.getShort(), "no latency (old overload)");
        assertNoPrinter(b);
        assertFalse(b.hasRemaining());
    }

    /** CONNECT's printer part (protocol v13) when no printer is announced, plus the reserved smart-card byte. */
    private static void assertNoPrinter(ByteBuffer b) {
        assertEquals(0, b.get(), "printerFlags");
        assertEquals("", str(b), "printer name");
        assertEquals("", str(b), "printer driver");
        assertEquals(0, b.get(), "smartcardFlags");
    }

    @Test
    void connectEndsWithThePrinterAfterTheAudioPart() {
        byte[] msg = RdpProtocol.connect("h", 3389, "u", "", "pw".toCharArray(), "", 800, 600, 100, 0, 0, "",
                RdpProtocol.Gateway.NONE, java.util.List.of(), 0, 100,
                new RdpProtocol.Printer(RdpProtocol.PRINTER_ENABLED | RdpProtocol.PRINTER_DEFAULT, "jterm",
                        "Microsoft Print To PDF"));
        ByteBuffer b = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN);
        int tail = 1 + 2 + 5 + 2 + 22 + 1;
        b.position(msg.length - tail);
        assertEquals(RdpProtocol.PRINTER_ENABLED | RdpProtocol.PRINTER_DEFAULT, b.get());
        assertEquals("jterm", str(b));
        assertEquals("Microsoft Print To PDF", str(b));
        assertEquals(0, b.get(), "smartcardFlags");
        assertFalse(b.hasRemaining());
    }

    @Test
    void connectEndsWithTheSmartCardFlags() {
        byte[] msg = RdpProtocol.connect("h", 3389, "u", "", "pw".toCharArray(), "", 800, 600, 100, 0, 0, "",
                RdpProtocol.Gateway.NONE, java.util.List.of(), 0, 100, RdpProtocol.Printer.NONE,
                RdpProtocol.SMARTCARD_ENABLED);
        assertEquals(RdpProtocol.SMARTCARD_ENABLED, msg[msg.length - 1]);
        assertThrows(IllegalArgumentException.class, () -> RdpProtocol.connect("h", 3389, "u", "",
                "pw".toCharArray(), "", 800, 600, 100, 0, 0, "", RdpProtocol.Gateway.NONE, java.util.List.of(),
                0, 100, RdpProtocol.Printer.NONE, 0x02), "a bit the helper would refuse");
    }

    @Test
    void aPrinterAnnouncementTheHelperWouldRefuseIsRejectedHere() {
        int on = RdpProtocol.PRINTER_ENABLED;
        assertThrows(IllegalArgumentException.class, () -> new RdpProtocol.Printer(on, "", "Microsoft Print To PDF"));
        assertThrows(IllegalArgumentException.class, () -> new RdpProtocol.Printer(on, "jterm", ""));
        assertThrows(IllegalArgumentException.class, () -> new RdpProtocol.Printer(on, "jterm", "Drïver"));
        assertThrows(IllegalArgumentException.class, () -> new RdpProtocol.Printer(on, "x".repeat(65), "d"));
        // Disabled: nothing is announced, so nothing to check.
        new RdpProtocol.Printer(0, "", "");
    }

    /** Skips CONNECT's fields up to the gateway part (the layout asserted above). */
    private static ByteBuffer gatewayPart(byte[] msg) {
        ByteBuffer b = body(msg, RdpProtocol.CONNECT);
        str(b);
        b.getShort();
        str(b);
        str(b);
        str(b);
        str(b);
        b.getShort();
        b.getShort();
        b.getShort();
        b.get();
        b.get();
        str(b);
        return b;
    }

    @Test
    void aGatewayWithItsOwnCredentialsIsSentInFull() {
        byte[] msg = RdpProtocol.connect("desktop", 3389, "mark", "CORP", "pw".toCharArray(), "", 1280, 800,
                100, 0, 0, "", new RdpProtocol.Gateway("rdg.corp", 8443, false, "gwuser", "GW", "gw-pw".toCharArray()));
        ByteBuffer b = gatewayPart(msg);
        assertEquals(RdpProtocol.GW_ENABLED, b.get());
        assertEquals("rdg.corp", str(b));
        assertEquals(8443, b.getShort() & 0xFFFF);
        assertEquals("gwuser", str(b));
        assertEquals("GW", str(b));
        assertEquals("gw-pw", str(b));
        assertEquals(0, b.get());
        assertEquals(0, b.get(), "audioFlags");
        assertEquals(0, b.getShort(), "audioLatencyMs");
        assertNoPrinter(b);
        assertFalse(b.hasRemaining());
    }

    @Test
    void aGatewayReusingTheDesktopCredentialsCarriesNoneOfItsOwn() {
        byte[] msg = RdpProtocol.connect("desktop", 3389, "mark", "", "pw".toCharArray(), "", 1280, 800,
                100, 0, 0, "", new RdpProtocol.Gateway("rdg.corp", 443, true, "ignored", "ignored", "x".toCharArray()));
        ByteBuffer b = gatewayPart(msg);
        assertEquals(RdpProtocol.GW_ENABLED | RdpProtocol.GW_SAME_CREDENTIALS, b.get());
        assertEquals("rdg.corp", str(b));
        assertEquals(443, b.getShort() & 0xFFFF);
        assertEquals("", str(b), "the helper copies the desktop's user");
        assertEquals("", str(b));
        assertEquals("", str(b), "no second copy of a password on the pipe");
        assertEquals(0, b.get());
        assertEquals(0, b.get(), "audioFlags");
        assertEquals(0, b.getShort(), "audioLatencyMs");
        assertNoPrinter(b);
        assertFalse(b.hasRemaining());
    }

    @Test
    void sharedFoldersFollowTheGatewayAsIdAndNameOnly() {
        byte[] msg = RdpProtocol.connect("desktop", 3389, "mark", "", "pw".toCharArray(), "", 1280, 800,
                100, 0, 0, "", RdpProtocol.Gateway.NONE,
                java.util.List.of(new RdpProtocol.Drive(1, "Documents"), new RdpProtocol.Drive(0x01020304, "src")));
        ByteBuffer b = gatewayPart(msg);
        b.get();
        str(b);
        b.getShort();
        str(b);
        str(b);
        str(b);
        assertEquals(2, b.get());
        assertEquals(1, b.getInt());
        assertEquals("Documents", str(b));
        assertEquals(0x01020304, b.getInt(), "little-endian u32");
        assertEquals("src", str(b));
        assertEquals(0, b.get(), "audioFlags");
        assertEquals(0, b.getShort(), "audioLatencyMs");
        assertNoPrinter(b);
        assertFalse(b.hasRemaining(), "no local path anywhere");
    }

    @Test
    void driveRequestsAndAnswersUseTheDocumentedLayout() {
        ByteBuffer in = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
        in.putInt(7).putInt(42).putInt(99).putInt(0x0C).putInt(1).put(new byte[]{9, 8, 7}).flip();
        RdpProtocol.DriveIrp irp = assertInstanceOf(RdpProtocol.DriveIrp.class,
                RdpProtocol.decode(RdpProtocol.DRIVE_IRP, in));
        assertEquals(7, irp.driveId());
        assertEquals(42, irp.fileId());
        assertEquals(99, irp.completionId());
        assertEquals(0x0C, irp.major());
        assertEquals(1, irp.minor());
        assertArrayEquals(new byte[]{9, 8, 7}, irp.input());

        ByteBuffer done = body(RdpProtocol.driveIrpDone(99, 0xC0000022, new byte[]{1, 2}), RdpProtocol.DRIVE_IRP_DONE);
        assertEquals(99, done.getInt());
        assertEquals(0xC0000022, done.getInt());
        assertEquals(1, done.get());
        assertEquals(2, done.get());
        assertFalse(done.hasRemaining());
        assertThrows(BufferUnderflowException.class,
                () -> RdpProtocol.decode(RdpProtocol.DRIVE_IRP, ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)));
    }

    @Test
    void aGatewayCertificatePromptIsRecognised() {
        assertTrue(new RdpProtocol.CertRequest("rdg", 443, "", "", "", "", RdpProtocol.CERT_FLAG_GATEWAY).gateway());
        assertFalse(new RdpProtocol.CertRequest("desk", 3389, "", "", "", "", RdpProtocol.CERT_FLAG_MISMATCH).gateway());
    }

    @Test
    void clipboardFlagsAreIndependentAndZeroKeepsTheChannelClosed() {
        assertEquals(0, RdpProtocol.clipboardFlags(false, false));
        assertEquals(RdpProtocol.CLIP_REMOTE_TO_LOCAL, RdpProtocol.clipboardFlags(true, false));
        assertEquals(RdpProtocol.CLIP_LOCAL_TO_REMOTE, RdpProtocol.clipboardFlags(false, true));
    }

    @Test
    void filesToTheRemoteCountOnlyWithLocalToRemote() {
        assertEquals(RdpProtocol.CLIP_LOCAL_TO_REMOTE | RdpProtocol.CLIP_FILES_TO_REMOTE,
                RdpProtocol.clipboardFlags(false, true, true));
        assertEquals(RdpProtocol.CLIP_REMOTE_TO_LOCAL, RdpProtocol.clipboardFlags(true, false, true));
        assertEquals(RdpProtocol.CLIP_LOCAL_TO_REMOTE, RdpProtocol.clipboardFlags(false, true, false));
    }

    @Test
    void remoteFileMessagesUseTheDocumentedLayout() {
        assertEquals(RdpProtocol.CLIP_REMOTE_TO_LOCAL | RdpProtocol.CLIP_FILES_TO_LOCAL,
                RdpProtocol.clipboardFlags(true, false, false, true));
        assertEquals(RdpProtocol.CLIP_LOCAL_TO_REMOTE, RdpProtocol.clipboardFlags(false, true, false, true),
                "files to this computer need the remote-to-local direction");

        ByteBuffer f = body(RdpProtocol.fileFetch(4, 2, RdpProtocol.FILECONTENTS_RANGE, 0x1_0000_0005L,
                1 << 20, true, 9), RdpProtocol.FILE_FETCH);
        assertEquals(4, f.getInt());
        assertEquals(2, f.getInt());
        assertEquals(RdpProtocol.FILECONTENTS_RANGE, f.getInt());
        assertEquals(5, f.getInt());
        assertEquals(1, f.getInt());
        assertEquals(1 << 20, f.getInt());
        assertEquals(1, f.get());
        assertEquals(9, f.getInt());
        assertFalse(f.hasRemaining());

        ByteBuffer l = body(RdpProtocol.clipLockRemote(false, 3), RdpProtocol.CLIP_LOCK_REMOTE);
        assertEquals(0, l.get());
        assertEquals(3, l.getInt());
        assertFalse(l.hasRemaining());

        ByteBuffer in = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(4).put((byte) 1)
                .put(new byte[]{6, 7}).flip();
        RdpProtocol.FileRemoteData d = assertInstanceOf(RdpProtocol.FileRemoteData.class,
                RdpProtocol.decode(RdpProtocol.FILE_REMOTE_DATA, in));
        assertEquals(4, d.streamId());
        assertTrue(d.ok());
        assertArrayEquals(new byte[]{6, 7}, d.data());

        RdpProtocol.ClipCaps caps = assertInstanceOf(RdpProtocol.ClipCaps.class, RdpProtocol.decode(
                RdpProtocol.CLIP_CAPS, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3E).flip()));
        assertTrue(caps.canLock());
        assertFalse(new RdpProtocol.ClipCaps(0x0E).canLock());
    }

    @Test
    void fileRequestsLocksAndAnswersUseTheDocumentedLayout() {
        ByteBuffer in = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN);
        in.putInt(5).putInt(3).putInt(RdpProtocol.FILECONTENTS_RANGE)
                .putInt(0x10).putInt(0x1) // position 0x1_0000_0010: past 4 GiB
                .putInt(0x8000_0000).put((byte) 1).putInt(77).flip();
        RdpProtocol.FileRequest r = assertInstanceOf(RdpProtocol.FileRequest.class,
                RdpProtocol.decode(RdpProtocol.FILE_REQUEST, in));
        assertEquals(5, r.streamId());
        assertEquals(3, r.listIndex());
        assertEquals(RdpProtocol.FILECONTENTS_RANGE, r.flags());
        assertEquals(0x1_0000_0010L, r.position());
        assertEquals(0x8000_0000, r.cbRequested()); // unsigned on the wire; the reader treats it so
        assertTrue(r.haveClipDataId());
        assertEquals(77, r.clipDataId());

        ByteBuffer high = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN);
        high.putInt(1).putInt(0).putInt(RdpProtocol.FILECONTENTS_RANGE).putInt(0).putInt(0x8000_0000)
                .putInt(1).put((byte) 0).putInt(0).flip();
        assertTrue(((RdpProtocol.FileRequest) RdpProtocol.decode(RdpProtocol.FILE_REQUEST, high)).position() < 0,
                "a position past 2^63 reads as negative, which the reader refuses");

        assertEquals(new RdpProtocol.ClipLock(9), RdpProtocol.decode(RdpProtocol.CLIP_LOCK,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(9).flip()));
        assertEquals(new RdpProtocol.ClipUnlock(9), RdpProtocol.decode(RdpProtocol.CLIP_UNLOCK,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(9).flip()));

        ByteBuffer data = body(RdpProtocol.fileData(5, true, new byte[]{4, 2}), RdpProtocol.FILE_DATA);
        assertEquals(5, data.getInt());
        assertEquals(1, data.get());
        assertEquals(4, data.get());
        assertEquals(2, data.get());
        assertFalse(data.hasRemaining());
        ByteBuffer refused = body(RdpProtocol.fileData(6, false, new byte[]{1}), RdpProtocol.FILE_DATA);
        assertEquals(6, refused.getInt());
        assertEquals(0, refused.get());
        assertFalse(refused.hasRemaining(), "a refusal carries no bytes");
        assertThrows(BufferUnderflowException.class,
                () -> RdpProtocol.decode(RdpProtocol.FILE_REQUEST, ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)));
    }

    @Test
    void clipboardMessagesGoOutInTheDocumentedLayout() {
        ByteBuffer offer = body(RdpProtocol.clipOffer(RdpProtocol.CLIPKIND_TEXT | RdpProtocol.CLIPKIND_IMAGE),
                RdpProtocol.CLIP_OFFER);
        assertEquals(3, offer.get());
        assertFalse(offer.hasRemaining());

        ByteBuffer data = body(RdpProtocol.clipData(RdpProtocol.CLIPKIND_TEXT, true, new byte[]{1, 2, 3}),
                RdpProtocol.CLIP_DATA);
        assertEquals(RdpProtocol.CLIPKIND_TEXT, data.get());
        assertEquals(1, data.get());
        byte[] rest = new byte[data.remaining()];
        data.get(rest);
        assertArrayEquals(new byte[]{1, 2, 3}, rest);

        ByteBuffer refused = body(RdpProtocol.clipData(RdpProtocol.CLIPKIND_IMAGE, false, new byte[]{9}),
                RdpProtocol.CLIP_DATA);
        assertEquals(RdpProtocol.CLIPKIND_IMAGE, refused.get());
        assertEquals(0, refused.get());
        assertFalse(refused.hasRemaining(), "a refusal carries no payload, whatever was passed");

        ByteBuffer fetch = body(RdpProtocol.clipFetch(RdpProtocol.CLIPKIND_IMAGE), RdpProtocol.CLIP_FETCH);
        assertEquals(RdpProtocol.CLIPKIND_IMAGE, fetch.get());
    }

    @Test
    void decodesClipboardMessagesAndCopiesTheData() {
        RdpProtocol.ClipRemoteOffer offer = assertInstanceOf(RdpProtocol.ClipRemoteOffer.class,
                RdpProtocol.decode(RdpProtocol.CLIP_REMOTE_OFFER, inbound(1).put((byte) 2).flip()));
        assertEquals(RdpProtocol.CLIPKIND_IMAGE, offer.kinds());

        ByteBuffer buf = inbound(8).put((byte) 1).put((byte) 1).put(new byte[]{'h', 0, 'i', 0});
        RdpProtocol.ClipRemoteData data = assertInstanceOf(RdpProtocol.ClipRemoteData.class,
                RdpProtocol.decode(RdpProtocol.CLIP_REMOTE_DATA, buf.flip()));
        assertTrue(data.ok());
        assertArrayEquals(new byte[]{'h', 0, 'i', 0}, data.data());

        RdpProtocol.ClipRemoteData failed = assertInstanceOf(RdpProtocol.ClipRemoteData.class,
                RdpProtocol.decode(RdpProtocol.CLIP_REMOTE_DATA, inbound(2).put((byte) 1).put((byte) 0).flip()));
        assertFalse(failed.ok());
        assertEquals(0, failed.data().length);

        RdpProtocol.ClipRequest req = assertInstanceOf(RdpProtocol.ClipRequest.class,
                RdpProtocol.decode(RdpProtocol.CLIP_REQUEST, inbound(1).put((byte) 1).flip()));
        assertEquals(RdpProtocol.CLIPKIND_TEXT, req.kind());
    }

    @Test
    void inputMessages() {
        ByteBuffer key = body(RdpProtocol.key(true, 0xA3, RdpProtocol.KEY_REPEAT), RdpProtocol.KEY);
        assertEquals(1, key.get());
        assertEquals(0xA3, key.getShort());
        assertEquals(RdpProtocol.KEY_REPEAT, key.get());

        ByteBuffer uni = body(RdpProtocol.unicode(false, 'é'), RdpProtocol.UNICODE);
        assertEquals(0, uni.get());
        assertEquals('é', (char) uni.getShort());

        ByteBuffer mouse = body(RdpProtocol.mouse(RdpProtocol.PTR_MOVE, -5, 70_000), RdpProtocol.MOUSE);
        assertEquals(RdpProtocol.PTR_MOVE, mouse.getShort() & 0xFFFF);
        assertEquals(0, mouse.getShort() & 0xFFFF, "negative coordinates clamp to 0");
        assertEquals(0xFFFF, mouse.getShort() & 0xFFFF, "oversized coordinates clamp to u16");

        ByteBuffer resize = body(RdpProtocol.resize(1922, 927, 150), RdpProtocol.RESIZE);
        assertEquals(1922, resize.getShort() & 0xFFFF);
        assertEquals(927, resize.getShort() & 0xFFFF);
        assertEquals(150, resize.getShort() & 0xFFFF);

        assertEquals(1, body(RdpProtocol.certReply(true), RdpProtocol.CERT_REPLY).get());
        assertEquals(5, RdpProtocol.bye().length);
    }

    /** Builds an inbound body the way the helper writes it. */
    private static ByteBuffer inbound(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putStr(ByteBuffer b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) bytes.length).put(bytes);
    }

    @Test
    void decodesConnectedAndHello() {
        ByteBuffer c = inbound(5).putShort((short) 1920).putShort((short) 1200).put((byte) 1).flip();
        RdpProtocol.Connected connected = assertInstanceOf(RdpProtocol.Connected.class,
                RdpProtocol.decode(RdpProtocol.CONNECTED, c));
        assertEquals(1920, connected.width());
        assertTrue(connected.unicodeInput());

        ByteBuffer h = inbound(32).putShort((short) 1);
        putStr(h, "3.33.0");
        RdpProtocol.HelloReply hello = assertInstanceOf(RdpProtocol.HelloReply.class,
                RdpProtocol.decode(RdpProtocol.HELLO_REPLY, h.flip()));
        assertEquals("3.33.0", hello.freerdpVersion());
    }

    @Test
    void decodesAFrameAsAViewOverItsPixels() {
        ByteBuffer f = inbound(8 + 2 * 1 * 4)
                .putShort((short) 10).putShort((short) 20).putShort((short) 2).putShort((short) 1)
                .putInt(0x00112233).putInt(0x00445566).flip();
        RdpProtocol.Frame frame = assertInstanceOf(RdpProtocol.Frame.class,
                RdpProtocol.decode(RdpProtocol.FRAME, f));
        assertEquals(10, frame.x());
        assertEquals(20, frame.y());
        assertEquals(0x00112233, frame.pixels().getInt(0));
        assertEquals(0x00445566, frame.pixels().getInt(4));
    }

    @Test
    void aTruncatedFrameIsRejected() {
        ByteBuffer f = inbound(8 + 4).putShort((short) 0).putShort((short) 0)
                .putShort((short) 2).putShort((short) 1).putInt(0).flip(); // one pixel short
        assertThrows(BufferUnderflowException.class, () -> RdpProtocol.decode(RdpProtocol.FRAME, f));
    }

    @Test
    void decodesPointerAndCertificateAndDisconnect() {
        ByteBuffer p = inbound(4 + 8 + 4).putInt(7).putShort((short) 1).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putInt(0xFF00FF00).flip();
        RdpProtocol.PointerNew ptr = assertInstanceOf(RdpProtocol.PointerNew.class,
                RdpProtocol.decode(RdpProtocol.POINTER_NEW, p));
        assertEquals(7, ptr.id());
        assertArrayEquals(new int[]{0xFF00FF00}, ptr.argb());

        ByteBuffer c = inbound(128);
        putStr(c, "orion");
        c.putShort((short) 3389);
        putStr(c, "GNOME");
        putStr(c, "CN = GNOME");
        putStr(c, "CN = GNOME");
        putStr(c, "77:18");
        c.putInt(RdpProtocol.CERT_FLAG_MISMATCH);
        RdpProtocol.CertRequest cert = assertInstanceOf(RdpProtocol.CertRequest.class,
                RdpProtocol.decode(RdpProtocol.CERT_REQUEST, c.flip()));
        assertEquals("orion", cert.host());
        assertEquals(3389, cert.port());
        assertEquals("77:18", cert.sha256Fingerprint());
        assertTrue(cert.nameMismatch());

        ByteBuffer d = inbound(64).putInt(0x00020014);
        putStr(d, "logon failure");
        RdpProtocol.Disconnected disc = assertInstanceOf(RdpProtocol.Disconnected.class,
                RdpProtocol.decode(RdpProtocol.DISCONNECTED, d.flip()));
        assertTrue(disc.credentialsRejected());
    }

    @Test
    void onlyCredentialFailuresTriggerARePrompt() {
        assertTrue(new RdpProtocol.Disconnected(0x00020009L, "").credentialsRejected());
        assertTrue(new RdpProtocol.Disconnected(0x00020015L, "").credentialsRejected());
        assertTrue(new RdpProtocol.Disconnected(0x0002001BL, "").credentialsRejected());
        assertFalse(new RdpProtocol.Disconnected(0L, "").credentialsRejected());
        assertFalse(new RdpProtocol.Disconnected(0x00020008L, "TLS connect failed").credentialsRejected());
        assertFalse(new RdpProtocol.Disconnected(0xFFFFFFFFL, "").credentialsRejected());
    }

    @Test
    void aSecurityNegotiationFailureIsRecognisedAndIsNotACredentialFailure() {
        // FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED: MAKE_FREERDP_ERROR(CONNECT, 0x0C).
        RdpProtocol.Disconnected nego = new RdpProtocol.Disconnected(0x0002000CL,
                "The connection failed at negotiating security settings.");
        assertTrue(nego.securityNegotiationFailed());
        assertFalse(nego.credentialsRejected(), "no password prompt for a refused security layer");
        assertFalse(new RdpProtocol.Disconnected(0x00020008L, "").securityNegotiationFailed());
        assertFalse(new RdpProtocol.Disconnected(0L, "").securityNegotiationFailed());
    }

    @Test
    void anUnknownTypeIsReportedNotFatal() {
        RdpProtocol.Unknown u = assertInstanceOf(RdpProtocol.Unknown.class,
                RdpProtocol.decode(0xEE, inbound(3).put(new byte[3]).flip()));
        assertEquals(0xEE, u.type());
        assertEquals(3, u.length());
    }

    // ------------------------------------------------------------------ protocol v10 (audio)

    @Test
    void versionMatchesProtocolH() {
        assertEquals(13, RdpProtocol.VERSION);
    }

    @Test
    void connectEndsWithAudioFlagsAndLatencyAfterTheDriveList() {
        byte[] msg = RdpProtocol.connect("h", 3389, "u", "", "pw".toCharArray(), "", 800, 600, 100, 0, 0, "",
                RdpProtocol.Gateway.NONE, java.util.List.of(new RdpProtocol.Drive(5, "Docs")),
                RdpProtocol.audioFlags(true, true, false), 120);
        ByteBuffer b = gatewayPart(msg);
        b.get(); // gateway flags
        str(b);
        b.getShort();
        str(b);
        str(b);
        str(b);
        assertEquals(1, b.get() & 0xFF, "one drive");
        assertEquals(5, b.getInt());
        assertEquals("Docs", str(b));
        assertEquals(RdpProtocol.AUDIO_PLAYBACK | RdpProtocol.AUDIO_MIC, b.get() & 0xFF);
        assertEquals(120, b.getShort() & 0xFFFF);
        assertNoPrinter(b);
        assertFalse(b.hasRemaining());
    }

    @Test
    void oldDriveOverloadSendsNoAudio() {
        byte[] a = RdpProtocol.connect("h", 3389, "u", "", null, "", 800, 600, 100, 0, 0, "",
                RdpProtocol.Gateway.NONE, java.util.List.of());
        byte[] c = RdpProtocol.connect("h", 3389, "u", "", null, "", 800, 600, 100, 0, 0, "",
                RdpProtocol.Gateway.NONE, java.util.List.of(), 0, 0);
        assertArrayEquals(c, a);
        ByteBuffer b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        // ... audioFlags, u16 latency, then the empty printer part (6 bytes) last
        assertEquals(0, b.get(a.length - 9));
        assertEquals(0, b.getShort(a.length - 8));
    }

    @Test
    void audioFlagsMask() {
        assertEquals(RdpProtocol.AUDIO_PLAYBACK | RdpProtocol.AUDIO_AAC, RdpProtocol.audioFlags(true, false, true));
        assertEquals(RdpProtocol.AUDIO_PLAYBACK | RdpProtocol.AUDIO_MIC | RdpProtocol.AUDIO_AAC,
                RdpProtocol.audioFlags(true, true, true));
        assertEquals(0, RdpProtocol.audioFlags(false, false, true), "AAC without playback is dropped");
        assertEquals(RdpProtocol.AUDIO_MIC, RdpProtocol.audioFlags(false, true, true));
        assertEquals(0, RdpProtocol.audioFlags(false, false, false));
    }

    @Test
    void audioDelayIsOneClampedU16() {
        ByteBuffer b = body(RdpProtocol.audioDelay(78), RdpProtocol.AUDIO_DELAY);
        assertEquals(78, b.getShort() & 0xFFFF);
        assertEquals(0, b.remaining());
        assertEquals(0, body(RdpProtocol.audioDelay(-5), RdpProtocol.AUDIO_DELAY).getShort());
        assertEquals(RdpProtocol.AUDIO_DELAY_MAX_MS,
                body(RdpProtocol.audioDelay(70_000), RdpProtocol.AUDIO_DELAY).getShort() & 0xFFFF);
    }

    @Test
    void micDataLayoutAndRange() {
        byte[] data = {9, 1, 2, 3, 9};
        ByteBuffer b = body(RdpProtocol.micData(0xCAFE, data, 1, 3), RdpProtocol.MIC_DATA);
        assertEquals(0xCAFE, b.getInt());
        byte[] got = new byte[b.remaining()];
        b.get(got);
        assertArrayEquals(new byte[]{1, 2, 3}, got);

        assertEquals(0, body(RdpProtocol.micData(1, data, 0, 0), RdpProtocol.MIC_DATA).getInt() - 1);
        assertThrows(IllegalArgumentException.class, () -> RdpProtocol.micData(1, data, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> RdpProtocol.micData(1, data, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> RdpProtocol.micData(1, data, 3, 3));
        byte[] big = new byte[RdpProtocol.AUDIO_MAX_CHUNK + 1];
        assertEquals(RdpProtocol.AUDIO_MAX_CHUNK + 9,
                RdpProtocol.micData(1, big, 0, RdpProtocol.AUDIO_MAX_CHUNK).length);
        assertThrows(IllegalArgumentException.class, () -> RdpProtocol.micData(1, big, 0, big.length));
    }

    @Test
    void decodesAudioMessages() {
        RdpProtocol.AudioOpen open = assertInstanceOf(RdpProtocol.AudioOpen.class, RdpProtocol.decode(
                RdpProtocol.AUDIO_OPEN, inbound(6).putInt(44100).put((byte) 2).put((byte) 1).flip()));
        assertEquals(44100, open.sampleRate());
        assertEquals(2, open.channels());
        assertEquals(1, open.codec());

        assertInstanceOf(RdpProtocol.AudioClose.class,
                RdpProtocol.decode(RdpProtocol.AUDIO_CLOSE, inbound(0).flip()));

        RdpProtocol.AudioVolume vol = assertInstanceOf(RdpProtocol.AudioVolume.class, RdpProtocol.decode(
                RdpProtocol.AUDIO_VOLUME, inbound(4).putShort((short) 0x8000).putShort((short) 0xFFFF).flip()));
        assertEquals(0x8000, vol.left());
        assertEquals(0xFFFF, vol.right());

        RdpProtocol.MicOpen mic = assertInstanceOf(RdpProtocol.MicOpen.class, RdpProtocol.decode(
                RdpProtocol.MIC_OPEN, inbound(13).putInt(7).putInt(16000).put((byte) 1).putInt(320).flip()));
        assertEquals(7, mic.streamId());
        assertEquals(16000, mic.sampleRate());
        assertEquals(1, mic.channels());
        assertEquals(320, mic.framesPerPacket());

        RdpProtocol.MicClose mc = assertInstanceOf(RdpProtocol.MicClose.class,
                RdpProtocol.decode(RdpProtocol.MIC_CLOSE, inbound(4).putInt(7).flip()));
        assertEquals(7, mc.streamId());
    }

    @Test
    void audioDataCarriesThePayloadBytes() {
        byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};
        RdpProtocol.AudioData d = assertInstanceOf(RdpProtocol.AudioData.class,
                RdpProtocol.decode(RdpProtocol.AUDIO_DATA, inbound(8).put(payload).flip()));
        byte[] got = new byte[d.samples().remaining()];
        d.samples().get(got);
        assertArrayEquals(payload, got);
        RdpProtocol.AudioData empty = assertInstanceOf(RdpProtocol.AudioData.class,
                RdpProtocol.decode(RdpProtocol.AUDIO_DATA, inbound(0).flip()));
        assertEquals(0, empty.samples().remaining());
    }

    @Test
    void truncatedAudioOpenAndMicOpenUnderflow() {
        assertThrows(BufferUnderflowException.class, () -> RdpProtocol.decode(RdpProtocol.AUDIO_OPEN,
                inbound(5).putInt(48000).put((byte) 2).flip()));
        assertThrows(BufferUnderflowException.class, () -> RdpProtocol.decode(RdpProtocol.MIC_OPEN,
                inbound(12).putInt(1).putInt(16000).put((byte) 1).flip()));
        assertThrows(BufferUnderflowException.class, () -> RdpProtocol.decode(RdpProtocol.AUDIO_VOLUME,
                inbound(2).putShort((short) 1).flip()));
    }

    @Test
    void resolveRequestCarriesTheName() {
        byte[] name = "host.example".getBytes(StandardCharsets.UTF_8);
        RdpProtocol.ResolveRequest r = assertInstanceOf(RdpProtocol.ResolveRequest.class,
                RdpProtocol.decode(RdpProtocol.RESOLVE,
                        inbound(2 + name.length).putShort((short) name.length).put(name).flip()));
        assertEquals("host.example", r.name());
    }

    @Test
    void resolveReplyListsAddressesAndCapsThem() {
        ByteBuffer b = body(RdpProtocol.resolveReply(java.util.List.of("10.0.0.5", "fe80::1")),
                RdpProtocol.RESOLVE_REPLY);
        assertEquals(2, b.get() & 0xFF);
        assertEquals("10.0.0.5", str(b));
        assertEquals("fe80::1", str(b));
        assertFalse(b.hasRemaining());

        ByteBuffer empty = body(RdpProtocol.resolveReply(java.util.List.of()), RdpProtocol.RESOLVE_REPLY);
        assertEquals(0, empty.get() & 0xFF);
        assertFalse(empty.hasRemaining());

        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("10.0.0." + i);
        }
        ByteBuffer capped = body(RdpProtocol.resolveReply(many), RdpProtocol.RESOLVE_REPLY);
        assertEquals(RdpProtocol.RESOLVE_MAX_ADDRESSES, capped.get() & 0xFF);
    }
}
