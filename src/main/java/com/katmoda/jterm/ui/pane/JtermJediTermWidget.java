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
package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.katmoda.jterm.ui.theme.JTermSettingsProvider;

/** A {@link JediTermWidget} that uses {@link JtermTerminalPanel} for right-click paste support. */
final class JtermJediTermWidget extends JediTermWidget {

    JtermJediTermWidget(SettingsProvider settingsProvider) {
        super(settingsProvider);
    }

    @Override
    protected TerminalPanel createTerminalPanel(SettingsProvider settingsProvider,
                                                StyleState styleState, TerminalTextBuffer textBuffer) {
        // Referencing `this` here is safe: the callback only fires on a later Ctrl+wheel event,
        // long after this constructor-time call returns.
        return new JtermTerminalPanel(settingsProvider, textBuffer, styleState, this::zoomFont);
    }

    @Override
    protected StyleState createDefaultStyle() {
        // mySettingsProvider is assigned by the JediTermWidget constructor before this runs.
        StyleState state = new ThemeAwareStyleState((JTermSettingsProvider) mySettingsProvider);
        state.setDefaultStyle(mySettingsProvider.getDefaultStyle());
        return state;
    }

    /**
     * Re-resolves terminal colors after a theme change: clears the panel's cached selection/found
     * colors (recomputed lazily on the next paint) and repaints. Default-pen and ANSI-indexed cells
     * are resolved against the settings provider on paint, so they follow the new theme without
     * being rewritten. Call after {@link JTermSettingsProvider#setTheme}.
     */
    void recolor() {
        TerminalPanel panel = getTerminalPanel();
        try {
            var reset = TerminalPanel.class.getDeclaredMethod("resetColorCache");
            reset.setAccessible(true);
            reset.invoke(panel);
        } catch (Exception ignored) {
            // Best-effort: without it the selection tint refreshes on the next selection instead.
        }
        panel.repaint();
    }

    /** Grows/shrinks this pane's font by {@code steps} points and re-lays-out the grid. EDT only. */
    void zoomFont(int steps) {
        ((JTermSettingsProvider) mySettingsProvider).adjustFontSize(steps);
        reinitFont();
    }

    /** Restores this pane's font to its configured size and re-lays-out the grid. EDT only. */
    void resetFont() {
        ((JTermSettingsProvider) mySettingsProvider).resetFontSize();
        reinitFont();
    }

    /**
     * Rebuilds JediTerm's derived (normal/bold/italic) fonts from the settings provider's current
     * font and recomputes the cell grid — which also notifies the pty of the new row/column count
     * via the panel's resize path. {@code reinitFontAndResize} is protected, so we invoke it with
     * the same best-effort reflection used by {@link #recolor()}.
     */
    private void reinitFont() {
        TerminalPanel panel = getTerminalPanel();
        try {
            var reinit = TerminalPanel.class.getDeclaredMethod("reinitFontAndResize");
            reinit.setAccessible(true);
            reinit.invoke(panel);
        } catch (Exception ignored) {
            // Best-effort: without it the font size won't change until the next natural resize.
        }
        panel.repaint();
    }

    /**
     * Reuse this widget — and its existing {@link TerminalTextBuffer} scrollback — with a new
     * connector after the previous session ended. {@code setTtyConnector} builds a fresh
     * {@code TerminalStarter} (the old one is flagged stopped once a session ends) and {@code start}
     * spawns a new reader thread; neither clears the text buffer, so prior output is retained and
     * the new session's output appends below it. We first leave any alternate-screen buffer (e.g. a
     * session dropped while inside vim/htop) so the new output lands in the scrollback-backed
     * primary buffer rather than a stale alt-screen. Must be called on the EDT.
     */
    void restartWith(TtyConnector connector) {
        if (isSessionRunning()) {
            stop();
        }
        getTerminal().useAlternateBuffer(false);
        // The reused TerminalPanel keeps the cursor-visibility state from the dead session. If that
        // session (or a TUI it was running) had hidden the cursor via DECTCEM (?25l) and dropped
        // before restoring it, the cursor would stay invisible because a fresh shell never re-enables
        // it. Force it back on so the reconnected session always shows a cursor.
        getTerminal().setCursorVisible(true);
        setTtyConnector(connector);
        start();
    }
}
