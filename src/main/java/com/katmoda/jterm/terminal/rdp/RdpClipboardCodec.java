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
import java.util.Locale;

/**
 * Converts between what the RDP clipboard channel carries and what Java holds. Pure functions, no
 * Swing and no clipboard access, so it is unit-tested directly.
 *
 * <ul>
 *   <li><b>Text</b> is {@code CF_UNICODETEXT}: UTF-16LE, NUL-terminated, Windows line endings.</li>
 *   <li><b>Images</b> are {@code CF_DIB}: a BITMAPINFOHEADER and the pixels, i.e. a {@code .bmp}
 *       without its 14-byte file header. That is also what lets the JDK's own BMP reader and writer
 *       do the work. A server offering only {@code CF_DIBV5} sends a BITMAPV5HEADER instead (channel
 *       masks inside the header, usually with alpha), which the same reader takes.</li>
 *   <li><b>HTML</b> is {@code CF_HTML}: UTF-8 behind a small ASCII header whose {@code StartHTML},
 *       {@code EndHTML}, {@code StartFragment} and {@code EndFragment} give byte offsets into the
 *       whole payload. The fragment is what the user selected; the document around it carries the
 *       context (styles, enclosing table) a rich paste needs.</li>
 * </ul>
 *
 * <p>Everything here treats its input as hostile: it arrives from the remote server. Sizes are
 * checked against {@link RdpProtocol#CLIP_MAX_BYTES}, and <em>before</em> any pixel buffer is
 * allocated the image header is validated against the data actually present and its pixel count
 * against {@link #MAX_PIXELS}, so neither a header that lies about its size nor a small,
 * low-bit-depth image that expands on decoding can make the reader allocate a huge one. Compressed DIBs (RLE, embedded JPEG/PNG) are refused
 * rather than expanded.</p>
 */
public final class RdpClipboardCodec {

    private static final int FILE_HEADER = 14;
    private static final int BI_RGB = 0;
    private static final int BI_BITFIELDS = 3;

    /**
     * Largest image either way, in pixels: what a 16-bit DIB inside {@link RdpProtocol#CLIP_MAX_BYTES}
     * can hold (16 Mi pixels, a 64 MiB decoded copy). Checking the header against the data present is
     * not enough on its own: a 1-bit DIB is 32 times smaller than the 32-bit image it decodes to, so
     * a 32 MiB payload, nearly free to send over RDP's bulk compression, would otherwise claim 1 GiB of
     * heap.
     */
    public static final long MAX_PIXELS = RdpProtocol.CLIP_MAX_BYTES / 2;

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

    // ------------------------------------------------------------------------------------ html

    /** HTML from a CF_HTML: the whole document, and the fragment the user actually selected. */
    public record Html(String document, String fragment) {
    }

    private static final String START_FRAGMENT = "<!--StartFragment-->";
    private static final String END_FRAGMENT = "<!--EndFragment-->";
    private static final String HTML_HEADER =
            "Version:0.9\r\nStartHTML:%010d\r\nEndHTML:%010d\r\nStartFragment:%010d\r\nEndFragment:%010d\r\n";

    /** How far into a CF_HTML its header may reach. Real ones are ~200 bytes, plus a SourceURL. */
    static final int HTML_HEADER_LIMIT = 64 * 1024;

