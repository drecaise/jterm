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

import com.katmoda.jterm.config.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Decides <em>when</em> the app asks GitHub about a new release, and hands any find to the UI.
 *
 * <p>The whole point of this class is that thousands of installs must not converge on one
 * instant at {@code api.github.com}. Two independent sources of jitter prevent that:</p>
 * <ul>
 *   <li>A check that is due fires after a random <b>30–180 s</b> spread rather than immediately.
 *       That both smooths the "everyone opens a terminal at 09:00" burst and keeps startup
 *       responsive — nothing competes with the first frame being painted.</li>
 *   <li>Each subsequent period is a fresh random draw in <b>20–28 h</b> rather than a fixed 24 h,
 *       so an instance left running for weeks drifts instead of locking onto one time of day.</li>
 * </ul>
 *
 * <p>A persisted last-attempt timestamp ({@code AppSettings.lastUpdateCheckEpochSeconds}) makes
 * the throttle survive restarts, so launching the app ten times in a morning still produces at
 * most one request. The timestamp is recorded whether the attempt succeeded or failed, which is
 * what makes a rate-limited or offline client back off for a day instead of retrying in a loop.
 * </p>
 *
 * <p>Scheduled checks are <b>silent on failure</b> — a laptop with no network must never greet
 * the user with an error dialog. Only the explicit Help menu check reports problems.</p>
 */
public final class UpdateScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateScheduler.class);

    /** Bounds of the post-startup spread, in seconds. */
    static final long MIN_SPREAD_SECONDS = 30;
    static final long MAX_SPREAD_SECONDS = 180;

    /** Bounds of the recurring period: 24 h ± 4 h, in seconds. */
    static final long MIN_PERIOD_SECONDS = 20 * 3600L;
    static final long MAX_PERIOD_SECONDS = 28 * 3600L;

    /** How long to idle between look-ins while the user has the check switched off. */
    static final long DISABLED_RECHECK_SECONDS = 3600;

    private final String currentVersion;
    private final Consumer<ReleaseInfo> onUpdateFound;
    private final RandomGenerator random = RandomGenerator.getDefault();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private volatile boolean stopped;

    /**
     * @param currentVersion the running build's version
     * @param onUpdateFound  invoked on the EDT with a release strictly newer than
     *                       {@code currentVersion} that the user has not skipped
     */
    public UpdateScheduler(String currentVersion, Consumer<ReleaseInfo> onUpdateFound) {
        this.currentVersion = currentVersion;
        this.onUpdateFound = onUpdateFound;
    }

    /**
     * Arms the checker. A no-op only when the running version is unparsable.
     *
     * <p>Deliberately armed even when the user has opted out, so that switching the setting back
     * on in Preferences takes effect without a restart. Being armed costs one idle daemon thread
     * that wakes hourly to re-read the flag — while the check is off it makes no network call at
     * all, which is the guarantee that actually matters.</p>
     */
    public synchronized void start() {
        if (executor != null || stopped) {
            return;
        }
        if (AppVersion.parse(currentVersion).isEmpty()) {
            // Running against unfiltered classes: AppInfo reports "(dev)" and there is nothing
            // meaningful to compare against, so stay quiet rather than nag on every launch.
            LOG.debug("update check: version '{}' is not comparable; skipping", currentVersion);
            return;
        }
        // Daemon, so an armed check can never hold the JVM open after the window closes —
        // matching the shared executors in HighlightingInstaller and SshSession.
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jterm-update-check");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
    }

    /** Cancels any armed check and shuts the thread down. Safe to call more than once. */
    public synchronized void stop() {
        stopped = true;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Computes the delay before the next attempt.
     *
     * <p>Pure and package-visible so the anti-herd behaviour can be unit-tested with a pinned
     * {@link RandomGenerator}. Serves both the initial schedule (where {@code lastCheck} comes
     * from settings) and every reschedule (where it is the attempt just recorded, making
     * {@code elapsed} zero and the result a full jittered period).</p>
     */
    static long nextDelaySeconds(long lastCheck, long now, RandomGenerator rnd) {
        long spread = rnd.nextLong(MIN_SPREAD_SECONDS, MAX_SPREAD_SECONDS + 1);
        if (lastCheck <= 0) {
            return spread;
        }
        long elapsed = now - lastCheck;
        if (elapsed < 0) {
            // Clock moved backwards, or a hand-edited/corrupt timestamp sits in the future.
            // Treat it as never checked rather than letting it wedge the check forever.
            return spread;
        }
        long remaining = rnd.nextLong(MIN_PERIOD_SECONDS, MAX_PERIOD_SECONDS + 1) - elapsed;
        return remaining <= 0 ? spread : remaining;
    }

    private void scheduleNext() {
        scheduleIn(nextDelaySeconds(AppSettings.get().getLastUpdateCheckEpochSeconds(),
                Instant.now().getEpochSecond(), random));
    }

    private synchronized void scheduleIn(long delaySeconds) {
        if (stopped || executor == null) {
            return;
        }
        LOG.debug("update check: next attempt in {} s", delaySeconds);
        pending = executor.schedule(this::run, delaySeconds, TimeUnit.SECONDS);
    }

    private void run() {
        if (stopped) {
            return;
        }
        // Re-read rather than caching, so both directions of the Preferences toggle take effect
        // without a restart. While off: no request is made, and the attempt is not stamped (none
        // happened) — so the next look-in uses the fixed idle interval rather than the throttle.
        if (!AppSettings.get().isUpdateCheckEnabled()) {
            LOG.debug("update check: switched off; idling for {} s", DISABLED_RECHECK_SECONDS);
            scheduleIn(DISABLED_RECHECK_SECONDS);
            return;
        }
        try {
            Optional<ReleaseInfo> release = UpdateChecker.fetchLatest(currentVersion);
            release.ifPresent(this::deliverIfNewer);
        } catch (IOException e) {
            LOG.debug("update check failed", e);
        } catch (RuntimeException e) {
            LOG.debug("update check failed unexpectedly", e);
        } finally {
            recordAttempt();
            scheduleNext();
        }
    }

    private void deliverIfNewer(ReleaseInfo release) {
        if (!AppVersion.isUpgrade(currentVersion, release.tagName())) {
            LOG.debug("update check: {} is not newer than {}", release.tagName(), currentVersion);
            return;
        }
        if (release.tagName().equals(AppSettings.get().getSkippedUpdateVersion())) {
            LOG.debug("update check: {} was skipped by the user", release.tagName());
            return;
        }
        SwingUtilities.invokeLater(() -> onUpdateFound.accept(release));
    }

    /**
     * Stamps this attempt so the throttle survives a restart.
     *
     * <p>The field is set here (a plain long, and {@link #scheduleNext} reads it immediately),
     * but the write to disk is marshalled onto the EDT: {@code AppSettings.save()} rewrites the
     * whole file, and letting this thread race the EDT over it could drop a setting the user
     * just changed.</p>
     */
    private static void recordAttempt() {
        AppSettings settings = AppSettings.get();
        settings.setLastUpdateCheckEpochSeconds(Instant.now().getEpochSecond());
        SwingUtilities.invokeLater(settings::save);
    }
}
