package com.katmoda.jterm.ui.pane;

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
        return new JtermTerminalPanel(settingsProvider, textBuffer, styleState);
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
}
