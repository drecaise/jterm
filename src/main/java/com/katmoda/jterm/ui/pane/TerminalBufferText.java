package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.model.LinesStorage;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;

/**
 * Extracts a terminal's full output (scrollback history followed by the visible screen) as
 * plain text, used by the "Save terminal output to file" action on the session-stopped screen.
 */
final class TerminalBufferText {

    private TerminalBufferText() {
    }

    /** Returns the widget's history + screen as newline-separated text (trailing blanks trimmed). */
    static String collect(JtermJediTermWidget widget) {
        return collect(widget.getTerminalTextBuffer());
    }

    /** Same as {@link #collect(JtermJediTermWidget)} but against a raw buffer (testable). */
    static String collect(TerminalTextBuffer buffer) {
        StringBuilder out = new StringBuilder();
        buffer.lock();
        try {
            appendLines(out, buffer.getHistoryLinesStorage());
            appendLines(out, buffer.getScreenLinesStorage());
        } finally {
            buffer.unlock();
        }
        return trimTrailingBlankLines(out.toString());
    }

    private static void appendLines(StringBuilder out, LinesStorage storage) {
        for (TerminalLine line : storage) {
            out.append(stripTrailing(line.getText())).append('\n');
        }
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static String trimTrailingBlankLines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return end > 0 ? text.substring(0, end) + "\n" : "";
    }
}
