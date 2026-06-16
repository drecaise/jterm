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
package com.katmoda.jterm.terminal.local;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.nio.charset.Charset;

/**
 * Bridges a pty4j {@link PtyProcess} to JediTerm. {@link ProcessTtyConnector} already
 * implements stream reading/writing against {@link Process}; we only add a name and
 * forward resize events to the pty.
 */
final class PtyTtyConnector extends ProcessTtyConnector {

    private final PtyProcess process;
    private final String name;

    PtyTtyConnector(PtyProcess process, String name, Charset charset) {
        super(process, charset);
        this.process = process;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void resize(TermSize size) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }
}
