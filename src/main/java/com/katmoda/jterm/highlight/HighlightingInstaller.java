package com.katmoda.jterm.highlight;

import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalLineIntervalHighlighting;
import com.jediterm.terminal.model.TerminalModelListener;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.katmoda.jterm.highlight.CompiledHighlightList.CompiledRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Colors new terminal output by re-scanning the on-screen lines whenever the text buffer changes
 * and applying each rule's foreground style via {@link TerminalLine#addCustomHighlighting} — the
 * same per-character override mechanism JediTerm uses for hyperlinks, but with no link semantics
 * (no underline, hover, cursor change or click).
 *
 * <p>Only screen lines are scanned (never history): a line gets colored as it appears, and the
 * highlightings we applied while it was visible ride along into scrollback. Per scan we dispose the
 * highlightings we previously created on a still-visible line before re-applying, so repeated change
 * events never stack duplicates. The model listener fires on JediTerm's read thread, so work runs
 * under {@link TerminalTextBuffer#lock()}.</p>
 */
public final class HighlightingInstaller {

    private HighlightingInstaller() {
    }

    /**
     * Installs highlighting on {@code widget}. No-op if the compiled list is empty.
     *
     * @return a teardown {@link Runnable} that detaches the listener (call from the pane's close
     *         path); {@code null} if nothing was installed.
     */
    public static Runnable install(JediTermWidget widget, CompiledHighlightList compiled) {
        if (compiled == null || compiled.isEmpty()) {
            return null;
        }
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        Scanner scanner = new Scanner(buffer, compiled.rules());
        TerminalModelListener listener = scanner::scan;
        buffer.addModelListener(listener);
        return () -> buffer.removeModelListener(listener);
    }

    /** Holds the per-line highlightings we created, so we can dispose-before-reapply. */
    private static final class Scanner {
        private final TerminalTextBuffer buffer;
        private final List<CompiledRule> rules;
        private final Map<TerminalLine, List<TerminalLineIntervalHighlighting>> applied =
                new IdentityHashMap<>();

        Scanner(TerminalTextBuffer buffer, List<CompiledRule> rules) {
            this.buffer = buffer;
            this.rules = rules;
        }

        void scan() {
            buffer.lock();
            try {
                Set<TerminalLine> current = new HashSet<>();
                int count = buffer.getScreenLinesCount();
                for (int i = 0; i < count; i++) {
                    TerminalLine line = buffer.getLine(i);
                    if (line == null) {
                        continue;
                    }
                    current.add(line);
                    rehighlight(line);
                }
                // Drop references to lines that scrolled into history WITHOUT disposing them, so
                // their color persists in scrollback and the map doesn't grow unbounded.
                applied.keySet().retainAll(current);
            } finally {
                buffer.unlock();
            }
        }

        private void rehighlight(TerminalLine line) {
            List<TerminalLineIntervalHighlighting> previous = applied.remove(line);
            if (previous != null) {
                for (TerminalLineIntervalHighlighting h : previous) {
                    h.dispose();
                }
            }
            String text = line.getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            List<TerminalLineIntervalHighlighting> created = new ArrayList<>();
            for (CompiledRule rule : rules) {
                Matcher m = rule.pattern().matcher(text);
                while (m.find()) {
                    if (m.end() > m.start()) {
                        // addCustomHighlighting's second argument is a LENGTH, not an end offset.
                        created.add(line.addCustomHighlighting(
                                m.start(), m.end() - m.start(), rule.style()));
                    }
                }
            }
            if (!created.isEmpty()) {
                applied.put(line, created);
            }
        }
    }
}
