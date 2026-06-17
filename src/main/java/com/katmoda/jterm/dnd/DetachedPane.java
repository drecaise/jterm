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

import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.ui.grid.GridContent;

/**
 * The outcome of detaching a pane from its grid for a move: the pane itself plus its restart
 * factory. A non-null {@code DetachedPane} means the detach succeeded; {@link #factory()} may be
 * {@code null} for content with no restart concept (e.g. the SFTP browser), which is why this
 * carries an explicit success signal rather than overloading a nullable factory as one.
 */
public record DetachedPane(GridContent content, SessionFactory factory) {
}
