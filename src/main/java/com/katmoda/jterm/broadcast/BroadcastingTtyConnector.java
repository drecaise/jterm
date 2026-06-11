package com.katmoda.jterm.broadcast;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wraps a pane's real {@link TtyConnector}. Reads/resize/etc. pass straight through;
 * {@code write} additionally hands the bytes to the {@link BroadcastBus} so they can be
 * mirrored to other participating panes when broadcast mode is on.
 *
 * <p>The bus writes to the <em>real</em> connectors of the other panes (not their
 * wrappers), so there is no fan-out loop.</p>
 */
public final class BroadcastingTtyConnector implements TtyConnector {

    private final TtyConnector real;
    private final BroadcastBus bus;

    public BroadcastingTtyConnector(TtyConnector real, BroadcastBus bus) {
        this.real = real;
        this.bus = bus;
    }

    /** The wrapped connector, used as the broadcast source identity. */
    public TtyConnector real() {
        return real;
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
        return real.read(buf, offset, length);
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
