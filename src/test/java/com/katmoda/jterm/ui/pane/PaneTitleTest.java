/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.pane;

import com.katmoda.jterm.ui.pane.PaneTitle.PaneLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The label/tab composition rules, the path shortening, and the sanitising of remote-sent text. */
class PaneTitleTest {

    private static final String CWD = "/home/mark/git/jterm";

    // ---- pane label ----

    @Test
    void plainLocalShowsBarePathWhicheverWayThePreferenceIsSet() {
        assertEquals(CWD, PaneTitle.paneLabel(null, "local", CWD, true, true).full());
        assertEquals(CWD, PaneTitle.paneLabel(null, "local", CWD, true, false).full());
    }

    @Test
    void plainLocalFallsBackToItsNameWhenNoDirectoryIsKnown() {
        assertEquals("local", PaneTitle.paneLabel(null, "local", null, true, true).full());
    }

    @Test
    void remoteAppendsPathOnlyWhenThePreferenceIsOn() {
        assertEquals("orion (" + CWD + ")",
                PaneTitle.paneLabel(null, "orion", CWD, false, true).full());
        assertEquals("orion", PaneTitle.paneLabel(null, "orion", CWD, false, false).full());
        assertEquals("orion", PaneTitle.paneLabel(null, "orion", null, false, true).full());
    }

    @Test
    void renameWinsOutrightOnBothSurfaces() {
        assertEquals("build", PaneTitle.paneLabel("build", "orion", CWD, false, true).full());
        assertEquals("build", PaneTitle.paneLabel("build", "local", CWD, true, true).full());
        assertEquals("build", PaneTitle.tabTitle("build", "Terminal 3", "local", CWD, true, true));
        assertEquals("build", PaneTitle.tabTitle("build", null, "orion", CWD, false, true));
    }

    @Test
    void blankRenameIsTreatedAsNoRename() {
        assertEquals("orion", PaneTitle.paneLabel("   ", "orion", CWD, false, false).full());
    }

    // ---- tab title ----

    @Test
    void tabCarriesOnlyTheLastSegmentAndOnlyWhenOn() {
        assertEquals("Terminal 3 (jterm)",
                PaneTitle.tabTitle(null, "Terminal 3", "local", CWD, true, true));
        assertEquals("Terminal 3",
                PaneTitle.tabTitle(null, "Terminal 3", "local", CWD, true, false));
        assertEquals("orion (jterm)", PaneTitle.tabTitle(null, null, "orion", CWD, false, true));
    }

    @Test
    void wslIsNamedBySessionNotByTheGenericTabName() {
        // A WSL distro has an icon id, so it is not "plain local" and keeps its own name.
        assertEquals("RockyLinux9 (jterm)",
                PaneTitle.tabTitle(null, "Terminal 3", "RockyLinux9", CWD, false, true));
    }

    @Test
    void plainLocalFallsBackToTheSessionNameWithoutAGenericTabName() {
        assertEquals("local", PaneTitle.tabTitle(null, null, "local", null, true, true));
    }

    // ---- last segment ----

    @Test
    void lastSegmentHandlesRootsAndTrailingSeparators() {
        assertEquals("jterm", PaneTitle.lastSegment(CWD));
        assertEquals("b", PaneTitle.lastSegment("/a/b/"));
        assertEquals("/", PaneTitle.lastSegment("/"));
        assertEquals("~", PaneTitle.lastSegment("~"));
        assertEquals("a", PaneTitle.lastSegment("/a"));
        assertEquals(".android", PaneTitle.lastSegment("C:\\Users\\w104186\\.android"));
        assertEquals("C:", PaneTitle.lastSegment("C:\\"));
    }

    // ---- truncation ----

    @Test
    void truncationDropsLeadingComponentsThenTheName() {
        List<String> out = PaneTitle.truncationCandidates(
                new PaneLabel("", "/a/very/long/path/to/some/cwd"));

        assertEquals("/a/very/long/path/to/some/cwd", out.get(0));
        assertTrue(out.contains(".../to/some/cwd"), out.toString());
        assertTrue(out.contains(".../cwd"), out.toString());
        assertEquals("cwd", out.get(out.size() - 1));
    }

