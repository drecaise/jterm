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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>The helper is given the library's full path ({@link #libraryPath}) and loads it before FreeRDP
 * looks; FreeRDP opens the codec by bare name ({@code libopenh264.so.8}, {@code .so.7}, …), and a
 * by-name open returns the already-loaded library with that SONAME, so no library search path is
 * involved. The file is stored under the first of those names. Windows and macOS need none of this: the helper uses the system's decoders there.
 * The Flatpak downloads it like every other Linux package: the freedesktop 25.08 runtime has no
 * OpenH264 extension any more (24.08 had one), and the settings folder is inside its sandbox.</p>
 *
 * <p>The network and the file system are the only inputs, and both are untrusted: the download is
 * size-capped, hashed before anything reads it, and the decompressed size is capped too.</p>
 */
public final class OpenH264 {

    /** Shown wherever the user controls the codec, and in the license information (Cisco's condition 3/4). */
    public static final String NOTICE = "OpenH264 Video Codec provided by Cisco Systems, Inc.";

    public static final String VERSION = "2.7.0";

    /** The file name FreeRDP's loader tries first for this release (the library's soname). */
    static final String LIBRARY_FILE = "libopenh264.so.8";

    /** Beside the library: the pinned SHA-256 it was verified against (see {@link #isCurrent}). */
    static final String PIN_FILE = LIBRARY_FILE + ".sha256";

    private static final Logger LOG = LoggerFactory.getLogger(OpenH264.class);
    private static final AtomicBoolean UPDATING = new AtomicBoolean();

