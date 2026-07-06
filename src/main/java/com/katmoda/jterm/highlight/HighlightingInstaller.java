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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;

/**
 * Colors new terminal output by re-scanning the on-screen lines when the text buffer changes
 * and applying each rule's foreground style via {@link TerminalLine#addCustomHighlighting} — the
 * same per-character override mechanism JediTerm uses for hyperlinks, but with no link semantics
 * (no underline, hover, cursor change or click).
 *
 * <p>Only screen lines are scanned (never history): a line gets colored as it appears, and the
 * highlightings we applied while it was visible ride along into scrollback. Per scan we dispose the
 * highlightings we previously created on a still-visible line before re-applying, so repeated change
 * events never stack duplicates.</p>
 *
 * <p>Scans are <em>debounced</em>: the model listener — which JediTerm fires on its emulator thread
 * for every buffer mutation, at least twice per output line — only flips a flag and schedules a
 * scan {@value #SCAN_DELAY_MS}&nbsp;ms out on a shared daemon scheduler. Running the rule regexes
 * synchronously per event throttled pty consumption (making fast output crawl and Ctrl+C appear to
 * hang while the backlog drained) and, since the scan holds {@link TerminalTextBuffer#lock()},
 * stalled painting too. Each scan also skips lines whose text is unchanged since the last scan, so
 * pure scrolling re-matches almost nothing (a {@link TerminalLine}'s identity travels with its
 * content).</p>
 */
public final class HighlightingInstaller {

    /** Debounce window between a buffer change and the rescan picking it up. */
    private static final int SCAN_DELAY_MS = 50;

    /** One scanner thread for all panes; scans are short and take the owning buffer's lock. */
    private static final ScheduledExecutorService SCANNER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jterm-highlight-scanner");
                t.setDaemon(true);
                return t;
            });

    private HighlightingInstaller() {
    }

    /**
     * Installs highlighting on {@code widget}. No-op if the compiled list is empty.
     *
     * @return a teardown {@link Runnable} that detaches the listener and cancels any pending scan
     *         (call from the pane's close path); {@code null} if nothing was installed.
     */
    public static Runnable install(JediTermWidget widget, CompiledHighlightList compiled) {
        if (compiled == null || compiled.isEmpty()) {
            return null;
        }
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        Scanner scanner = new Scanner(buffer, compiled.rules());
        TerminalModelListener listener = scanner::requestScan;
        buffer.addModelListener(listener);
        return () -> {
            buffer.removeModelListener(listener);
            scanner.close();
        };
    }

    /** Holds the per-line scan results, so we can skip unchanged lines and dispose-before-reapply. */
    private static final class Scanner {

        /** What the last scan saw on a line: its text and the highlightings we created for it. */
        private record LineState(String text, List<TerminalLineIntervalHighlighting> highlightings) {
        }

        private final TerminalTextBuffer buffer;
        private final List<CompiledRule> rules;
        /** Touched only on the scanner thread. */
        private final Map<TerminalLine, LineState> applied = new IdentityHashMap<>();

        /** True while a scan is scheduled but not yet started; coalesces change events. */
        private final AtomicBoolean scanPending = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> pendingScan;
        private volatile boolean closed;

        Scanner(TerminalTextBuffer buffer, List<CompiledRule> rules) {
            this.buffer = buffer;
            this.rules = rules;
        }

        /** Model-listener entry point; called on JediTerm's emulator thread — must stay cheap. */
        void requestScan() {
            if (!closed && scanPending.compareAndSet(false, true)) {
                pendingScan = SCANNER.schedule(this::scan, SCAN_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }

        void close() {
            closed = true;
            ScheduledFuture<?> pending = pendingScan;
            if (pending != null) {
                pending.cancel(false);
            }
        }

        private void scan() {
            // Re-arm first: changes arriving mid-scan schedule a follow-up that picks them up.
            scanPending.set(false);
            if (closed) {
                return;
            }
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
            String text = line.getText();
            if (text == null) {
                text = "";
            }
            LineState previous = applied.get(line);
            if (previous != null && previous.text().equals(text)) {
                return;
            }
            if (previous != null) {
                for (TerminalLineIntervalHighlighting h : previous.highlightings()) {
                    h.dispose();
                }
            }
            List<TerminalLineIntervalHighlighting> created = new ArrayList<>();
            if (!text.isEmpty()) {
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
            }
            applied.put(line, new LineState(text, created));
        }
    }
}
