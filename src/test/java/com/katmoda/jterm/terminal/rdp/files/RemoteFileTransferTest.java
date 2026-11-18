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
package com.katmoda.jterm.terminal.rdp.files;

import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import com.katmoda.jterm.terminal.rdp.drive.OriginMark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileTransferTest {

    @TempDir
    Path tmp;

    /** A server whose files are byte arrays, by list index; records what was asked. */
    private static final class FakeServer implements RemoteFileTransfer.Reader {
        final Map<Integer, byte[]> files = new HashMap<>();
        final Map<Integer, Long> claimedSizes = new HashMap<>();
        final List<long[]> ranges = new CopyOnWriteArrayList<>();
        int shortAt = -1; // a range starting here comes back one byte short

        @Override
        public CompletableFuture<byte[]> fetch(int index, int flags, long position, int cb) {
            byte[] f = files.get(index);
            if (f == null) {
                return CompletableFuture.failedFuture(new IOException("no such file"));
            }
            if (flags == RdpProtocol.FILECONTENTS_SIZE) {
                long size = claimedSizes.getOrDefault(index, (long) f.length);
                return CompletableFuture.completedFuture(
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array());
            }
            ranges.add(new long[]{index, position, cb});
            int from = (int) Math.min(position, f.length);
            int to = (int) Math.min(position + cb, f.length);
            if (position == shortAt) {
                to--;
            }
            return CompletableFuture.completedFuture(Arrays.copyOfRange(f, from, to));
        }
    }

    private static final class Ui implements RemoteFileTransfer.Listener {
        RemoteFileTransfer.Decision onExisting = RemoteFileTransfer.Decision.SKIP;
        final List<Path> asked = new ArrayList<>();
        long lastDone;
        boolean cancel;

        @Override
        public RemoteFileTransfer.Decision exists(Path target) {
            asked.add(target);
            return onExisting;
        }

        @Override
        public void progress(String name, long done, long total) {
            lastDone = done;
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }
    }

    private final FakeServer server = new FakeServer();
    private final Ui ui = new Ui();
    private final List<Path> marked = new ArrayList<>();
    private final OriginMark mark = marked::add;

    private RemoteFileTransfer.Result run(Path dest, List<Path> protectedFolders, byte[] list) throws Exception {
        return new RemoteFileTransfer(dest, protectedFolders, server, ui, mark).run(RemoteFileList.parse(list));
    }

    private static byte[] list(Object[]... entries) {
        return RemoteFileListTest.list(entries);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31);
        }
        return b;
    }

    @Test
    void writesFoldersAndFilesIntactInChunksAndMarksThem() throws Exception {
        byte[] big = bytes(RemoteFileTransfer.CHUNK * 2 + 123);
        server.files.put(1, big);
        server.files.put(2, "hello".getBytes());
        Path dest = Files.createDirectories(tmp.resolve("dest"));

        RemoteFileTransfer.Result r = run(dest, List.of(), list(
                new Object[]{"photos", true, 0},
                new Object[]{"photos\\big.bin", false, big.length},
                new Object[]{"photos\\2026\\hello.txt", false, 5})); // its folder has no entry of its own

        assertEquals(2, r.written());
        assertTrue(r.failed().isEmpty(), r.failed().toString());
        assertArrayEquals(big, Files.readAllBytes(dest.resolve("photos/big.bin")));
        assertEquals("hello", Files.readString(dest.resolve("photos/2026/hello.txt")));
        assertEquals(3, server.ranges.stream().filter(x -> x[0] == 1).count(), "1 MiB at a time");
        assertEquals(big.length + 5, ui.lastDone);
        assertEquals(List.of(dest.toRealPath().resolve("photos/big.bin"), dest.toRealPath().resolve("photos/2026/hello.txt")),
                marked);
        try (var s = Files.list(dest.resolve("photos"))) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".part")), "no temporary file left");
        }
    }

    @Test
    void readsTheSmallerOfTheTwoSizes() throws Exception {
        server.files.put(0, bytes(100));
        server.claimedSizes.put(0, 1_000_000_000_000L); // the server's size request lies big
        Path dest = Files.createDirectories(tmp.resolve("d"));
        run(dest, List.of(), list(new Object[]{"a.bin", false, 10}));
        assertEquals(10, Files.size(dest.resolve("a.bin")));
    }

    @Test
    void aRangeThatComesBackShortFailsTheFileAndLeavesNothing() throws Exception {
        server.files.put(0, bytes(RemoteFileTransfer.CHUNK + 10));
        server.shortAt = RemoteFileTransfer.CHUNK;
        Path dest = Files.createDirectories(tmp.resolve("d"));
        RemoteFileTransfer.Result r = run(dest, List.of(), list(new Object[]{"a.bin", false, RemoteFileTransfer.CHUNK + 10}));
        assertEquals(0, r.written());
        assertEquals(1, r.failed().size());
        try (var s = Files.list(dest)) {
            assertEquals(0, s.count(), "neither the file nor its temporary copy");
        }
    }

    @Test
    void anExistingFileIsReplacedOnlyWhenTheUserSaysSo() throws Exception {
        server.files.put(0, "new".getBytes());
        Path dest = Files.createDirectories(tmp.resolve("d"));
        Files.writeString(dest.resolve("a.txt"), "old");

        RemoteFileTransfer.Result skipped = run(dest, List.of(), list(new Object[]{"a.txt", false, 3}));
        assertEquals("old", Files.readString(dest.resolve("a.txt")));
        assertEquals(List.of("a.txt"), skipped.skipped());

        ui.onExisting = RemoteFileTransfer.Decision.REPLACE;
        run(dest, List.of(), list(new Object[]{"a.txt", false, 3}));
        assertEquals("new", Files.readString(dest.resolve("a.txt")));

        ui.onExisting = RemoteFileTransfer.Decision.CANCEL;
        server.files.put(1, "b".getBytes());
        RemoteFileTransfer.Result cancelled = run(dest, List.of(), list(
                new Object[]{"a.txt", false, 3}, new Object[]{"b.txt", false, 1}));
        assertTrue(cancelled.cancelled());
        assertFalse(Files.exists(dest.resolve("b.txt")), "nothing after Cancel");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void neverWritesThroughALinkInTheDestination() throws Exception {
        server.files.put(1, "pwned".getBytes());
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path dest = Files.createDirectories(tmp.resolve("d"));
        Files.createSymbolicLink(dest.resolve("docs"), outside);

        RemoteFileTransfer.Result r = run(dest, List.of(), list(
                new Object[]{"docs", true, 0}, new Object[]{"docs\\x.txt", false, 5}));

        assertEquals(2, r.failed().size());
        try (var s = Files.list(outside)) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void neverWritesIntoProtectedFoldersEvenOnesThatDontExistYet() throws Exception {
        server.files.put(1, "ssh-ed25519 AAAA attacker".getBytes());
        server.files.put(3, "{}".getBytes());
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ssh = home.resolve(".ssh"); // not there yet
        Path vault = Files.createDirectories(home.resolve(".config/jterm"));

        RemoteFileTransfer.Result r = run(home, List.of(ssh, vault), list(
                new Object[]{".SSH", true, 0},
                new Object[]{".SSH\\authorized_keys", false, 25},
                new Object[]{".config", true, 0},
                new Object[]{".config\\jterm\\credentials.json", false, 2}));

        assertFalse(Files.exists(ssh));
        assertFalse(Files.exists(home.resolve(".SSH")));
        assertFalse(Files.exists(vault.resolve("credentials.json")));
        assertEquals(3, r.failed().size(), r.failed().toString());
        assertThrows(IOException.class, () -> new RemoteFileTransfer(vault, List.of(ssh, vault), server, ui, mark),
                "nor into a destination inside one");
    }

    @Test
    void aFolderWhereAFileBelongsFailsThatEntry() throws Exception {
        server.files.put(0, "x".getBytes());
        Path dest = Files.createDirectories(tmp.resolve("d"));
        Files.createDirectories(dest.resolve("a.txt"));
        RemoteFileTransfer.Result r = run(dest, List.of(), list(new Object[]{"a.txt", false, 1}));
        assertEquals(1, r.failed().size());
        assertTrue(ui.asked.isEmpty(), "a folder is never offered for replacement");
    }

    @Test
    void cancelStopsBetweenRangesAndCleansUp() throws Exception {
        server.files.put(0, bytes(RemoteFileTransfer.CHUNK * 8));
        Path dest = Files.createDirectories(tmp.resolve("d"));
        RemoteFileTransfer.Listener cancelling = new RemoteFileTransfer.Listener() {
            @Override
            public RemoteFileTransfer.Decision exists(Path target) {
                return RemoteFileTransfer.Decision.SKIP;
            }

            @Override
            public void progress(String name, long done, long total) {
                ui.cancel = true; // the user presses Cancel after the first range
            }

            @Override
            public boolean cancelled() {
                return ui.cancel;
            }
        };
        RemoteFileTransfer.Result r = new RemoteFileTransfer(dest, List.of(), server, cancelling, mark)
                .run(RemoteFileList.parse(list(new Object[]{"big.bin", false, RemoteFileTransfer.CHUNK * 8})));
        assertTrue(r.cancelled());
        try (var s = Files.list(dest)) {
            assertEquals(0, s.count());
        }
        assertTrue(server.ranges.size() <= RemoteFileTransfer.IN_FLIGHT + 1, "it stopped asking");
    }

    @Test
    void aServerThatStopsAnsweringFailsTheFile() throws Exception {
        RemoteFileTransfer.Reader silent = (i, f, p, cb) -> new CompletableFuture<>();
        Path dest = Files.createDirectories(tmp.resolve("d"));
        RemoteFileTransfer.Result r = new RemoteFileTransfer(dest, List.of(), silent, ui, mark, 1)
                .run(RemoteFileList.parse(list(new Object[]{"a", false, 1})));
        assertEquals(1, r.failed().size());
        assertTrue(r.failed().get(0).contains("stopped answering"));
    }
}
