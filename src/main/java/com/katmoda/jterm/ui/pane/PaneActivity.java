package com.katmoda.jterm.ui.pane;

/**
 * A terminal pane's background-activity state, surfaced on its tab when the tab is not in
 * front: {@link #NONE} (seen / nothing new), {@link #NEW_OUTPUT} (unread output), and
 * {@link #DISCONNECTED} (its session ended). Selecting the tab resets every pane to
 * {@link #NONE}; see {@code PaneGrid.setForeground}.
 */
public enum PaneActivity {
    NONE, NEW_OUTPUT, DISCONNECTED
}
