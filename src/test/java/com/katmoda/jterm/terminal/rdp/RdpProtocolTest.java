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
                "sha256:aa:bb", 1920, 1200, 150, RdpProtocol.clipboardFlags(true, true));
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
        assertFalse(b.hasRemaining());
    }

    @Test
    void clipboardFlagsAreIndependentAndZeroKeepsTheChannelClosed() {
        assertEquals(0, RdpProtocol.clipboardFlags(false, false));
        assertEquals(RdpProtocol.CLIP_REMOTE_TO_LOCAL, RdpProtocol.clipboardFlags(true, false));
        assertEquals(RdpProtocol.CLIP_LOCAL_TO_REMOTE, RdpProtocol.clipboardFlags(false, true));
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
    void anUnknownTypeIsReportedNotFatal() {
        RdpProtocol.Unknown u = assertInstanceOf(RdpProtocol.Unknown.class,
                RdpProtocol.decode(0xEE, inbound(3).put(new byte[3]).flip()));
        assertEquals(0xEE, u.type());
        assertEquals(3, u.length());
    }
}
