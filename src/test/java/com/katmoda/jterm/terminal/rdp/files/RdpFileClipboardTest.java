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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RdpFileClipboardTest {

    @TempDir
    Path dir;

    private static RdpProtocol.FileRequest request(Integer clipDataId) {
        return new RdpProtocol.FileRequest(1, 0, RdpProtocol.FILECONTENTS_SIZE, 0, 8,
                clipDataId != null, clipDataId != null ? clipDataId : 0);
    }

    /** A copy of {@code name}, with its snapshot built and the user's answer given. */
    private RdpFileClipboard.Copy allowedCopy(RdpFileClipboard c, String name, boolean allow) throws Exception {
        Path f = Files.writeString(dir.resolve(name), name);
        c.announced(List.of(f));
        RdpFileClipboard.Copy copy = c.copyFor(List.of(f));
        copy.setSnapshot(FileSnapshot.build(List.of(f), List.of()));
        copy.decide(allow);
        return copy;
    }

    @Test
    void nothingIsReadableUntilTheUserAllowsIt() throws Exception {
        RdpFileClipboard c = new RdpFileClipboard();
        assertNull(c.snapshotFor(request(null)), "no copy at all");

        Path f = Files.writeString(dir.resolve("a.txt"), "a");
        c.announced(List.of(f));
        RdpFileClipboard.Copy copy = c.copyFor(List.of(f));
        copy.setSnapshot(FileSnapshot.build(List.of(f), List.of()));
        assertNull(c.snapshotFor(request(null)), "not asked yet");
        copy.decide(false);
        assertNull(c.snapshotFor(request(null)), "refused");
        copy.decide(true);
        assertSame(copy.snapshot(), c.snapshotFor(request(null)));
    }

    @Test
    void eachAnnouncementIsANewCopyWithANewQuestion() throws Exception {
        RdpFileClipboard c = new RdpFileClipboard();
        RdpFileClipboard.Copy first = allowedCopy(c, "a.txt", true);
        assertSame(first, c.copyFor(first.roots()), "the same paste of the same copy");

        c.announced(first.roots()); // the same files announced again: asked again
        RdpFileClipboard.Copy again = c.copyFor(first.roots());
        assertNotSame(first, again);
        assertNull(again.allowed());

        c.announced(null);
        assertNull(c.snapshotFor(request(null)), "nothing copied any more");
    }

    @Test
    void aLockKeepsReadingTheCopyItPinned() throws Exception {
        RdpFileClipboard c = new RdpFileClipboard();
        RdpFileClipboard.Copy first = allowedCopy(c, "a.txt", true);
        c.lock(7);
        RdpFileClipboard.Copy second = allowedCopy(c, "b.txt", true); // copied something else locally

        assertSame(first.snapshot(), c.snapshotFor(request(7)));
        assertSame(second.snapshot(), c.snapshotFor(request(null)));
        assertNull(c.snapshotFor(request(8)), "an id that was never locked reads nothing");

        c.unlock(7);
        assertNull(c.snapshotFor(request(7)));
    }

    @Test
    void aLockOnNothingPinsNothing() {
        RdpFileClipboard c = new RdpFileClipboard();
        c.lock(1);
        assertEquals(0, c.lockCount());
    }

    @Test
    void locksAreCappedOldestFirst() throws Exception {
        RdpFileClipboard c = new RdpFileClipboard();
        RdpFileClipboard.Copy copy = allowedCopy(c, "a.txt", true);
        for (int id = 0; id < RdpFileClipboard.MAX_LOCKS + 5; id++) {
            c.lock(id);
        }
        assertEquals(RdpFileClipboard.MAX_LOCKS, c.lockCount());
        assertNull(c.snapshotFor(request(0)), "the oldest went first");
        assertSame(copy.snapshot(), c.snapshotFor(request(RdpFileClipboard.MAX_LOCKS + 4)));

        c.clear();
        assertEquals(0, c.lockCount());
        assertNull(c.snapshotFor(request(null)));
    }
}
