package com.katmoda.jterm.ui.sidebar;

/** How a session chosen from the sidebar should be opened. */
public enum OpenMode {
    /** Open in a brand-new tab (the default for double-click / "Open"). */
    NEW_TAB,
    /** Replace/fill the active pane of the current tab. */
    ACTIVE,
    /** Add a column to the current tab and open in it. */
    SPLIT_COLUMN,
    /** Add a row to the current tab and open in it. */
    SPLIT_ROW
}