    private static final String BASE_URL = "https://ciscobinary.openh264.org/";
    private static final int MAX_DOWNLOAD_BYTES = 8 << 20;  // the real file is about 0.6 MiB
    private static final int MAX_LIBRARY_BYTES = 32 << 20;
    private static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(2);

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
                    "44ad0dd2c5af2bac84d26f89f2eb4e268708b8739323d15d33837f7221ce4980");
            case "aarch64", "arm64" -> new Asset("libopenh264-" + VERSION + "-linux-arm64.8.so.bz2",
                    "74c7a1d02003d80daf2b4bc7bbb02f73e5548debd4c158b495e1f83d80ebd136");
            default -> null;
        };
    }

    /** The build to download on this machine, or {@code null} when jterm cannot offer a download. */
    public static Asset assetForThisMachine() {
        return assetFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Where the downloaded codec lives: inside the config directory, so it is per user. */
    public static Path defaultDir() {
        return AppPaths.configDir().resolve("codecs").resolve("openh264");
    }

    /**
     * Whether {@code dir} holds the build {@code asset} pins. A library is stamped at install with
     * the pinned hash it was verified against ({@link #PIN_FILE}); one from an older pin, or with no
     * stamp, is not current. Every OpenH264 release uses the same file name, so the name alone cannot
     * tell a codec with a known vulnerability from its fix.
     */
    public static boolean isCurrent(Path dir, Asset asset) {
        if (asset == null || !Files.isRegularFile(dir.resolve(LIBRARY_FILE))) {
            return false;
        }
        try {
            return Files.readString(dir.resolve(PIN_FILE)).strip().equalsIgnoreCase(asset.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The library for the helper to load, or {@code null} when the codec is switched
     * off, not downloaded, or from an older pin (the helper then simply does not offer H.264 to the
     * server). An outdated build is deliberately not loaded: a pin moves when Cisco fixes something,
     * and the decoder parses video the remote server controls. {@link #updateInBackground} replaces it.
     */
    public static Path libraryPath(boolean enabled, Path dir, Asset asset) {
        return enabled && isCurrent(dir, asset) ? dir.resolve(LIBRARY_FILE).toAbsolutePath() : null;
    }

    /**
     * When the codec is enabled but missing or from an older pin (jterm was updated since it was
     * downloaded), fetches the current build on a background thread. The user already opted in to
     * this download; a new jterm only changes which verified build it is. At most one runs at a time,
     * and a failure (offline, say) is logged and retried at the next call: at startup and at each
     * connect. Until then sessions run without H.264.
     */
    public static void updateInBackground(boolean enabled, Path dir, Asset asset) {
        if (!enabled || asset == null || isCurrent(dir, asset) || !UPDATING.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                install(dir, asset);
                LOG.info("Updated the OpenH264 codec to {}", VERSION);
            } catch (IOException | RuntimeException e) {
                LOG.debug("Could not update the OpenH264 codec; retrying later", e);
            } finally {
                UPDATING.set(false);
            }
        }, "openh264-update");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Abandons a running {@link #install}, from any thread: it fails with
     * {@link InterruptedIOException} and writes nothing, checked between reads and again before the
     * first file is touched. It cannot cut short a read already blocked on the network (measured:
     * {@code HttpURLConnection.disconnect()} from another thread does not unblock it), so a caller
     * that must not wait — a dialog — stops waiting itself; the abandoned download ends within one
     * read timeout.
     */
    public static final class Cancel {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Downloads, verifies and installs Cisco's build into {@code dir}. */
    public static void install(Path dir, Asset asset) throws IOException {
        install(dir, asset, new Cancel());
    }

    /** As {@link #install(Path, Asset)}, stoppable through {@code cancel}. */
    public static void install(Path dir, Asset asset, Cancel cancel) throws IOException {
        install(dir, asset, asset.uri(), cancel);
    }

    /** As {@link #install(Path, Asset)} from an explicit source (a {@code file:} URI in tests). */
    static void install(Path dir, Asset asset, URI source) throws IOException {
        install(dir, asset, source, new Cancel());
    }

    static void install(Path dir, Asset asset, URI source, Cancel cancel) throws IOException {
        byte[] compressed = download(source, cancel);
        String actual = sha256(compressed);
        if (!actual.equalsIgnoreCase(asset.sha256())) {
            throw new IOException("The downloaded codec does not match the expected checksum and was discarded.");
        }
        byte[] library = decompress(compressed);
        if (library.length < 4 || library[0] != 0x7F || library[1] != 'E' || library[2] != 'L' || library[3] != 'F') {
            throw new IOException("The downloaded codec is not a shared library.");
        }
        if (cancel.isCancelled()) {
            throw new InterruptedIOException("The codec download was cancelled.");
        }
        Files.createDirectories(dir);
        // Stamp last, and unstamp first: interrupted anywhere in between, the library reads as
        // outdated and is fetched again, never as current with the wrong bytes.
        Files.deleteIfExists(dir.resolve(PIN_FILE));
        replace(dir, LIBRARY_FILE, library);
        replace(dir, PIN_FILE, (asset.sha256().toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    /** Writes {@code dir/name} through a temporary file, so a reader never sees half of it. */
    private static void replace(Path dir, String name, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(dir, ".download-", ".part");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Removes the downloaded codec (switching H.264 off). */
    public static void remove(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(PIN_FILE));
        Files.deleteIfExists(dir.resolve(LIBRARY_FILE));
    }

    /**
     * The file at {@code source}, capped in size and in total time. The read timeout alone bounds
     * only each read, so a server trickling a byte at a time could hold a download open for hours;
     * the deadline covers the whole transfer (the real file takes a second or two).
     */
    private static byte[] download(URI source, Cancel cancel) throws IOException {
        URLConnection c = source.toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        long deadline = System.nanoTime() + DOWNLOAD_DEADLINE.toNanos();
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while (!cancel.isCancelled() && (n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("The codec download is larger than expected.");
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new IOException("The codec download took too long.");
                }
                out.write(buf, 0, n);
            }
            if (cancel.isCancelled()) {
                throw new InterruptedIOException("The codec download was cancelled.");
            }
            return out.toByteArray();
        } catch (IOException e) {
            if (cancel.isCancelled() && !(e instanceof InterruptedIOException)) {
                throw new InterruptedIOException("The codec download was cancelled.");
            }
            throw e;
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
