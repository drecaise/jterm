package com.katmoda.jterm.dnd;

import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.ui.pane.TerminalPane;

/**
 * Detaches a pane from whichever tab's grid currently owns it, so another grid can adopt it. Only
 * the main window knows about all tabs, so it implements this; a grid receiving a cross-tab pane
 * drop uses it to pull the pane out of its source tab (closing that tab if it becomes empty).
 */
@FunctionalInterface
public interface PaneMoveCoordinator {

    /**
     * Remove {@code pane} from its owning grid <em>without</em> closing the session, closing the
     * owning tab if it is left empty.
     *
     * @return the pane's restart factory, so the adopting grid can keep restart working, or
     *         {@code null} if no grid owns the pane.
     */
    SessionFactory detachFromOwner(TerminalPane pane);
}
