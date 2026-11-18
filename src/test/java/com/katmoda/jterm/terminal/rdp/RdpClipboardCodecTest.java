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

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RdpClipboardCodecTest {

    // ------------------------------------------------------------------------------------ text

    @Test
    void textGoesOutAsNulTerminatedUtf16WithWindowsLineEndings() {
        byte[] wire = RdpClipboardCodec.encodeText("a\nb");
        byte[] expected = "a\r\nb".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(expected.length + 2, wire.length);
        assertArrayEquals(expected, java.util.Arrays.copyOf(wire, expected.length));
        assertEquals(0, wire[wire.length - 1]);
        assertEquals(0, wire[wire.length - 2]);
    }

    @Test
    void anExistingCrlfIsNotDoubled() {
        assertEquals("a\r\nb\u0000", new String(RdpClipboardCodec.encodeText("a\r\nb"), StandardCharsets.UTF_16LE));
        assertEquals("a\r\nb\r\n\u0000", new String(RdpClipboardCodec.encodeText("a\rb\n"), StandardCharsets.UTF_16LE));
    }

    @Test
    void textRoundTripsIncludingNonBmpCharacters() {
        String text = "héllo wörld 😀\nline two";
        assertEquals(text, RdpClipboardCodec.decodeText(RdpClipboardCodec.encodeText(text)));
    }

    @Test
    void decodeStopsAtTheFirstNulAndNormalizesLineEndings() {
        byte[] wire = "one\r\ntwo\rthree\u0000garbage after the terminator".getBytes(StandardCharsets.UTF_16LE);
        assertEquals("one\ntwo\nthree", RdpClipboardCodec.decodeText(wire));
    }

    @Test
    void decodeAcceptsATextWithoutATerminatorAndIgnoresAStrayByte() {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_16LE);
        assertEquals("abc", RdpClipboardCodec.decodeText(body));
        byte[] odd = java.util.Arrays.copyOf(body, body.length + 1);
        assertEquals("abc", RdpClipboardCodec.decodeText(odd));
    }

    @Test
    void emptyOrOversizedTextIsRefused() {
        assertNull(RdpClipboardCodec.decodeText(null));
        assertNull(RdpClipboardCodec.decodeText(new byte[0]));
        assertNull(RdpClipboardCodec.decodeText(new byte[]{0, 0}), "only a terminator");
        assertNull(RdpClipboardCodec.decodeText(new byte[RdpProtocol.CLIP_MAX_BYTES + 2]));
        assertNull(RdpClipboardCodec.encodeText(null));
        assertNull(RdpClipboardCodec.encodeText("x".repeat(RdpProtocol.CLIP_MAX_BYTES / 2)),
                "the terminator pushes it over the limit");
    }

    // ----------------------------------------------------------------------------------- image

    private static BufferedImage sample() {
        BufferedImage img = new BufferedImage(5, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                img.setRGB(x, y, (x * 50) << 16 | (y * 100) << 8 | 0x7F);
            }
        }
        return img;
    }

    @Test
    void imageRoundTripsPixelForPixel() {
        BufferedImage in = sample();
        byte[] dib = RdpClipboardCodec.encodeImage(in);
        assertNotNull(dib);
        BufferedImage out = RdpClipboardCodec.decodeImage(dib);
        assertNotNull(out);
        assertEquals(5, out.getWidth());
        assertEquals(3, out.getHeight());
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                assertEquals(in.getRGB(x, y) & 0xFFFFFF, out.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void theDibHasNoFileHeader() {
        byte[] dib = RdpClipboardCodec.encodeImage(sample());
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(40, b.getInt(0), "starts with the BITMAPINFOHEADER, not 'BM'");
        assertEquals(5, b.getInt(4));
        assertEquals(24, b.getShort(14));
    }

    @Test
    void transparencyIsFlattenedOntoWhite() {
        BufferedImage clear = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB); // fully transparent
        BufferedImage out = RdpClipboardCodec.decodeImage(RdpClipboardCodec.encodeImage(clear));
        assertEquals(0xFFFFFF, out.getRGB(0, 0) & 0xFFFFFF);
    }

    /** A DIB header with the given fields, followed by {@code pixelBytes} of zeros. */
    private static byte[] dib(int headerSize, int width, int height, int bits, int compression, int pixelBytes) {
        ByteBuffer b = ByteBuffer.allocate(Math.max(headerSize, 40) + pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, headerSize).putInt(4, width).putInt(8, height).putShort(12, (short) 1)
                .putShort(14, (short) bits).putInt(16, compression);
        return b.array();
    }

    @Test
    void aHeaderThatClaimsMoreThanTheDataHoldsIsRefusedBeforeAnyAllocation() {
        // Claims 60000x60000 at 32 bpp (14 GB) with 100 bytes of pixels: must not reach the reader.
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 60_000, 60_000, 32, 0, 100)));
        // Honest 2x2x32 = 16 bytes of pixels decodes.
        assertNotNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 32, 0, 16)));
        // One byte short does not.
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 32, 0, 15)));
    }

    @Test
    void aSmallLowDepthDibThatWouldDecodeToAHugeImageIsRefused() {
        // 1 bpp, 16384x16000: 31 MiB on the wire (inside the transfer limit, and all zeros, so nearly
        // free under RDP compression) but 1 GiB as the 32-bit image it decodes to. This used to throw
        // OutOfMemoryError even with a 2 GiB heap.
        int rows = 16384 / 8;
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 16384, 16000, 1, 0, 8 + rows * 16000)));
        // At the cap it still decodes.
        BufferedImage atCap = RdpClipboardCodec.decodeImage(dib(40, 4096, 4096, 1, 0, 8 + 512 * 4096));
        assertNotNull(atCap);
        assertEquals(RdpClipboardCodec.MAX_PIXELS, (long) atCap.getWidth() * atCap.getHeight());
    }

    @Test
    void anImageTooLargeToSendIsRefusedBeforeItIsCopied() {
        // A local image that claims 100000x100000: refusing it must not first allocate its RGB copy.
        java.awt.Image huge = new java.awt.Image() {
            @Override
            public int getWidth(java.awt.image.ImageObserver o) {
                return 100_000;
            }

            @Override
            public int getHeight(java.awt.image.ImageObserver o) {
                return 100_000;
            }

            @Override
            public java.awt.image.ImageProducer getSource() {
                throw new AssertionError("not reached");
            }

            @Override
            public java.awt.Graphics getGraphics() {
                throw new AssertionError("not reached");
            }

            @Override
            public Object getProperty(String name, java.awt.image.ImageObserver o) {
                return null;
            }
        };
        assertNull(RdpClipboardCodec.encodeImage(huge));
    }

    @Test
    void compressedOrOddlyShapedDibsAreRefused() {
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 8, 1, 64)), "RLE8");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 24, 4, 64)), "embedded JPEG");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 24, 5, 64)), "embedded PNG");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 2, 7, 0, 64)), "7 bits per pixel");
        assertNull(RdpClipboardCodec.decodeImage(dib(12, 2, 2, 24, 0, 64)), "an OS/2 header");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 0, 2, 24, 0, 64)), "zero width");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, -2, 2, 24, 0, 64)), "negative width");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, 0, 24, 0, 64)), "zero height");
        assertNull(RdpClipboardCodec.decodeImage(dib(40, 2, Integer.MIN_VALUE, 24, 0, 64)));
    }

    @Test
    void garbageAndTruncationNeverThrow() {
        assertNull(RdpClipboardCodec.decodeImage(null));
        assertNull(RdpClipboardCodec.decodeImage(new byte[0]));
        assertNull(RdpClipboardCodec.decodeImage(new byte[39]));
        byte[] good = RdpClipboardCodec.encodeImage(sample());
        assertNull(RdpClipboardCodec.decodeImage(java.util.Arrays.copyOf(good, good.length - 5)));
        byte[] noise = new byte[200];
        new java.util.Random(7).nextBytes(noise);
        assertNull(RdpClipboardCodec.decodeImage(noise));
    }

    @Test
    void aTopDownDibDecodes() {
        // Negative height = rows stored top to bottom. 2x2, 24 bpp, rows padded to 8 bytes.
        byte[] d = dib(40, 2, -2, 24, 0, 16);
        ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).position(40);
        d[40] = 0; d[41] = 0; d[42] = (byte) 0xFF; // top-left: red (BGR order)
        BufferedImage img = RdpClipboardCodec.decodeImage(d);
        assertNotNull(img);
        assertEquals(0xFF0000, img.getRGB(0, 0) & 0xFFFFFF);
    }

    // ------------------------------------------------------------------------------- CF_DIBV5

    /**
     * A CF_DIBV5 as Windows renders one: a 124-byte BITMAPV5HEADER, 32 bpp, the channel masks inside
     * the header, sRGB colour space, no profile. {@code argb} rows top to bottom.
     */
    private static byte[] dibV5(int[][] argb, boolean bitfields, boolean topDown) {
        int h = argb.length;
        int w = argb[0].length;
        ByteBuffer b = ByteBuffer.allocate(124 + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(124).putInt(w).putInt(topDown ? -h : h).putShort((short) 1).putShort((short) 32)
                .putInt(bitfields ? 3 : 0).putInt(w * h * 4).putInt(2835).putInt(2835).putInt(0).putInt(0);
        b.putInt(0x00FF0000).putInt(0x0000FF00).putInt(0x000000FF).putInt(bitfields ? 0xFF000000 : 0);
        b.putInt(0x73524742); // LCS_sRGB
        b.position(124);
        for (int row = 0; row < h; row++) {
            int[] line = argb[topDown ? row : h - 1 - row];
            for (int px : line) {
                b.putInt(px); // little-endian 0xAARRGGBB = bytes B, G, R, A
            }
        }
        return b.array();
    }

    @Test
    void aDibV5WithAlphaDecodesFlattenedOntoWhite() {
        byte[] v5 = dibV5(new int[][]{
                {0xFFFF0000, 0xFF00FF00},
                {0xFF0000FF, 0x00000000}}, true, false);
        BufferedImage img = RdpClipboardCodec.decodeImage(v5);
        assertNotNull(img);
        assertEquals(2, img.getWidth());
        assertEquals(2, img.getHeight());
        assertEquals(0xFF0000, img.getRGB(0, 0) & 0xFFFFFF, "bottom-up rows land the right way up");
        assertEquals(0x00FF00, img.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(0x0000FF, img.getRGB(0, 1) & 0xFFFFFF);
        assertEquals(0xFFFFFF, img.getRGB(1, 1) & 0xFFFFFF, "a transparent pixel becomes white");
    }

    @Test
    void aTopDownBiRgbDibV5Decodes() {
        byte[] v5 = dibV5(new int[][]{{0xFF123456}, {0xFFABCDEF}}, false, true);
        BufferedImage img = RdpClipboardCodec.decodeImage(v5);
        assertNotNull(img);
        assertEquals(0x123456, img.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0xABCDEF, img.getRGB(0, 1) & 0xFFFFFF);
    }

    @Test
    void aDibV5ThatLiesAboutItsSizeOrProfileNeverThrows() {
        byte[] v5 = dibV5(new int[][]{{0xFF000000, 0xFF000000}}, true, false);
        ByteBuffer.wrap(v5).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 50_000).putInt(8, 50_000);
        assertNull(RdpClipboardCodec.decodeImage(v5), "claims far more pixels than it carries");

        byte[] profile = dibV5(new int[][]{{0xFF00FF00}}, true, false);
        ByteBuffer pb = ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN);
        pb.putInt(56, 0x4D424544); // PROFILE_EMBEDDED
        pb.putInt(112, 0x7FFF_0000).putInt(116, 0x1000); // profile offset and size far past the data
        BufferedImage img = RdpClipboardCodec.decodeImage(profile); // decoded or refused, never thrown
        if (img != null) {
            assertEquals(0x00FF00, img.getRGB(0, 0) & 0xFFFFFF);
        }
    }

    // ------------------------------------------------------------------------------- CF_HTML

    /** The offsets a CF_HTML header gives, by name. */
    private static long offset(byte[] cfHtml, String name) {
        String header = new String(cfHtml, 0, Math.min(cfHtml.length, 200), StandardCharsets.US_ASCII);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + ":(\\d+)").matcher(header);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String range(byte[] cfHtml, String from, String to) {
        int a = (int) offset(cfHtml, from);
        int b = (int) offset(cfHtml, to);
        return new String(cfHtml, a, b - a, StandardCharsets.UTF_8);
    }

    @Test
    void htmlGoesOutWithOffsetsThatPointAtTheFragmentInUtf8Bytes() {
        byte[] wire = RdpClipboardCodec.encodeHtml("<b>héllo 😀</b>");
        assertNotNull(wire);
        assertEquals(0, wire[wire.length - 1], "NUL-terminated");
        assertEquals("<b>héllo 😀</b>", range(wire, "StartFragment", "EndFragment"),
                "byte offsets, so the multi-byte characters before the end don't shift it");
        String doc = range(wire, "StartHTML", "EndHTML");
        assertEquals("<html><body><!--StartFragment--><b>héllo 😀</b><!--EndFragment--></body></html>", doc);
        assertEquals(wire.length - 1, offset(wire, "EndHTML"));
        assertEquals("Version:0.9", new String(wire, 0, 11, StandardCharsets.US_ASCII));
    }

    @Test
    void anHtmlDocumentKeepsItsOwnMarkersOrHasItsBodyMarked() {
        String marked = "<html><head><style>b{}</style></head><body>x <!--StartFragment-->sel<!--EndFragment--> y</body></html>";
        byte[] a = RdpClipboardCodec.encodeHtml(marked);
        assertEquals("sel", range(a, "StartFragment", "EndFragment"));
        assertEquals(marked, range(a, "StartHTML", "EndHTML"), "the document is sent as it is");

        byte[] b = RdpClipboardCodec.encodeHtml("<HTML><BODY class=\"c\"><p>one</p></BODY></HTML>");
        assertEquals("<p>one</p>", range(b, "StartFragment", "EndFragment"));
        assertEquals("<HTML><BODY class=\"c\"><!--StartFragment--><p>one</p><!--EndFragment--></BODY></HTML>",
                range(b, "StartHTML", "EndHTML"));
    }

    @Test
    void htmlRoundTrips() {
        String html = "<table><tr><td>ä</td></tr></table>";
        RdpClipboardCodec.Html out = RdpClipboardCodec.decodeHtml(RdpClipboardCodec.encodeHtml(html));
        assertNotNull(out);
        assertEquals(html, out.fragment());
        assertEquals("<html><body><!--StartFragment-->" + html + "<!--EndFragment--></body></html>", out.document());
    }

    @Test
    void aWindowsCfHtmlDecodes() {
        // The shape Chrome and Office put on a Windows clipboard: optional SourceURL, CRLF lines,
        // StartHTML at the first '<', a NUL after EndHTML.
        String body = "<html>\r\n<body>\r\n<!--StartFragment--><i>it</i><!--EndFragment-->\r\n</body>\r\n</html>";
        String header = "Version:0.9\r\nStartHTML:%010d\r\nEndHTML:%010d\r\nStartFragment:%010d\r\n"
                + "EndFragment:%010d\r\nSourceURL:https://example.com/page\r\n";
        int h = String.format(header, 0, 0, 0, 0).length();
        int fs = h + body.indexOf("<i>");
        int fe = h + body.indexOf("<!--EndFragment");
        byte[] wire = (String.format(header, h, h + body.length(), fs, fe) + body + "\u0000")
                .getBytes(StandardCharsets.UTF_8);
        RdpClipboardCodec.Html out = RdpClipboardCodec.decodeHtml(wire);
        assertNotNull(out);
        assertEquals("<i>it</i>", out.fragment());
        assertEquals(body, out.document());
    }

    @Test
    void aCfHtmlWithoutAContextRangeFallsBackToTheFragment() {
        // StartHTML/EndHTML of -1 are allowed by the format: no context, the fragment is all there is.
        String body = "<b>x</b>";
        String header = "Version:1.0\r\nStartHTML:-1\r\nEndHTML:-1\r\nStartFragment:%010d\r\nEndFragment:%010d\r\n";
        int h = String.format(header, 0, 0).length();
        byte[] wire = (String.format(header, h, h + body.length()) + body).getBytes(StandardCharsets.UTF_8);
        RdpClipboardCodec.Html out = RdpClipboardCodec.decodeHtml(wire);
        assertNotNull(out);
        assertEquals(body, out.fragment());
        assertEquals(body, out.document());
    }

    @Test
    void lyingOrMissingHtmlOffsetsAreRefusedAndNeverThrow() {
        assertNull(RdpClipboardCodec.decodeHtml(null));
        assertNull(RdpClipboardCodec.decodeHtml(new byte[0]));
        assertNull(RdpClipboardCodec.decodeHtml("<b>no header at all</b>".getBytes(StandardCharsets.UTF_8)));
        assertNull(RdpClipboardCodec.decodeHtml(
                "Version:0.9\r\nStartHTML:0000000040\r\nEndHTML:0099999999\r\n<b>x</b>".getBytes(StandardCharsets.UTF_8)),
                "end past the data");
        assertNull(RdpClipboardCodec.decodeHtml(
                "Version:0.9\r\nStartHTML:0000000050\r\nEndHTML:0000000040\r\n<b>x</b>".getBytes(StandardCharsets.UTF_8)),
                "reversed range");
        assertNull(RdpClipboardCodec.decodeHtml(
                "Version:0.9\r\nStartHTML:99999999999999999999\r\nEndHTML:x\r\n<b>x</b>".getBytes(StandardCharsets.UTF_8)),
                "numbers that don't parse");
        byte[] endless = new byte[RdpClipboardCodec.HTML_HEADER_LIMIT + 100];
        java.util.Arrays.fill(endless, (byte) 'A');
        assertNull(RdpClipboardCodec.decodeHtml(endless), "a header with no end is given up on");
        byte[] noise = new byte[4096];
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < 200; i++) {
            r.nextBytes(noise);
            RdpClipboardCodec.decodeHtml(noise); // must not throw
        }
    }

    @Test
    void emptyOrOversizedHtmlIsRefused() {
        assertNull(RdpClipboardCodec.encodeHtml(null));
        assertNull(RdpClipboardCodec.encodeHtml(""));
        assertNull(RdpClipboardCodec.encodeHtml("x".repeat(RdpProtocol.CLIP_MAX_BYTES)));
        assertNull(RdpClipboardCodec.decodeHtml(new byte[RdpProtocol.CLIP_MAX_BYTES + 1]));
    }
}
