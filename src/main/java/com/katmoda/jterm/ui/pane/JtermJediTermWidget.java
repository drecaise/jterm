package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

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
}
