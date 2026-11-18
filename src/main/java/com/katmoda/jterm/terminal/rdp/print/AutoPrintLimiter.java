package com.katmoda.jterm.terminal.rdp.print;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The caps on a session's "print without asking" (printer design D3): at most {@link #MAX_PAGES}
 * pages a job, and {@link #MAX_JOBS} jobs within any {@link #WINDOW_MILLIS}. A job over either waits
 * in the bar like any other, so a trusted server prints straight away and a hostile one can't empty
 * the paper tray. One per connection attempt. Pure; the caller passes the time.
 */
public final class AutoPrintLimiter {

    public static final int MAX_PAGES = 50;
    public static final int MAX_JOBS = 10;
    public static final long WINDOW_MILLIS = 10 * 60_000L;

    /** Why a job didn't print by itself, or null when it may. */
    public enum Verdict {
        OK(null),
        TOO_MANY_PAGES("it has more than " + MAX_PAGES + " pages"),
        TOO_MANY_JOBS("more than " + MAX_JOBS + " jobs came within 10 minutes");

        private final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }

        /** Words for the bar, or null for OK. */
        public String reason() {
            return reason;
        }
    }

    private final Deque<Long> printed = new ArrayDeque<>();

    /** Decides for a job of {@code pages}; an OK counts against the window. */
    public synchronized Verdict tryAcquire(int pages, long nowMillis) {
        if (pages < 1 || pages > MAX_PAGES) {
            return Verdict.TOO_MANY_PAGES;
        }
        while (!printed.isEmpty() && nowMillis - printed.peekFirst() >= WINDOW_MILLIS) {
            printed.pollFirst();
        }
        if (printed.size() >= MAX_JOBS) {
            return Verdict.TOO_MANY_JOBS;
        }
        printed.addLast(nowMillis);
        return Verdict.OK;
    }
}
