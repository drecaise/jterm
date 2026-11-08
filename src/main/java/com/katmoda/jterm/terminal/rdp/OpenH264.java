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

import com.katmoda.jterm.config.AppPaths;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Cisco's OpenH264, the H.264 decoder the Linux RDP helper loads at run time.
 *
 * <p><b>Why this is downloaded, not bundled.</b> H.264 is patent-encumbered. Cisco pays the patent
 * pool for the binary <em>it</em> distributes and passes that license on, at no cost, only when the
 * binary is downloaded separately onto the user's device (never combined with third-party software
 * beforehand), the user can switch it on and off, and the Cisco notice is shown where they do. A
 * copy built from source, or one shipped inside an installer, carries no such license. So the
 * installers contain no codec; the user opts in (Preferences) and this class fetches Cisco's own
 * file, checks it against a pinned hash and puts it where the helper can load it.</p>
 *
 * <p>The helper finds the library through {@code LD_LIBRARY_PATH} (see {@link #libraryDir}); FreeRDP
 * tries the file names {@code libopenh264.so.8}, {@code .so.7}, … and the file is stored under the
 * first of them. Windows and macOS need none of this: the helper uses the system's decoders there.
 * A Flatpak gets the codec from the {@code org.freedesktop.Platform.openh264} runtime extension.</p>
 *
 * <p>The network and the file system are the only inputs, and both are untrusted: the download is
 * size-capped, hashed before anything reads it, and the decompressed size is capped too.</p>
 */
public final class OpenH264 {

    /** Shown wherever the user controls the codec, and in the license information (Cisco's condition 3/4). */
    public static final String NOTICE = "OpenH264 Video Codec provided by Cisco Systems, Inc.";

    public static final String VERSION = "2.6.0";

    /** The file name FreeRDP's loader tries first for this release (the library's soname). */
    static final String LIBRARY_FILE = "libopenh264.so.8";

    private static final String BASE_URL = "https://ciscobinary.openh264.org/";
    private static final int MAX_DOWNLOAD_BYTES = 8 << 20;  // the real file is about 0.6 MiB
    private static final int MAX_LIBRARY_BYTES = 32 << 20;

    /** One downloadable build: its file name on Cisco's server and the SHA-256 of that file. */
    public record Asset(String file, String sha256) {
        URI uri() {
            return URI.create(BASE_URL + file);
        }
    }

    private OpenH264() {
    }

    /** The Cisco build for this CPU, or {@code null} where there is none (or none we pinned). */
    static Asset assetFor(String osName, String osArch) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) {
            return null;
        }
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> new Asset("libopenh264-" + VERSION + "-linux64.8.so.bz2",
                    "27ab53323c110b76214c1c72222f459d17febbcd1e252136cadc292b0308d75b");
            case "aarch64", "arm64" -> new Asset("libopenh264-" + VERSION + "-linux-arm64.8.so.bz2",
                    "a78aea7970150f46bcd3bb7994c9e6dd90bd7a9ea785920f5a73f6964e3fcda7");
            default -> null;
        };
    }

    /** The build to download on this machine, or {@code null} when jterm cannot offer a download. */
    public static Asset assetForThisMachine() {
        if (providedByFlatpak()) {
            return null;
        }
        return assetFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** In a Flatpak the freedesktop runtime's OpenH264 extension supplies the codec, with no download. */
    public static boolean providedByFlatpak() {
        return System.getenv("FLATPAK_ID") != null;
    }

    /** Where the downloaded codec lives: inside the config directory, so it is per user. */
    public static Path defaultDir() {
        return AppPaths.configDir().resolve("codecs").resolve("openh264");
    }

    public static boolean isInstalled(Path dir) {
        return Files.isRegularFile(dir.resolve(LIBRARY_FILE));
    }

    /**
     * The directory to put on the helper's library path, or {@code null} when the codec is switched
     * off or not downloaded (the helper then simply does not offer H.264 to the server).
     */
    public static Path libraryDir(boolean enabled, Path dir) {
        return enabled && isInstalled(dir) ? dir : null;
    }

    /** Downloads, verifies and installs Cisco's build into {@code dir}. */
    public static void install(Path dir, Asset asset) throws IOException {
        install(dir, asset, asset.uri());
    }

    /** As {@link #install(Path, Asset)} from an explicit source (a {@code file:} URI in tests). */
    static void install(Path dir, Asset asset, URI source) throws IOException {
        byte[] compressed = download(source);
        String actual = sha256(compressed);
        if (!actual.equalsIgnoreCase(asset.sha256())) {
            throw new IOException("The downloaded codec does not match the expected checksum and was discarded.");
        }
        byte[] library = decompress(compressed);
        if (library.length < 4 || library[0] != 0x7F || library[1] != 'E' || library[2] != 'L' || library[3] != 'F') {
            throw new IOException("The downloaded codec is not a shared library.");
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".download-", ".part");
        try {
            Files.write(tmp, library);
            try {
                Files.move(tmp, dir.resolve(LIBRARY_FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dir.resolve(LIBRARY_FILE), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Removes the downloaded codec (switching H.264 off). */
    public static void remove(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(LIBRARY_FILE));
    }

    private static byte[] download(URI source) throws IOException {
        URLConnection c = source.toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream()) {
            return readCapped(in, MAX_DOWNLOAD_BYTES, "The codec download is larger than expected.");
        }
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        try (InputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(compressed))) {
            return readCapped(in, MAX_LIBRARY_BYTES, "The codec is larger than expected after unpacking.");
        }
    }

    private static byte[] readCapped(InputStream in, int cap, String tooBig) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > cap) {
                throw new IOException(tooBig);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE has SHA-256
        }
    }
}
