package com.katmoda.jterm.ui;

/**
 * A top-level tab body in the main window. Tabs are normally a
 * {@link com.katmoda.jterm.ui.grid.PaneGrid} of terminals, but may also be a
 * {@link com.katmoda.jterm.rdp.RdpTab} hosting a full RDP desktop. This interface gives both a
 * uniform teardown hook so closing a tab always releases its backing resources (terminal/SSH
 * sessions, or the external FreeRDP process) — no orphans.
 */
public interface TabContent {

    /** Release all backing resources held by this tab. Called when the tab is closed. */
    void dispose();
}
