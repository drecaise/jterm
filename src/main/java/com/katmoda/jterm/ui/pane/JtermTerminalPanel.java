package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.katmoda.jterm.config.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;

/**
 * A {@link TerminalPanel} that adds "paste on right click" behaviour.
 *
 * <p>JediTerm opens its context menu from its own internal mouse listener on a right-click, so
 * a listener added from outside can't suppress it. Intercepting {@link #processMouseEvent} —
 * which dispatches to those listeners — lets us paste and swallow the click before the popup is
 * built. When the preference is on, a plain right-click pastes; holding Ctrl still opens the
 * context menu. When it's off, the default context-menu behaviour is untouched.</p>
 */
final class JtermTerminalPanel extends TerminalPanel {

    private final String pasteActionName;

    JtermTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer textBuffer, StyleState styleState) {
        super(settingsProvider, textBuffer, styleState);
        this.pasteActionName = settingsProvider.getPasteActionPresentation().getName();
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        if (isPlainPasteClick(e)) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                requestFocusInWindow();
                paste();
            } else if (e.getID() == MouseEvent.MOUSE_CLICKED) {
                return; // swallow so JediTerm's default context menu doesn't open
            }
        }
        super.processMouseEvent(e);
    }

    /**
     * A plain (no-Ctrl) right-click while the preference is on and the terminal app isn't
     * itself capturing the mouse (so mouse-aware programs still receive the event).
     */
    private boolean isPlainPasteClick(MouseEvent e) {
        return SwingUtilities.isRightMouseButton(e)
                && AppSettings.get().isPasteOnRightClick()
                && !e.isControlDown()
                && !isRemoteMouseAction(e);
    }

    /** Defers to JediTerm's Paste action, which honours bracketed-paste mode. */
    private void paste() {
        for (TerminalAction action : getActions()) {
            if (pasteActionName.equals(action.getName())) {
                action.actionPerformed(null);
                return;
            }
        }
    }
}
