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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppVersionTest {

    @Test
    void parsesPlainAndTaggedVersions() {
        assertEquals(new AppVersion(1, 8, 0, ""), AppVersion.parse("1.8.0").orElseThrow());
        assertEquals(new AppVersion(1, 8, 0, ""), AppVersion.parse("v1.8.0").orElseThrow());
        assertEquals(new AppVersion(1, 8, 0, ""), AppVersion.parse("  v1.8.0 ").orElseThrow());
    }

    @Test
    void omittedComponentsDefaultToZero() {
        assertEquals(new AppVersion(2, 0, 0, ""), AppVersion.parse("2").orElseThrow());
        assertEquals(new AppVersion(1, 8, 0, ""), AppVersion.parse("1.8").orElseThrow());
    }

    @Test
    void parsesPreReleaseSuffixes() {
        assertEquals("rc1", AppVersion.parse("1.8.0-rc1").orElseThrow().preRelease());
        assertEquals("SNAPSHOT", AppVersion.parse("1.8.0-SNAPSHOT").orElseThrow().preRelease());
        assertEquals("build.7", AppVersion.parse("1.8.0+build.7").orElseThrow().preRelease());
    }

    @Test
    void unparsableInputYieldsEmpty() {
        // "(dev)" is what AppInfo reports when application.properties was not filtered by Maven.
        assertEquals(Optional.empty(), AppVersion.parse("(dev)"));
        assertEquals(Optional.empty(), AppVersion.parse(""));
        assertEquals(Optional.empty(), AppVersion.parse("   "));
        assertEquals(Optional.empty(), AppVersion.parse(null));
        assertEquals(Optional.empty(), AppVersion.parse("release-candidate"));
        assertEquals(Optional.empty(), AppVersion.parse("1.8.0.4.2.1"));
    }

    @Test
    void ordersByEachComponentNumerically() {
        assertNewer("2.0.0", "1.9.9");
        assertNewer("1.9.0", "1.8.9");
        assertNewer("1.8.1", "1.8.0");
        assertEquals(0, AppVersion.parse("1.8.0").orElseThrow()
                .compareTo(AppVersion.parse("v1.8.0").orElseThrow()));
    }

    @Test
    void doubleDigitMinorBeatsSingleDigit() {
        // The classic string-compare trap: "1.10.0" sorts before "1.9.0" lexicographically.
        assertNewer("1.10.0", "1.9.0");
        assertNewer("1.8.10", "1.8.9");
    }

    @Test
    void preReleasePrecedesItsOwnRelease() {
        assertNewer("1.8.0", "1.8.0-rc1");
        assertNewer("1.8.0", "1.8.0-SNAPSHOT");
        assertNewer("1.8.0-rc2", "1.8.0-rc1");
        // ...but a newer number still wins over any suffix on an older one.
        assertNewer("1.9.0-rc1", "1.8.0");
    }

    @Test
    void isUpgradeRequiresBothSidesToParse() {
        assertTrue(AppVersion.isUpgrade("1.8.0", "v1.9.0"));
        assertFalse(AppVersion.isUpgrade("1.9.0", "v1.8.0"));
        assertFalse(AppVersion.isUpgrade("1.8.0", "v1.8.0"));
        // A dev build must never be told to upgrade, and a garbage tag must never trigger one.
        assertFalse(AppVersion.isUpgrade("(dev)", "v99.0.0"));
        assertFalse(AppVersion.isUpgrade("1.8.0", "nightly"));
        assertFalse(AppVersion.isUpgrade(null, "v1.9.0"));
    }

    private static void assertNewer(String newer, String older) {
        assertTrue(AppVersion.parse(newer).orElseThrow()
                        .isNewerThan(AppVersion.parse(older).orElseThrow()),
                newer + " should be newer than " + older);
        assertFalse(AppVersion.parse(older).orElseThrow()
                        .isNewerThan(AppVersion.parse(newer).orElseThrow()),
                older + " should not be newer than " + newer);
    }
}
