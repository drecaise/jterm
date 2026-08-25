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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code MAJOR.MINOR.PATCH[-preRelease]} version, used to decide whether a GitHub
 * release is newer than the running build.
 *
 * <p>Deliberately not a full semver implementation — jterm only ever needs "is A newer than B"
 * across its own tags ({@code v1.8.0}) and its own {@code application.version} ({@code 1.8.0},
 * or {@code 1.8.0-SNAPSHOT} between releases). Anything it cannot make sense of parses to
 * empty rather than to a guess, and the caller then skips the check entirely: {@code AppInfo}
 * reports {@code "(dev)"} when the app runs against unfiltered classes, and a dev build must
 * never nag about an update.</p>
 */
public record AppVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<AppVersion> {

    /** Leading {@code v} optional; minor/patch optional; anything after {@code -} or {@code +}. */
    private static final Pattern PATTERN =
            Pattern.compile("v?(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?(?:[-+](.+))?");

    /**
     * Parses a version string, tolerating a leading {@code v} and omitted trailing components.
     *
     * @return empty if {@code text} is null, blank, or not recognisably a version
     */
    public static Optional<AppVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                m.group(4) == null ? "" : m.group(4)));
    }

    /**
     * True when both strings parse and {@code candidate} is strictly newer than {@code current}.
     *
     * <p>The shared "is this worth telling the user about" policy, used by both the scheduled
     * check and the manual Help menu one. An unparsable version on either side answers false, so
     * a dev build ({@code "(dev)"}) or a malformed tag is silently ignored rather than guessed
     * at.</p>
     */
    public static boolean isUpgrade(String current, String candidate) {
        Optional<AppVersion> from = parse(current);
        Optional<AppVersion> to = parse(candidate);
        return from.isPresent() && to.isPresent() && to.get().isNewerThan(from.get());
    }

    /** True when this version is strictly newer than {@code other}. */
    public boolean isNewerThan(AppVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        // Same numbers: a pre-release precedes its own release, so 1.8.0-rc1 < 1.8.0 and a user
        // running 1.8.0-SNAPSHOT is correctly offered the real 1.8.0.
        boolean pre = !preRelease.isEmpty();
        boolean otherPre = !other.preRelease.isEmpty();
        if (pre != otherPre) {
            return pre ? -1 : 1;
        }
        return preRelease.compareTo(other.preRelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
