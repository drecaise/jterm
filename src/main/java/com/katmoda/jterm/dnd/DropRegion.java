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