    /**
     * The CF_HTML form of {@code html}, NUL-terminated. The fragment is what the HTML itself marks
     * with {@code <!--StartFragment-->}/{@code <!--EndFragment-->}; failing that the body's content;
     * failing that the whole input, wrapped in a minimal document. {@code null} when empty or over
     * the size limit.
     */
    public static byte[] encodeHtml(String html) {
        if (html == null || html.isEmpty() || html.length() > RdpProtocol.CLIP_MAX_BYTES) {
            return null; // a UTF-8 encoding is never shorter than the char count
        }
        String doc;
        int fragStart;
        int fragEnd;
        int markedStart = indexOfIgnoreCase(html, START_FRAGMENT, 0);
        int markedEnd = markedStart < 0 ? -1
                : indexOfIgnoreCase(html, END_FRAGMENT, markedStart + START_FRAGMENT.length());
        int body = indexOfIgnoreCase(html, "<body", 0);
        int bodyOpenEnd = body < 0 ? -1 : html.indexOf('>', body);
        int bodyClose = lastIndexOfIgnoreCase(html, "</body");
        if (markedEnd >= 0) {
            doc = html;
            fragStart = markedStart + START_FRAGMENT.length();
            fragEnd = markedEnd;
        } else if (bodyOpenEnd >= 0 && bodyClose > bodyOpenEnd) {
            doc = html.substring(0, bodyOpenEnd + 1) + START_FRAGMENT
                    + html.substring(bodyOpenEnd + 1, bodyClose) + END_FRAGMENT + html.substring(bodyClose);
            fragStart = bodyOpenEnd + 1 + START_FRAGMENT.length();
            fragEnd = bodyClose + START_FRAGMENT.length();
        } else {
            String head = "<html><body>" + START_FRAGMENT;
            doc = head + html + END_FRAGMENT + "</body></html>";
            fragStart = head.length();
            fragEnd = head.length() + html.length();
        }
        // The split points sit next to ASCII markup, so no surrogate pair is ever cut in two.
        byte[] content = doc.getBytes(StandardCharsets.UTF_8);
        int headerLength = String.format(Locale.ROOT, HTML_HEADER, 0, 0, 0, 0).length();
        long total = (long) headerLength + content.length + 1;
        if (total > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        int startFragment = headerLength + doc.substring(0, fragStart).getBytes(StandardCharsets.UTF_8).length;
        int endFragment = headerLength + doc.substring(0, fragEnd).getBytes(StandardCharsets.UTF_8).length;
        byte[] header = String.format(Locale.ROOT, HTML_HEADER, headerLength, headerLength + content.length,
                startFragment, endFragment).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[(int) total];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(content, 0, out, header.length, content.length);
        return out;
    }

    /**
     * HTML from a CF_HTML, or {@code null} when the header gives no usable offsets. The offsets are
     * the server's and are trusted for nothing: a range outside the payload, or reversed, is ignored,
     * and if neither the document nor the fragment range survives the whole thing is refused. The
     * header is read only up to the first {@code <} and at most {@link #HTML_HEADER_LIMIT} bytes.
     * Invalid UTF-8 is replaced, not rejected.
     */
    public static Html decodeHtml(byte[] wire) {
        if (wire == null || wire.length == 0 || wire.length > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        long startHtml = -1;
        long endHtml = -1;
        long startFragment = -1;
        long endFragment = -1;
        int limit = Math.min(wire.length, HTML_HEADER_LIMIT);
        int pos = 0;
        while (pos < limit && wire[pos] != '<') {
            int eol = pos;
            while (eol < limit && wire[eol] != '\r' && wire[eol] != '\n') {
                eol++;
            }
            String line = new String(wire, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int colon = line.indexOf(':');
            if (colon > 0) {
                long value = parseOffset(line.substring(colon + 1).trim());
                switch (line.substring(0, colon).trim()) {
                    case "StartHTML" -> startHtml = value;
                    case "EndHTML" -> endHtml = value;
                    case "StartFragment" -> startFragment = value;
                    case "EndFragment" -> endFragment = value;
                    default -> { }
                }
            }
            pos = eol;
            while (pos < limit && (wire[pos] == '\r' || wire[pos] == '\n')) {
                pos++;
            }
        }
        String document = slice(wire, startHtml, endHtml);
        String fragment = slice(wire, startFragment, endFragment);
        if (document == null && fragment == null) {
            return null;
        }
        return new Html(document != null ? document : fragment, fragment != null ? fragment : document);
    }

    /** {@code wire[start, end)} as UTF-8 without trailing NULs, or null if the range is unusable. */
    private static String slice(byte[] wire, long start, long end) {
        if (start < 0 || end < start || end > wire.length) {
            return null;
        }
        int e = (int) end;
        while (e > start && wire[e - 1] == 0) {
            e--;
        }
        if (e == start) {
            return null;
        }
        return new String(wire, (int) start, e - (int) start, StandardCharsets.UTF_8);
    }

    /** A header offset, or -1 for anything that is not a plain number of sane length. */
    private static long parseOffset(String value) {
        if (value.isEmpty() || value.length() > 12) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Case-insensitive search char by char ({@code toLowerCase} may change a string's length). */
    private static int indexOfIgnoreCase(String s, String needle, int from) {
        for (int i = Math.max(0, from); i + needle.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfIgnoreCase(String s, String needle) {
        for (int i = s.length() - needle.length(); i >= 0; i--) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
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
        // Reject on the arithmetic first, before even the RGB copy: encoding a huge image to find
        // out it is too big is the very allocation the limit is there to prevent.
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        long rows = (((long) w * 3 + 3) / 4) * 4;
        if (w <= 0 || h <= 0 || rows * h + 40 > RdpProtocol.CLIP_MAX_BYTES) {
            return null;
        }
        BufferedImage rgb = toRgb(image);
        if (rgb == null) {
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
        if ((long) width * absHeight > MAX_PIXELS) {
            return null;
        }
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
