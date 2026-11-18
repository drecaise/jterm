package com.katmoda.jterm.terminal.rdp.print;

import java.util.ArrayList;
import java.util.List;

/**
 * The print jobs waiting in one pane, across its reconnects (R4): never more than
 * {@link #MAX_PENDING_JOBS} jobs or {@link #MAX_PENDING_BYTES} in total, so a server can't fill the
 * disk by printing and never printing out. Jobs leave through {@link #remove} (printed, saved or
 * discarded), and {@link #clear} deletes every file when the pane closes. Thread-safe; the listener
 * runs on the thread that changed the queue.
 */
public final class PrintJobQueue {

    /** Largest single job. */
    public static final long MAX_JOB_BYTES = 256L << 20;
    /** Most jobs waiting at once. */
    public static final int MAX_PENDING_JOBS = 8;
    /** Most bytes waiting at once, the job being received included. */
    public static final long MAX_PENDING_BYTES = 1L << 30;

    /** Told when a job arrives or leaves. */
    public interface Listener {
        void jobAdded(PrintJob job);

        void changed();
    }

    private final List<PrintJob> jobs = new ArrayList<>();
    private final Listener listener;
    private final long maxJobBytes;
    private final int maxPendingJobs;
    private final long maxPendingBytes;
    private boolean closed;

    public PrintJobQueue(Listener listener) {
        this(listener, MAX_JOB_BYTES, MAX_PENDING_JOBS, MAX_PENDING_BYTES);
    }

    PrintJobQueue(Listener listener, long maxJobBytes, int maxPendingJobs, long maxPendingBytes) {
        this.listener = listener;
        this.maxJobBytes = maxJobBytes;
        this.maxPendingJobs = maxPendingJobs;
        this.maxPendingBytes = maxPendingBytes;
    }

    /** Whether a new job may start: fewer than the most waiting jobs, and the queue is open. */
    public synchronized boolean mayStartJob() {
        return !closed && jobs.size() < maxPendingJobs;
    }

    /** Whether a job being received may grow to {@code jobBytes}. */
    public synchronized boolean mayHold(long jobBytes) {
        return !closed && jobBytes <= maxJobBytes && pendingBytes() + jobBytes <= maxPendingBytes;
    }

    /** Whether {@code jobBytes} alone is over the single-job cap (for the refusal's wording). */
    synchronized boolean tooLargeForOneJob(long jobBytes) {
        return jobBytes > maxJobBytes;
    }

    private long pendingBytes() {
        long sum = 0;
        for (PrintJob j : jobs) {
            sum += j.bytes();
        }
        return sum;
    }

    /** Adds a complete job; false (and the file deleted) when the queue was closed meanwhile. */
    boolean add(PrintJob job) {
        synchronized (this) {
            if (closed) {
                PrintJobFolder.deleteQuietly(job.file());
                return false;
            }
            jobs.add(job);
        }
        listener.jobAdded(job);
        return true;
    }

    /** Takes a job out and deletes its file. */
    public void remove(PrintJob job) {
        boolean removed;
        synchronized (this) {
            removed = jobs.remove(job);
        }
        PrintJobFolder.deleteQuietly(job.file());
        if (removed) {
            listener.changed();
        }
    }

    public synchronized List<PrintJob> jobs() {
        return List.copyOf(jobs);
    }

    public synchronized int size() {
        return jobs.size();
    }

    /** Deletes every waiting job and refuses new ones. Idempotent. */
    public void clear() {
        List<PrintJob> gone;
        synchronized (this) {
            closed = true;
            gone = List.copyOf(jobs);
            jobs.clear();
        }
        for (PrintJob j : gone) {
            PrintJobFolder.deleteQuietly(j.file());
        }
        if (!gone.isEmpty()) {
            listener.changed();
        }
    }
}
