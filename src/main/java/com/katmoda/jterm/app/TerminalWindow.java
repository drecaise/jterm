package com.katmoda.jterm.app;

import com.katmoda.jterm.ui.tabs.TabPane;

import javax.swing.JFrame;

/**
 * A top-level window that hosts a tab strip of terminal grids: the single {@link MainWindow} or a
 * {@link DetachedWindow}. The {@link WindowManager} tracks these so tabs can move between windows and
 * the global shortcut dispatcher can target whichever window is focused.
 */
public interface TerminalWindow {

    /** The Swing frame backing this window. */
    JFrame frame();

    /** This window's tab strip. */
    TabPane tabPane();

    /** True only for the primary window (the re-attach target; never auto-closes when emptied). */
    boolean isMain();
}
