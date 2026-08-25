/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.cwd;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OSC 7 / OSC 9;9 state machine: terminators, decoding, and refusing to be led astray. */
class OscCwdScannerTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = "\u009C";      // C1 string terminator
    private static final String OSC = "\u009D";     // C1 OSC introducer

    private final List<String> reports = new ArrayList<>();
    private final List<CwdTracker.Source> sources = new ArrayList<>();
    private final OscCwdScanner scanner = new OscCwdScanner((source, path) -> {
        sources.add(source);
        reports.add(path);
    });

    private void feed(String s) {
        for (int i = 0; i < s.length(); i++) {
            scanner.feed(s.charAt(i));
        }
    }

    // ---- OSC 7 ----

    @Test
    void osc7IsAcceptedWithEveryTerminatorAndIntroducer() {
        feed(ESC + "]7;file://orion/home/mark" + BEL);
        feed(ESC + "]7;file:///tmp" + ESC + "\\");
        feed(ESC + "]7;file:///var" + ST);
        feed(OSC + "7;file:///etc" + BEL);

        assertEquals(List.of("/home/mark", "/tmp", "/var", "/etc"), reports);
        assertTrue(sources.stream().allMatch(s -> s == CwdTracker.Source.OSC7), sources.toString());
    }

    @Test
    void osc7PercentDecodesPerByteNotPerCharacter() {
        feed(ESC + "]7;file://orion/home/mark/%C3%A9t%C3%A9" + BEL);

        assertEquals(List.of("/home/mark/\u00e9t\u00e9"), reports);
    }

    @Test
    void osc7DropsAWindowsDriveLettersLeadingSlash() {
        feed(ESC + "]7;file:///C:/Users/x" + BEL);

        assertEquals(List.of("C:/Users/x"), reports);
    }

    @Test
    void osc7WithAMalformedEscapeIsDroppedRatherThanGuessedAt() {
        feed(ESC + "]7;file:///tmp/%ZZ" + BEL);
        feed(ESC + "]7;file:///tmp/%A" + BEL);
        feed(ESC + "]7;nothost/tmp" + BEL);

        assertEquals(List.of(), reports);
    }

    // ---- OSC 9;9 ----

    @Test
    void osc99IsAcceptedQuotedOrBareAndKeepsBackslashes() {
        feed(ESC + "]9;9;\"C:\\Users\\w104186\\.android\"" + BEL);
        feed(ESC + "]9;9;C:\\Temp" + ESC + "\\");

        assertEquals(List.of("C:\\Users\\w104186\\.android", "C:\\Temp"), reports);
        assertTrue(sources.stream().allMatch(s -> s == CwdTracker.Source.OSC99), sources.toString());
    }

    // ---- fragmentation ----

    @Test
    void aSequenceSplitAcrossFeedsIsStillRecognised() {
        String seq = ESC + "]7;file://orion/home/mark/git/jterm" + BEL;
        Random random = new Random(20260825L);

        int at = 0;
        while (at < seq.length()) {
            int take = Math.min(1 + random.nextInt(5), seq.length() - at);
            feed(seq.substring(at, at + take));
            at += take;
        }

        assertEquals(List.of("/home/mark/git/jterm"), reports);
    }

    // ---- things that must be ignored ----

    @Test
    void titlesHyperlinksAndPaletteChangesAreNotOurs() {
        // OSC 0/1/2 reach us through JediTerm's own title listener instead.
        feed(ESC + "]0;mark@orion:~/git/jterm" + BEL);
        feed(ESC + "]2;mark@orion:~" + BEL);
        feed(ESC + "]8;;https://example.invalid" + BEL);
        feed(ESC + "]4;1;rgb:ff/00/00" + BEL);

        assertEquals(List.of(), reports);
    }

    @Test
    void ordinaryOutputAndCsiSequencesReportNothing() {
        feed("hello " + ESC + "[31mworld" + ESC + "[0m\r\n$ ls -l\r\n");

        assertEquals(List.of(), reports);
    }

    @Test
    void anAbortedSequenceIsDiscarded() {
        feed(ESC + "]7;file:///tm\u0018p" + BEL);

        assertEquals(List.of(), reports);
    }

    @Test
    void anOverlongPayloadIsDroppedAndTheScannerResynchronises() {
        feed(ESC + "]7;file:///" + "a".repeat(4000) + BEL);
        assertEquals(List.of(), reports);

        feed(ESC + "]7;file:///tmp" + BEL);
        assertEquals(List.of("/tmp"), reports);
    }

    @Test
    void anUnterminatedSequenceDoesNotSwallowTheNextOne() {
        // A second ESC restarts the introducer rather than being taken as payload.
        feed(ESC + "]7;file:///tmp" + ESC + "]7;file:///var" + BEL);

        assertEquals(List.of("/var"), reports);
    }
}
