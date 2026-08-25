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
package com.katmoda.jterm.update;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static com.katmoda.jterm.update.UpdateScheduler.MAX_PERIOD_SECONDS;
import static com.katmoda.jterm.update.UpdateScheduler.MAX_SPREAD_SECONDS;
import static com.katmoda.jterm.update.UpdateScheduler.MIN_PERIOD_SECONDS;
import static com.katmoda.jterm.update.UpdateScheduler.MIN_SPREAD_SECONDS;
import static com.katmoda.jterm.update.UpdateScheduler.nextDelaySeconds;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The anti-herd scheduling logic, exercised with a pinned RNG. */
class UpdateSchedulerTest {

    private static final long NOW = 1_800_000_000L;
    private static final long HOUR = 3600L;

    private static RandomGenerator rng() {
        return new Random(42);
    }

    @Test
    void firstEverCheckLandsInTheStartupSpread() {
        RandomGenerator rnd = rng();
        for (int i = 0; i < 50; i++) {
            assertSpread(nextDelaySeconds(0, NOW, rnd));
        }
    }

    @Test
    void aCheckJustMadeDefersRoughlyADay() {
        RandomGenerator rnd = rng();
        for (int i = 0; i < 50; i++) {
            long delay = nextDelaySeconds(NOW - 60, NOW, rnd);
            assertTrue(delay >= MIN_PERIOD_SECONDS - 60 && delay <= MAX_PERIOD_SECONDS - 60,
                    "expected roughly a day, got " + delay);
        }
    }

    @Test
    void aCheckOlderThanTheLongestPeriodIsDueNow() {
        RandomGenerator rnd = rng();
        for (int i = 0; i < 50; i++) {
            assertSpread(nextDelaySeconds(NOW - 29 * HOUR, NOW, rnd));
        }
    }

    @Test
    void aCheckTwentyFiveHoursOldIsDueWithinThreeHours() {
        // 25 h sits inside the 20–28 h jitter band, so it is either due now (the spread) or
        // moments away — never another full day.
        RandomGenerator rnd = rng();
        for (int i = 0; i < 50; i++) {
            long delay = nextDelaySeconds(NOW - 25 * HOUR, NOW, rnd);
            assertTrue(delay <= 3 * HOUR, "expected at most 3 h, got " + delay);
        }
    }

    @Test
    void aTimestampInTheFutureIsTreatedAsNeverChecked() {
        // A clock that moved backwards, or a hand-edited settings.json, must not wedge the check.
        RandomGenerator rnd = rng();
        assertSpread(nextDelaySeconds(NOW + 10 * HOUR, NOW, rnd));
        assertSpread(nextDelaySeconds(Long.MAX_VALUE / 2, NOW, rnd));
    }

    @Test
    void consecutivePeriodsAreJitteredRatherThanFixed() {
        RandomGenerator rnd = rng();
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long delay = nextDelaySeconds(NOW, NOW, rnd);
            assertTrue(delay >= MIN_PERIOD_SECONDS && delay <= MAX_PERIOD_SECONDS,
                    "period out of the 20–28 h band: " + delay);
            delays.add(delay);
        }
        // If the period were fixed, every draw would be identical and instances would converge.
        assertTrue(delays.size() > 50, "expected jittered periods, got " + delays.size() + " distinct");
    }

    @Test
    void everyDelayIsPositive() {
        RandomGenerator rnd = rng();
        long[] lastChecks = {0, NOW, NOW - 1, NOW - HOUR, NOW - 24 * HOUR, NOW - 365 * 24 * HOUR};
        for (long lastCheck : lastChecks) {
            for (int i = 0; i < 20; i++) {
                assertTrue(nextDelaySeconds(lastCheck, NOW, rnd) > 0);
            }
        }
    }

    private static void assertSpread(long delay) {
        assertTrue(delay >= MIN_SPREAD_SECONDS && delay <= MAX_SPREAD_SECONDS,
                "expected the 30–180 s startup spread, got " + delay);
    }
}
