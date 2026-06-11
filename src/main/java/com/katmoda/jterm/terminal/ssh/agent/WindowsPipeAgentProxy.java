package com.katmoda.jterm.terminal.ssh.agent;

import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.ThreadUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * ssh-agent client for the Windows OpenSSH agent, which is exposed as a named pipe
 * ({@code \\.\pipe\openssh-ssh-agent}) rather than a Unix socket. A Windows named pipe can be
 * read/written through an ordinary {@link RandomAccessFile}, so — like {@link JdkAgentProxy}
 * — we only provide the transport and reuse {@link AbstractAgentProxy} for the protocol.
 */
public final class WindowsPipeAgentProxy extends AbstractAgentProxy {

    private static final int MAX_REPLY = 256 * 1024;

    /** Win32 ERROR_PIPE_BUSY, rendered into the {@link FileNotFoundException} message by the JDK. */
    private static final String PIPE_BUSY = "All pipe instances are busy";
    private static final int OPEN_ATTEMPTS = 20;
    private static final long OPEN_RETRY_MILLIS = 100;

    private final RandomAccessFile pipe;
    private volatile boolean open = true;

    public WindowsPipeAgentProxy(String pipePath) throws IOException {
        super(ThreadUtils.newSingleThreadExecutor("jterm-ssh-agent"));
        this.pipe = openPipe(pipePath);
    }

    /**
     * Opens the agent pipe, retrying briefly on {@code ERROR_PIPE_BUSY}. The agent is a
     * multi-instance pipe server, but there's a momentary gap after it accepts one client and
     * before it creates the next instance during which {@code CreateFile} reports "all instances
     * busy" (seen when opening the agent right after an SSH session has used it). This mirrors the
     * Win32 {@code WaitNamedPipe} retry loop; a genuinely missing agent fails fast (different error).
     */
    private static RandomAccessFile openPipe(String pipePath) throws IOException {
        FileNotFoundException lastBusy = null;
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            try {
                return new RandomAccessFile(pipePath, "rw");
            } catch (FileNotFoundException e) {
                if (!isPipeBusy(e)) {
                    throw e; // e.g. the agent isn't running — don't spin
                }
                lastBusy = e;
                try {
                    Thread.sleep(OPEN_RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastBusy;
    }

    private static boolean isPipeBusy(FileNotFoundException e) {
        return e.getMessage() != null && e.getMessage().contains(PIPE_BUSY);
    }

    @Override
    protected Buffer request(Buffer buffer) throws IOException {
        synchronized (pipe) {
            // Prepared message: [uint32 length][payload].
            pipe.write(buffer.array(), buffer.rpos(), buffer.available());

            int length = readLength();
            byte[] payload = new byte[length];
            pipe.readFully(payload);
            return new ByteArrayBuffer(payload);
        }
    }

    private int readLength() throws IOException {
        byte[] header = new byte[4];
        pipe.readFully(header);
        long length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (length < 0 || length > MAX_REPLY) {
            throw new IOException("Invalid ssh-agent reply length: " + length);
        }
        return (int) length;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
        try {
            super.close();
        } finally {
            pipe.close();
        }
    }
}
