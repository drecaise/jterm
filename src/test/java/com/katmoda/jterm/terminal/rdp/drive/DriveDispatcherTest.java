/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.rdp.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveDispatcherTest {

    @TempDir
    Path tmp;

    @Test
    void requestsForOneFileRunInOrderAndEveryOneIsAnswered() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "");
        RdpDriveService svc = new RdpDriveService(List.of(DriveShare.open(1, "s", tmp, false, List.of())),
                RdpDriveService.Limits.DEFAULT, OriginMark.NONE);
        Map<Integer, Integer> status = new ConcurrentHashMap<>();
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch created = new CountDownLatch(1);
        int[] fileId = new int[1];
        int writes = 200;
        CountDownLatch done = new CountDownLatch(writes + 1);
        try (DriveDispatcher d = new DriveDispatcher(svc, (completionId, io, out) -> {
            status.put(completionId, io);
            if (completionId == 0) {
                fileId[0] = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt();
                created.countDown();
            } else {
                order.add(completionId);
                done.countDown();
            }
        })) {
            byte[] p = "\\f.txt\0".getBytes(StandardCharsets.UTF_16LE);
            ByteBuffer c = ByteBuffer.allocate(32 + p.length).order(ByteOrder.LITTLE_ENDIAN);
            c.putInt(0x40000000).putLong(0).putInt(0).putInt(7).putInt(1).putInt(0).putInt(p.length).put(p);
            d.submit(1, 0, 0, RdpDriveService.IRP_MJ_CREATE, 0, c.array());
            assertTrue(created.await(5, TimeUnit.SECONDS));

            for (int i = 1; i <= writes; i++) {
                ByteBuffer w = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN);
                w.putInt(1).putLong(i - 1).put(new byte[20]).put((byte) ('a' + (i % 26)));
                d.submit(1, fileId[0], i, RdpDriveService.IRP_MJ_WRITE, 0, w.array());
            }
            d.submit(1, fileId[0], writes + 1, RdpDriveService.IRP_MJ_CLOSE, 0, new byte[32]);
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }
        for (int i = 1; i <= writes; i++) {
            assertEquals(i, order.get(i - 1), "same-file requests keep their order");
            assertEquals(NtStatus.SUCCESS, status.get(i));
        }
        assertEquals(writes, Files.size(tmp.resolve("f.txt")));
        assertEquals(0, svc.openHandles(), "closed, and the dispatcher's close closed the rest");
    }
}
