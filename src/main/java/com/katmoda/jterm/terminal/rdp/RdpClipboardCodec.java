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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Converts between what the RDP clipboard channel carries and what Java holds. Pure functions, no
 * Swing and no clipboard access, so it is unit-tested directly.
 *
 * <ul>
 *   <li><b>Text</b> is {@code CF_UNICODETEXT}: UTF-16LE, NUL-terminated, Windows line endings.</li>
 *   <li><b>Images</b> are {@code CF_DIB}: a BITMAPINFOHEADER and the pixels, i.e. a {@code .bmp}
 *       without its 14-byte file header. That is also what lets the JDK's own BMP reader and writer
 *       do the work.</li>
 * </ul>
 *
 * <p>Everything here treats its input as hostile: it arrives from the remote server. Sizes are
 * checked against {@link RdpProtocol#CLIP_MAX_BYTES} and the image header is validated against the
 * data actually present <em>before</em> any pixel buffer is allocated, so a header claiming a huge
 * image cannot make the reader allocate one. Compressed DIBs (RLE, embedded JPEG/PNG) are refused
 * rather than expanded.</p>
 */
public final class RdpClipboardCodec {

    private static final int FILE_HEADER = 14;
    private static final int BI_RGB = 0;
    private static final int BI_BITFIELDS = 3;

    private RdpClipboardCodec() {
    }

    // ------------------------------------------------------------------------------------ text

    /**
     * The wire form of {@code text}: every line ending becomes CRLF (what Windows applications
     * expect), UTF-16LE, one terminating NUL. {@code null} when it would exceed the size limit.
     */
    public static byte[] encodeText(String text) {
        if (text == null) {
            return null;
        }
        String crlf = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        if ((long) crlf.length() * 2 + 2 > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        byte[] body = crlf.getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        return out;
    }

    /**
     * Text from the wire: decoded as UTF-16LE up to the first NUL, with CRLF (and a lone CR) turned
     * into LF, which is what the local clipboard layers expect. {@code null} for an empty or
     * oversized payload.
     */
    public static String decodeText(byte[] wire) {
        if (wire == null || wire.length < 2 || wire.length > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        int end = wire.length & ~1; // an odd trailing byte is not a character
        for (int i = 0; i + 1 < end; i += 2) {
            if (wire[i] == 0 && wire[i + 1] == 0) {
                end = i;
                break;
            }
        }
        if (end == 0) {
            return null;
        }
        String s = new String(wire, 0, end, StandardCharsets.UTF_16LE);
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ----------------------------------------------------------------------------------- image

    /**
     * The CF_DIB form of {@code image}: a 24-bit bottom-up DIB, transparency flattened onto white
     * (a DIB has no usable alpha, and black is the wrong default for a copied logo).
     * {@code null} when it cannot be encoded or exceeds the size limit.
     */
    public static byte[] encodeImage(Image image) {
        if (image == null) {
            return null;
        }
        BufferedImage rgb = toRgb(image);
        if (rgb == null) {
            return null;
        }
        // Reject on the arithmetic first: encoding a huge image to find out it is too big is the
        // very allocation the limit is there to prevent.
        long rows = (((long) rgb.getWidth() * 3 + 3) / 4) * 4;
        if (rows * rgb.getHeight() + 40 > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        try {
            ByteArrayOutputStream bmp = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "bmp", bmp)) {
                return null;
            }
            byte[] all = bmp.toByteArray();
            if (all.length <= FILE_HEADER || all.length - FILE_HEADER > RdpProtocol.CLIP_MAX_BYTES) {
                return null;
            }
            byte[] dib = new byte[all.length - FILE_HEADER];
            System.arraycopy(all, FILE_HEADER, dib, 0, dib.length);
            return dib;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * An image from a CF_DIB, or {@code null} if it is malformed, compressed, inconsistent with its
     * own header, or too large.
     */
    public static BufferedImage decodeImage(byte[] dib) {
        if (dib == null || dib.length < 40 || dib.length > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getInt(0);
        int width = b.getInt(4);
        int height = b.getInt(8);
        int bitCount = b.getShort(14) & 0xFFFF;
        int compression = b.getInt(16);
        long colorsUsed = b.getInt(32) & 0xFFFFFFFFL;

        // 40 is BITMAPINFOHEADER; the V4/V5 headers (108/124) are supersets of it.
        if (headerSize != 40 && headerSize != 108 && headerSize != 124) {
            return null;
        }
        if (width <= 0 || height == 0 || height == Integer.MIN_VALUE) {
            return null;
        }
        if (compression != BI_RGB && compression != BI_BITFIELDS) {
            return null;
        }
        if (bitCount != 1 && bitCount != 4 && bitCount != 8 && bitCount != 16 && bitCount != 24
                && bitCount != 32) {
            return null;
        }
        long absHeight = Math.abs((long) height);
        long rowBytes = (((long) width * bitCount + 31) / 32) * 4;
        long pixelBytes = rowBytes * absHeight;

        long tableEntries = bitCount <= 8 ? (colorsUsed != 0 ? colorsUsed : (1L << bitCount)) : colorsUsed;
        if (tableEntries > 256) {
            return null;
        }
        // BITMAPINFOHEADER keeps its channel masks outside the header; V4/V5 carry them inside it.
        long masks = (compression == BI_BITFIELDS && headerSize == 40) ? 12 : 0;
        long pixelOffset = headerSize + masks + tableEntries * 4;
        // The data present must cover what the header claims, or the header is lying about its size.
        if (pixelOffset + pixelBytes > dib.length) {
            return null;
        }

        ByteBuffer file = ByteBuffer.allocate(FILE_HEADER + dib.length).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M')
                .putInt(FILE_HEADER + dib.length)
                .putInt(0)
                .putInt((int) (FILE_HEADER + pixelOffset))
                .put(dib);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.array()));
            return image != null ? toRgb(image) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A TYPE_INT_RGB copy of {@code image} on a white background, or null if it has no size. */
    private static BufferedImage toRgb(Image image) {
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
