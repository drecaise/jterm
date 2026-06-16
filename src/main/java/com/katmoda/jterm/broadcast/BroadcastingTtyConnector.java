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
package com.katmoda.jterm.broadcast;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a pane's real {@link TtyConnector}. Reads/resize/etc. pass straight through;
 * {@code write} additionally hands the bytes to the {@link BroadcastBus} so they can be
 * mirrored to other participating panes when broadcast mode is on.
 *
 * <p>The bus writes to the <em>real</em> connectors of the other panes (not their
 * wrappers), so there is no fan-out loop.</p>
 */
public final class BroadcastingTtyConnector implements TtyConnector {

    private volatile TtyConnector real;
    private BroadcastBus bus;

    /** Fired (off-EDT) the first time output is read after each {@link #outputHandled()}. */
    private volatile Runnable onOutput;
    /** Coalesces output notifications: only one is in flight until {@link #outputHandled()}. */
    private final AtomicBoolean notifyPending = new AtomicBoolean(false);

    public BroadcastingTtyConnector(TtyConnector real, BroadcastBus bus) {
        this.real = real;
        this.bus = bus;
    }

    /**
     * Sets a callback fired when this connector reads output, used to flag background-tab
     * activity. It fires at most once per {@link #outputHandled()} cycle, so a flood of reads
     * (e.g. {@code yes}) doesn't swamp the EDT — the handler re-arms by calling
     * {@link #outputHandled()} once it has processed the signal.
     */
    public void setOnOutput(Runnable onOutput) {
        this.onOutput = onOutput;
    }

    /** Re-arms the output signal so the next read fires {@link #onOutput} again. */
    public void outputHandled() {
        notifyPending.set(false);
    }

    /** The wrapped connector, used as the broadcast source identity. */
    public TtyConnector real() {
        return real;
    }

    /**
     * Repoint this wrapper at a freshly created connector, keeping the same wrapper object (and so
     * the same JediTerm widget binding and broadcast registration) across a reconnect/restart. The
     * field is {@code volatile} so the off-EDT reader thread sees the swap. Used by
     * {@code TerminalPane.reconnect}.
     */
    public void setReal(TtyConnector real) {
        this.real = real;
    }

    /**
     * Repoint this connector at a different bus. Used when a pane is moved to another grid (drag a
     * pane out to a new tab, or a tab into a split): the pane must broadcast within its new grid, not
     * the one it was created in.
     */
    public void setBus(BroadcastBus bus) {
        this.bus = bus;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        real.write(bytes);
        bus.broadcast(real, bytes);
    }

    @Override
    public void write(String string) throws IOException {
        real.write(string);
        bus.broadcast(real, string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int n = real.read(buf, offset, length);
        Runnable cb = onOutput;
        if (n > 0 && cb != null && notifyPending.compareAndSet(false, true)) {
            cb.run();
        }
        return n;
    }

    @Override
    public boolean isConnected() {
        return real.isConnected();
    }

    @Override
    public boolean ready() throws IOException {
        return real.ready();
    }

    @Override
    public void resize(TermSize size) {
        real.resize(size);
    }

    @Override
    public int waitFor() throws InterruptedException {
        return real.waitFor();
    }

    @Override
    public String getName() {
        return real.getName();
    }

    @Override
    public void close() {
        real.close();
    }
}
