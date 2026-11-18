package com.katmoda.jterm.terminal.rdp.print;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoPrintLimiterTest {

    @Test
    void aJobOverThePageCapWaits() {
        AutoPrintLimiter l = new AutoPrintLimiter();
        assertEquals(AutoPrintLimiter.Verdict.OK, l.tryAcquire(AutoPrintLimiter.MAX_PAGES, 0));
        assertEquals(AutoPrintLimiter.Verdict.TOO_MANY_PAGES, l.tryAcquire(AutoPrintLimiter.MAX_PAGES + 1, 0));
        assertEquals(AutoPrintLimiter.Verdict.TOO_MANY_PAGES, l.tryAcquire(0, 0), "an unknown page count never prints by itself");
    }

    @Test
    void moreThanTenJobsInTenMinutesWait_thenTheWindowMoves() {
        AutoPrintLimiter l = new AutoPrintLimiter();
        for (int i = 0; i < AutoPrintLimiter.MAX_JOBS; i++) {
            assertEquals(AutoPrintLimiter.Verdict.OK, l.tryAcquire(1, i * 1000L));
        }
        assertEquals(AutoPrintLimiter.Verdict.TOO_MANY_JOBS, l.tryAcquire(1, 60_000));
        // A refused job doesn't count, so the first one leaving the window frees exactly one place.
        assertEquals(AutoPrintLimiter.Verdict.OK, l.tryAcquire(1, AutoPrintLimiter.WINDOW_MILLIS));
        assertEquals(AutoPrintLimiter.Verdict.TOO_MANY_JOBS, l.tryAcquire(1, AutoPrintLimiter.WINDOW_MILLIS));
    }

    @Test
    void aPageCapRefusalDoesNotUseUpTheWindow() {
        AutoPrintLimiter l = new AutoPrintLimiter();
        for (int i = 0; i < 20; i++) {
            l.tryAcquire(1000, 0);
        }
        assertEquals(AutoPrintLimiter.Verdict.OK, l.tryAcquire(3, 0));
    }
}