    @Test
    void truncationKeepsTheNameUntilNothingElseIsLeftToDrop() {
        List<String> out = PaneTitle.truncationCandidates(new PaneLabel("web1", "/a/b/c"));

        assertEquals("web1 (/a/b/c)", out.get(0));
        assertTrue(out.indexOf("web1 (.../c)") < out.indexOf("/a/b/c"), out.toString());
        assertEquals("c", out.get(out.size() - 1));
    }

    @Test
    void truncationUsesThePathsOwnSeparator() {
        List<String> out = PaneTitle.truncationCandidates(
                new PaneLabel("", "C:\\Users\\w104186\\.android"));

        assertTrue(out.contains("...\\w104186\\.android"), out.toString());
    }

    @Test
    void aLabelWithNoPathHasNothingToShorten() {
        assertEquals(List.of("orion"), PaneTitle.truncationCandidates(new PaneLabel("orion", null)));
    }

    // ---- window-title heuristic ----

    @Test
    void windowTitleYieldsAPathOnlyWhenItLooksLikeOne() {
        assertEquals("~/git/jterm", PaneTitle.cwdFromWindowTitle("mark@orion:~/git/jterm"));
        assertEquals("/var/log", PaneTitle.cwdFromWindowTitle("mark@orion: /var/log"));
        assertEquals("C:\\Users\\mark", PaneTitle.cwdFromWindowTitle("C:\\Users\\mark"));
        assertNull(PaneTitle.cwdFromWindowTitle("vim README"));
        assertNull(PaneTitle.cwdFromWindowTitle("htop"));
        assertNull(PaneTitle.cwdFromWindowTitle("mark@orion:"));
        assertNull(PaneTitle.cwdFromWindowTitle(""));
        assertNull(PaneTitle.cwdFromWindowTitle(null));
    }

    // ---- sanitising (exercised through the public composers) ----

    @Test
    void controlCharactersAreStrippedFromRemoteText() {
        String label = PaneTitle.paneLabel(null, "orion", "/tmp/a\u0007b\nc", false, true).full();

        assertEquals("orion (/tmp/abc)", label);
    }

    @Test
    void bidiOverridesAreStrippedSoAHostCannotSpoofAnother() {
        String label = PaneTitle.paneLabel(null, "orion", "/tmp/\u202Egnp.x", false, true).full();

        assertFalse(label.contains("\u202E"), label);
        assertEquals("orion (/tmp/gnp.x)", label);
    }

    @Test
    void htmlIsDefusedSoSwingRendersItAsText() {
        String label = PaneTitle.paneLabel(null, "orion", "<html><b>x", false, true).full();
        assertFalse(label.contains("(<html"), label);

        // Repeated openings must not leave a live one behind after a single strip.
        String nested = PaneTitle.paneLabel("<<html><b>x", "orion", null, false, false).full();
        assertFalse(nested.startsWith("<html"), nested);

        assertFalse(PaneTitle.tabTitle(null, "<HTML>x", "local", null, true, false).startsWith("<HTML"));
    }

    @Test
    void aLocalLabelIsTheBarePathSoAnHtmlOpeningSitsAtPositionZero() {
        // The one case where remote text is not preceded by a name: a hostile OSC 9;9 payload on a
        // plain local pane. Swing only parses markup when the string *starts* with <html>.
        String label = PaneTitle.paneLabel(null, "local", "<html><img src=x>", true, true).full();
        assertFalse(label.regionMatches(true, 0, "<html", 0, 5), label);

        String tab = PaneTitle.tabTitle("<html><img src=x>", null, "local", null, true, true);
        assertFalse(tab.regionMatches(true, 0, "<html", 0, 5), tab);
    }

    @Test
    void overlongRemoteTextIsCapped() {
        String label = PaneTitle.paneLabel(null, "orion", "/" + "a".repeat(4000), false, true).full();

        assertTrue(label.length() < 600, "length was " + label.length());
    }

    @Test
    void anEmptyNameFallsBackRatherThanRenderingBlank() {
        assertEquals("terminal", PaneTitle.paneLabel(null, "  ", null, false, false).full());
        assertEquals("terminal", PaneTitle.tabTitle(null, null, null, null, false, false));
    }
}
