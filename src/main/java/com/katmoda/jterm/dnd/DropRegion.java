package com.katmoda.jterm.dnd;

/**
 * Which split a session drop should perform, based on where in the target pane it
 * landed: the top 60% adds a new column, the bottom 40% adds a new row.
 */
public enum DropRegion {
    COLUMN,
    ROW;

    /** Classify a drop by its y-position within a pane of the given height. */
    public static DropRegion forPosition(int y, int height) {
        return (y <= height * 0.60) ? COLUMN : ROW;
    }
}
