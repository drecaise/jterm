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
package com.katmoda.jterm.terminal.rdp.drive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Locale;

/**
 * Marks a file the remote computer wrote as downloaded, so the operating system warns before it is
 * run or opened as trusted: on Windows a {@code Zone.Identifier} stream saying "Internet zone" (the
 * mark of the web; Office then opens the file in Protected View), on macOS the
 * {@code com.apple.quarantine} attribute that Gatekeeper checks. Linux has no such mechanism, so
 * nothing is written there.
 *
 * <p>Best effort: a volume without streams or extended attributes (FAT, many network shares) simply
 * goes unmarked, and a failure never fails the write.</p>
 */
@FunctionalInterface
public interface OriginMark {

    void mark(Path file);

    OriginMark NONE = file -> { };

    static OriginMark forThisPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Platform::markWindows;
        }
        if (os.contains("mac")) {
            return Platform::markMac;
        }
        return NONE;
    }

    /** The implementations, kept out of the interface's public surface. */
    final class Platform {
        private static final Logger LOG = LoggerFactory.getLogger(OriginMark.class);

        private Platform() {
        }

        static void markWindows(Path file) {
            try {
                // ZoneId 3 is the Internet zone. The stream is named after the file, so it can only
                // attach to the file just checked (the name passed the share's checks, no ':').
                // Through java.io, not java.nio: a Path refuses any ':' after the drive letter
                // (InvalidPathException), so the NIO form of this never wrote a single mark, and the
                // catch below hid it. java.io opens a stream name as long as jdk.io.File.enableADS
                // is on, which is the default.
                try (OutputStream out = new FileOutputStream(file.toString() + ":Zone.Identifier")) {
                    out.write("[ZoneTransfer]\r\nZoneId=3\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            } catch (Exception e) {
                LOG.debug("could not mark {} as downloaded", file, e);
            }
        }

        static void markMac(Path file) {
            try {
                UserDefinedFileAttributeView view = Files.getFileAttributeView(file,
                        UserDefinedFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    return;
                }
                // flags;hex seconds;agent;uuid. 0081: "downloaded", not yet approved by the user.
                String value = String.format(Locale.ROOT, "0081;%08x;jterm;", System.currentTimeMillis() / 1000);
                view.write("com.apple.quarantine", ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                LOG.debug("could not mark {} as downloaded", file, e);
            }
        }
    }
}
