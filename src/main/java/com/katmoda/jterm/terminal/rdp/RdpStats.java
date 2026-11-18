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
package com.katmoda.jterm.terminal.rdp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One second of an RDP session's frame pipeline, for the pane's "Show performance" readout: what
 * the helper counted (frames and megabytes sent to jterm, the time its writes took, the codec the
 * server used) joined with what the pane measured (copying frames into its image, painting).
 *
 * <p>It exists to tell four causes of stuttering video apart, which look the same on screen: the
 * server sending few frames, the helper's pipe being slow, the copy being slow, or painting being
 * slow. Swing-free; the helper's half arrives as a log line ({@link #parseHelperLine}).</p>
 *
 * @param millis      the length of the helper's interval
 * @param frames      screen updates the helper sent (one per server frame)
 * @param kib         pixel data sent, in KiB
 * @param writeMicros time the helper spent writing them to jterm, in total
 * @param writeMaxMicros its slowest single write
 */
public record RdpStats(int millis, int frames, long kib, long writeMicros, long writeMaxMicros,
                       int h264, int avc444, int progressive, int other) {

    /** The marker of a statistics line in the helper's log output. */
    public static final String HELPER_PREFIX = "jterm-rdp-helper: stats ";

    /** What the pane measured over the same second. Times in nanoseconds. */
    public record Local(int blits, long blitNanos, int paints, long paintNanos, long paintMaxNanos,
                        boolean scaledPaint) {
    }

    /**
     * The helper's line ({@code "jterm-rdp-helper: stats ms=1000 frames=30 ..."}), or {@code null}
     * for any other line or one that can't be read. Unknown keys are ignored and missing ones are
     * zero, so the helper and jterm need not change together.
     */
    public static RdpStats parseHelperLine(String line) {
        if (line == null) {
            return null;
        }
        int at = line.indexOf(HELPER_PREFIX);
        if (at < 0) {
            return null;
        }
        Map<String, Long> v = new HashMap<>();
        for (String part : line.substring(at + HELPER_PREFIX.length()).trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                v.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!v.containsKey("frames")) {
            return null;
        }
        return new RdpStats(num(v, "ms"), num(v, "frames"), v.getOrDefault("kib", 0L),
                v.getOrDefault("write_us", 0L), v.getOrDefault("write_max_us", 0L),
                num(v, "h264"), num(v, "avc444"), num(v, "progressive"), num(v, "other"));
    }

    private static int num(Map<String, Long> v, String key) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v.getOrDefault(key, 0L)));
    }

    /** The codec most of the server's graphics commands used, in words. */
    public String codec() {
        int max = Math.max(Math.max(h264, avc444), Math.max(progressive, other));
        if (max == 0) {
            return "no codec data";
        }
        if (max == avc444) {
            return "H.264 4:4:4";
        }
        if (max == h264) {
            return "H.264";
        }
        return max == progressive ? "RemoteFX progressive" : "bitmap codecs";
    }

    /** Frames a second. */
    public double fps() {
        return millis <= 0 ? 0 : frames * 1000.0 / millis;
    }

    /**
     * One line for the pane's bar, e.g.
     * {@code "30 fps · H.264 4:4:4 · 442 MB/s · pipe 4.1 ms (max 9) · copy 1.2 ms · paint 1.0 ms (max 3), 30/s"}.
     * "pipe" is the helper's time to hand one frame to jterm, "copy" jterm's time to take it,
     * "paint" the time to draw it; each is per frame, and a frame every 33 ms has that much to spend.
     */
    public String summary(Local local) {
        double perFrame = frames <= 0 ? 0 : writeMicros / 1000.0 / frames;
        double mbPerSecond = millis <= 0 ? 0 : kib * 1024.0 / 1e6 * 1000.0 / millis;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%.0f fps · %s · %.0f MB/s · pipe %.1f ms (max %.0f)",
                fps(), codec(), mbPerSecond, perFrame, writeMaxMicros / 1000.0));
        if (local != null) {
            double copy = local.blits() <= 0 ? 0 : local.blitNanos() / 1e6 / Math.max(1, frames);
            double paint = local.paints() <= 0 ? 0 : local.paintNanos() / 1e6 / local.paints();
            sb.append(String.format(Locale.ROOT, " · copy %.1f ms · paint %.1f ms (max %.0f), %d/s%s",
                    copy, paint, local.paintMaxNanos() / 1e6, local.paints(),
                    local.scaledPaint() ? ", scaled" : ""));
        }
        return sb.toString();
    }
}
