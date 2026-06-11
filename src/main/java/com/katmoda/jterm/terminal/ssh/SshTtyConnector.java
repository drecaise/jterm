package com.katmoda.jterm.terminal.ssh;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

/**
 * JediTerm {@link TtyConnector} over a MINA SSHD {@link ChannelShell}, using the
 * channel's inverted streams (we read remote output from {@code invertedOut} and
 * write keystrokes to {@code invertedIn}).
 */
final class SshTtyConnector implements TtyConnector {

    private final ChannelShell channel;
    private final String name;
    private final OutputStream toRemote;
    private final InputStreamReader fromRemote;

    SshTtyConnector(ChannelShell channel, String name) {
        this.channel = channel;
        this.name = name;
        this.toRemote = channel.getInvertedIn();
        InputStream out = channel.getInvertedOut();
        this.fromRemote = new InputStreamReader(out, StandardCharsets.UTF_8);
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return fromRemote.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        toRemote.write(bytes);
        toRemote.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
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
