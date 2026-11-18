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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteClipboardFilesTest {

    private record Fetch(int stream, int index, boolean haveId, int id) {
    }

    private final List<Fetch> fetches = new CopyOnWriteArrayList<>();
    private final List<String> locks = new CopyOnWriteArrayList<>();
    private final RemoteClipboardFiles files = new RemoteClipboardFiles(new RemoteClipboardFiles.Wire() {
        @Override
        public void fetch(int streamId, int listIndex, int flags, long position, int cb, boolean haveId, int id) {
            fetches.add(new Fetch(streamId, listIndex, haveId, id));
        }

        @Override
        public void lock(boolean lock, int id) {
            locks.add((lock ? "lock " : "unlock ") + id);
        }
    });

    private static RemoteFileList someList() throws Exception {
        return RemoteFileList.parse(RemoteFileListTest.list(new Object[]{"a.txt", false, 1}));
    }

    private static Throwable failure(CompletableFuture<byte[]> f) {
        return assertThrows(ExecutionException.class, f::get).getCause();
    }

    @Test
    void withLockingEachFileCopyIsLockedAndReadsCarryTheId() throws Exception {
        files.setCanLock(true);
        long gen = files.serverCopied(true);
        assertEquals(List.of("lock 1"), locks);
        RemoteClipboardFiles.Offer o = files.listArrived(gen, someList());
        assertEquals(1, o.lockId());

        CompletableFuture<byte[]> f = files.fetch(o, 0, RdpProtocol.FILECONTENTS_RANGE, 0, 1);
        assertEquals(new Fetch(1, 0, true, 1), fetches.get(0));
        files.dataArrived(1, true, new byte[]{7});
        assertArrayEquals(new byte[]{7}, f.get());
    }

    @Test
    void aLockedTransferOutlivesTheNextServerCopy() throws Exception {
        files.setCanLock(true);
        RemoteClipboardFiles.Offer o = files.listArrived(files.serverCopied(true), someList());
        files.begin(o);
        CompletableFuture<byte[]> inFlight = files.fetch(o, 0, RdpProtocol.FILECONTENTS_RANGE, 0, 1);

        files.serverCopied(false); // the user copies text on the remote desktop
        assertNull(files.current(), "the button goes");
        assertEquals(List.of("lock 1"), locks, "still in use: not released");
        files.dataArrived(fetches.get(0).stream(), true, new byte[]{1});
        assertArrayEquals(new byte[]{1}, inFlight.get());
        files.fetch(o, 0, RdpProtocol.FILECONTENTS_RANGE, 1, 1); // and further reads still go out
        assertEquals(2, fetches.size());

        files.end(o);
        assertEquals(List.of("lock 1", "unlock 1"), locks);
    }

    @Test
    void anUnusedLockIsReleasedByTheNextCopy() throws Exception {
        files.setCanLock(true);
        files.listArrived(files.serverCopied(true), someList());
        files.serverCopied(true);
        assertEquals(List.of("lock 1", "unlock 1", "lock 2"), locks);
    }

    @Test
    void withoutLockingANewCopyAbortsTheOldListsReads() throws Exception {
        RemoteClipboardFiles.Offer o = files.listArrived(files.serverCopied(true), someList());
        assertNull(o.lockId());
        assertTrue(locks.isEmpty());
        CompletableFuture<byte[]> inFlight = files.fetch(o, 0, RdpProtocol.FILECONTENTS_RANGE, 0, 1);
        assertTrue(!fetches.get(0).haveId());

        files.serverCopied(false);
        assertInstanceOf(RemoteClipboardFiles.ClipboardChangedException.class, failure(inFlight));
        assertInstanceOf(RemoteClipboardFiles.ClipboardChangedException.class,
                failure(files.fetch(o, 0, RdpProtocol.FILECONTENTS_RANGE, 1, 1)));
        assertEquals(1, fetches.size(), "nothing more was asked of the server");
    }

    @Test
    void aStaleListIsDroppedAndUnknownAnswersIgnored() throws Exception {
        long old = files.serverCopied(true);
        files.serverCopied(true);
        assertNull(files.listArrived(old, someList()));
        files.dataArrived(99, true, new byte[]{1}); // nothing asked: no harm
        assertEquals(0, files.pendingCount());
    }

    @Test
    void closingFailsEverythingInFlight() throws Exception {
        RemoteClipboardFiles.Offer o = files.listArrived(files.serverCopied(true), someList());
        CompletableFuture<byte[]> f = files.fetch(o, 0, RdpProtocol.FILECONTENTS_SIZE, 0, 8);
        files.close();
        assertNotNull(failure(f));
        assertNotNull(failure(files.fetch(o, 0, RdpProtocol.FILECONTENTS_SIZE, 0, 8)));
        assertEquals(1, fetches.size());
    }

    @Test
    void aRefusedReadFails() throws Exception {
        RemoteClipboardFiles.Offer o = files.listArrived(files.serverCopied(true), someList());
        CompletableFuture<byte[]> f = files.fetch(o, 0, RdpProtocol.FILECONTENTS_SIZE, 0, 8);
        files.dataArrived(fetches.get(0).stream(), false, null);
        assertNotNull(failure(f));
    }
}
