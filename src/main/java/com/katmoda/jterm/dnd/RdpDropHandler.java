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

import com.katmoda.jterm.session.RdpSessionConfig;
import com.katmoda.jterm.ui.grid.GridContent;

import java.util.function.Predicate;

/**
 * Opens an RDP session dropped onto a pane grid. The grid supplies {@code placer}, which puts the
 * new pane where it was dropped (a split of the target pane, or the empty cell) and answers
 * {@code false} when there is no room, so the handler can open it in a new tab instead of replacing
 * a live session. Unlike {@link SessionDropHandler} nothing connects first: an RDP pane places
 * itself, then connects, and shows its own failure.
 */
@FunctionalInterface
public interface RdpDropHandler {
    void open(RdpSessionConfig config, Predicate<GridContent> placer);
}
