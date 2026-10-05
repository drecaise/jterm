/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.theme;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Copying text written in the default pen must give back what is on screen. Replays escape
 * sequences through JediTerm's real emulator and line model, then reads the line the way a copy
 * does ({@link SelectionUtil#getSelectionText}).
 */
class DefaultPenCopyTest {

    private static final int WIDTH = 80;

    @Test
    void defaultPenIsNotJediTermsPaddingStyle() {
        assertNotSame(TextStyle.EMPTY, JTermSettingsProvider.DEFAULT_PEN);
    }

    @Test
    void erasingPastTheLineEndLeavesNoNulInACopy() {
        // "abc", cursor to column 10, erase 3 characters (past the end of the line, so JediTerm pads
        // the gap), cursor to column 12, "xyz". This copied as "abc" followed by six NUL bytes.
        assertEquals("abc        xyz", copyFirstLine("abc\u001b[1;10H\u001b[3X\u001b[1;12Hxyz"));
    }

    @Test
    void textWrittenIntoErasedPaddingIsNotDroppedFromACopy() {
        // Text written into the erased area fused with the padding into one NUL-led run, which
        // TerminalLine.getText() reads as end-of-line — so the word vanished from the copy.
        assertEquals("text", copyFirstLine("\u001b[1;60H\u001b[3X\u001b[1;55Htext").strip());
    }

    private static String copyFirstLine(String output) {
        StyleState style = new StyleState();
        style.setDefaultStyle(JTermSettingsProvider.DEFAULT_PEN);
        TerminalTextBuffer buffer = new TerminalTextBuffer(WIDTH, 24, style, 100);
        JediTerminal terminal = new JediTerminal(new NullDisplay(), buffer, style);
        JediEmulator emulator = new JediEmulator(new ArrayTerminalDataStream(output.toCharArray()), terminal);
        try {
            while (emulator.hasNext()) {
                emulator.next();
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // A selection reaching past the last character ends in a newline; trailing blanks are not the point.
        return SelectionUtil.getSelectionText(new Point(0, 0), new Point(WIDTH, 0), buffer).stripTrailing();
    }

    /** A display that ignores everything — the line model is all these tests read. */
    private static final class NullDisplay implements TerminalDisplay {
        @Override public void setCursor(int x, int y) { }
        @Override public void setCursorShape(CursorShape cursorShape) { }
        @Override public void beep() { }
        @Override public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) { }
        @Override public void setCursorVisible(boolean isCursorVisible) { }
        @Override public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) { }
        @Override public String getWindowTitle() { return ""; }
        @Override public void setWindowTitle(String windowTitle) { }
        @Override public TerminalSelection getSelection() { return null; }
        @Override public void terminalMouseModeSet(MouseMode mouseMode) { }
        @Override public void setMouseFormat(MouseFormat mouseFormat) { }
        @Override public boolean ambiguousCharsAreDoubleWidth() { return false; }
    }
}
