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
}
