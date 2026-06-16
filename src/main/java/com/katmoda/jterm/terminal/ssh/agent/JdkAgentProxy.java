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
package com.katmoda.jterm.terminal.ssh.agent;

import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.ThreadUtils;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * An ssh-agent client speaking the agent protocol over a Unix-domain socket using the
 * JDK's native support ({@link UnixDomainSocketAddress}, Java 16+) — no Apache APR /
 * tomcat-native required (which MINA's bundled {@code UnixAgentFactory} otherwise needs).
 *
 * <p>{@link AbstractAgentProxy} implements the whole agent protocol (list identities, sign,
 * …); we only provide the transport: a {@link #request(Buffer)} that sends a prepared
 * {@code [uint32 length][payload]} message and reads the framed reply.</p>
 */
public final class JdkAgentProxy extends AbstractAgentProxy {

    private static final int MAX_REPLY = 256 * 1024;

    private final SocketChannel channel;

    public JdkAgentProxy(String authSocketPath) throws IOException {
        super(ThreadUtils.newSingleThreadExecutor("jterm-ssh-agent"));
        this.channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        this.channel.connect(UnixDomainSocketAddress.of(Path.of(authSocketPath)));
    }

    @Override
    protected Buffer request(Buffer buffer) throws IOException {
        synchronized (channel) {
            // The buffer is already prepared by AbstractAgentProxy: [uint32 length][payload].
            writeFully(ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available()));

            int length = readLength();
            byte[] payload = new byte[length];
            readFully(ByteBuffer.wrap(payload));
            return new ByteArrayBuffer(payload);
        }
    }

    private int readLength() throws IOException {
        byte[] header = new byte[4];
        readFully(ByteBuffer.wrap(header));
        long length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (length < 0 || length > MAX_REPLY) {
            throw new IOException("Invalid ssh-agent reply length: " + length);
        }
        return (int) length;
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (channel.write(buf) < 0) {
                throw new EOFException("ssh-agent socket closed");
            }
        }
    }

    private void readFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                throw new EOFException("ssh-agent socket closed");
            }
        }
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            channel.close();
        }
    }
}
