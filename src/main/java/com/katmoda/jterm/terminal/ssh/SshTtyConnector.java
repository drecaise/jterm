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
package com.katmoda.jterm.terminal.ssh;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.EnumSet;

/**
 * JediTerm {@link TtyConnector} over a MINA SSHD {@link ChannelShell}, using the
 * channel's inverted streams (we read remote output from {@code invertedOut} and
 * write keystrokes to {@code invertedIn}).
 */
final class SshTtyConnector implements TtyConnector {

    private final ChannelShell channel;
    private final String name;
    private final Charset charset;
    private final OutputStream toRemote;
    private final InputStreamReader fromRemote;

    // Timestamp of the last user-driven write, used to idle-gate keep-alive NUL injection. Only
    // real input (keystrokes, broadcast, pasted text) through write(...) updates this; an injected
    // keep-alive NUL deliberately does not, so idleness keeps being measured from the last user
    // action and the NUL re-fires each interval while the session stays idle.
    private volatile long lastActivityNanos = System.nanoTime();

    SshTtyConnector(ChannelShell channel, String name, Charset charset) {
        this.channel = channel;
        this.name = name;
        this.charset = charset;
        this.toRemote = channel.getInvertedIn();
        InputStream out = channel.getInvertedOut();
        this.fromRemote = new InputStreamReader(out, charset);
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return fromRemote.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        lastActivityNanos = System.nanoTime();
        // Share the lock with sendKeepAlive so the scheduler thread and the EDT never interleave a
        // write on toRemote.
        synchronized (toRemote) {
            toRemote.write(bytes);
            toRemote.flush();
        }
    }

    /** Whether no user write has occurred for at least {@code d}. */
    boolean idleFor(Duration d) {
        return System.nanoTime() - lastActivityNanos >= d.toNanos();
    }

    /**
     * Injects a single NUL byte into the shell input to reset a server-side {@code TMOUT} idle
     * countdown. The shell's {@code read} wakes and readline discards NUL, so nothing is echoed and
     * nothing lands on the command line. Deliberately does not update {@link #lastActivityNanos}.
     */
    void sendKeepAlive() throws IOException {
        synchronized (toRemote) {
            toRemote.write(0);
            toRemote.flush();
        }
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(charset));
    }

    @Override
    public boolean isConnected() {
        return channel.isOpen();
    }

    @Override
    public boolean ready() throws IOException {
        return fromRemote.ready();
    }

    @Override
    public void resize(TermSize size) {
        try {
            channel.sendWindowChange(size.getColumns(), size.getRows());
        } catch (IOException ignored) {
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L);
        Integer status = channel.getExitStatus();
        return status != null ? status : 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        channel.close(false);
    }
}
