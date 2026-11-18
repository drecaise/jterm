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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs drive requests off the helper's reader thread. Requests for the same open file run in the
 * order they arrived (a write followed by a close must not be reordered); different files run in
 * parallel on a small pool, so a long read never stalls a directory listing. A CREATE has no file
 * yet and runs on its own. The helper caps pending requests (256), so queues are bounded.
 */
public final class DriveDispatcher implements AutoCloseable {

    /** Where answers go: the connection's {@code completeDriveIrp}. */
    @FunctionalInterface
    public interface Sink {
        void complete(int completionId, int ioStatus, byte[] output);
    }

    private static final int THREADS = 4;

    private final RdpDriveService service;
    private final Sink sink;
    private final ExecutorService pool;
    private final Map<Long, ArrayDeque<Runnable>> queues = new HashMap<>();

    public DriveDispatcher(RdpDriveService service, Sink sink) {
        this.service = service;
        this.sink = sink;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "rdp-drive-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Queues one request; its answer goes to the sink. */
    public void submit(int driveId, int fileId, int completionId, int major, int minor, byte[] input) {
        Runnable task = () -> {
            RdpDriveService.Reply r = service.handle(driveId, fileId, major, minor, input);
            sink.complete(completionId, r.ioStatus(), r.output());
        };
        if (major == RdpDriveService.IRP_MJ_CREATE) {
            execute(task);
            return;
        }
        long key = ((long) driveId << 32) | (fileId & 0xFFFFFFFFL);
        synchronized (queues) {
            ArrayDeque<Runnable> q = queues.get(key);
            if (q != null) {
                q.add(task); // the running task for this file picks it up
                return;
            }
            queues.put(key, new ArrayDeque<>());
        }
        execute(() -> runInOrder(key, task));
    }

    private void runInOrder(long key, Runnable first) {
        Runnable next = first;
        while (next != null) {
            try {
                next.run();
            } catch (RuntimeException ignored) {
                // handle() never throws; a failing sink must not wedge the file's queue
            }
            synchronized (queues) {
                ArrayDeque<Runnable> q = queues.get(key);
                next = q == null ? null : q.poll();
                if (next == null) {
                    queues.remove(key);
                }
            }
        }
    }

    private void execute(Runnable r) {
        try {
            pool.execute(r);
        } catch (RejectedExecutionException closed) {
            // shutting down: the helper discards pending requests with the channel
        }
    }

    /** Stops taking requests and closes the service's handles once running ones finish. */
    @Override
    public void close() {
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        service.close();
    }
}
