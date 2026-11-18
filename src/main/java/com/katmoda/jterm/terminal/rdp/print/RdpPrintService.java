package com.katmoda.jterm.terminal.rdp.print;

import com.katmoda.jterm.terminal.rdp.drive.NtStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/**
 * Answers the printer's requests for one connection attempt (printer design): a job is CREATE, its
 * WRITEs, CLOSE. Each job goes to a file of its own in the {@link PrintJobFolder} and, once complete
 * and recognised as a PDF, joins the pane's {@link PrintJobQueue}. Nothing here prints or opens
 * anything: the document is only ever parsed by PDFBox, later, and only printed when the user (or the
 * session's opt-in) says so.
 *
 * <p>The server's bytes are hostile. Every length is checked against the request; the write offset
 * is ignored (Windows' spooler sends 0 for every write, measured), so data is appended in order; a
 * job over a cap is dropped, never truncated. One job at a time: a second CREATE before CLOSE is
 * {@code STATUS_DEVICE_BUSY}, which the spooler retries. Calls come from the connection's reader
 * thread; {@link #close} from any thread.</p>
 */
public final class RdpPrintService {

    private static final Logger LOG = LoggerFactory.getLogger(RdpPrintService.class);

    static final int IRP_MJ_CREATE = 0x00;
    static final int IRP_MJ_CLOSE = 0x02;
    static final int IRP_MJ_WRITE = 0x04;
    static final int IRP_MJ_DEVICE_CONTROL = 0x0E;
    static final int STATUS_DEVICE_BUSY = 0x80000011;
    static final int STATUS_PRINT_QUEUE_FULL = 0xC00000C6;

    /** The bytes every accepted job must begin with. */
    static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** How often (in bytes received) the free-space floor is checked again during a job. */
    static final long ROOM_CHECK_STEP = 16L << 20;

    /** An answer for the helper: the NTSTATUS and the reply body after the completion header. */
    public record Reply(int ioStatus, byte[] output) {
    }

    private final PrintJobFolder folder;
    private final PrintJobQueue queue;
    private final LongPredicate room;
    private final Consumer<String> refused;
    private final Clock clock;

    private int nextFileId = 1;
    private Job current;
    private long nextJobId = 1;
    private boolean closed;

    private static final class Job {
        final int fileId;
        final Path file;
        final OutputStream out;
        long written;
        long checkedAt;
        String failure;

        Job(int fileId, Path file, OutputStream out) {
            this.fileId = fileId;
            this.file = file;
            this.out = out;
        }
    }

    /**
     * {@code refused} hears, in words for the user, why a job was dropped (too large, not a PDF, too
     * many waiting); it may be called on the reader thread.
     */
    public RdpPrintService(PrintJobFolder folder, PrintJobQueue queue, Consumer<String> refused) {
        this(folder, queue, folder::hasRoomFor, refused, Clock.systemUTC());
    }

    RdpPrintService(PrintJobFolder folder, PrintJobQueue queue, LongPredicate room, Consumer<String> refused,
                    Clock clock) {
        this.folder = folder;
        this.queue = queue;
        this.room = room;
        this.refused = refused;
        this.clock = clock;
    }

    public synchronized Reply handle(int fileId, int major, int minor, byte[] input) {
        if (closed) {
            return fail(major, NtStatus.UNSUCCESSFUL);
        }
        try {
            return switch (major) {
                case IRP_MJ_CREATE -> create();
                case IRP_MJ_WRITE -> write(fileId, input);
                case IRP_MJ_CLOSE -> close(fileId);
                // FreeRDP's printer answers every device control with an empty output; so does this.
                case IRP_MJ_DEVICE_CONTROL -> new Reply(NtStatus.SUCCESS, new byte[4]);
                default -> fail(major, NtStatus.NOT_SUPPORTED);
            };
        } catch (IOException | RuntimeException e) {
            LOG.warn("print job request failed", e);
            abandon("jterm could not store the print job: " + e.getMessage());
            return fail(major, NtStatus.UNSUCCESSFUL);
        }
    }

    private Reply create() throws IOException {
        if (current != null) {
            return fail(IRP_MJ_CREATE, STATUS_DEVICE_BUSY);
        }
        if (!queue.mayStartJob()) {
            refused.accept("A print job was refused: " + PrintJobQueue.MAX_PENDING_JOBS
                    + " jobs are already waiting. Print, save or discard some first.");
            return fail(IRP_MJ_CREATE, STATUS_PRINT_QUEUE_FULL);
        }
        Path f = folder.newJobFile();
        int id = nextFileId++;
        if (nextFileId == 0) {
            nextFileId = 1;
        }
        current = new Job(id, f, Files.newOutputStream(f));
        return new Reply(NtStatus.SUCCESS, le32(id));
    }

    private Reply write(int fileId, byte[] input) throws IOException {
        Job j = current;
        if (j == null || j.fileId != fileId || input.length < 32) {
            return fail(IRP_MJ_WRITE, NtStatus.INVALID_HANDLE);
        }
        ByteBuffer in = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        long length = in.getInt() & 0xFFFFFFFFL;
        if (length > input.length - 32) {
            return fail(IRP_MJ_WRITE, NtStatus.INVALID_PARAMETER);
        }
        if (j.failure == null) {
            long total = j.written + length;
            if (!queue.mayHold(total)) {
                j.failure = queue.tooLargeForOneJob(total)
                        ? "A print job was refused: it is larger than " + (PrintJobQueue.MAX_JOB_BYTES >> 20) + " MB."
                        : "A print job was refused: the jobs waiting already take "
                          + (PrintJobQueue.MAX_PENDING_BYTES >> 20) + " MB. Print, save or discard some first.";
            } else if (total - j.checkedAt >= ROOM_CHECK_STEP || j.written == 0) {
                j.checkedAt = total;
                if (!room.test(Math.max(length, ROOM_CHECK_STEP))) {
                    j.failure = "A print job was refused: the disk is almost full.";
                }
            }
            if (j.failure != null) {
                // Drop what came so far now; the rest of this job is refused as it arrives.
                j.out.close();
                PrintJobFolder.deleteQuietly(j.file);
                refused.accept(j.failure);
                return fail(IRP_MJ_WRITE, NtStatus.DISK_FULL);
            }
            j.out.write(input, 32, (int) length);
            j.written = total;
            ByteBuffer out = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt((int) length);
            return new Reply(NtStatus.SUCCESS, out.array());
        }
        return fail(IRP_MJ_WRITE, NtStatus.DISK_FULL);
    }

    private Reply close(int fileId) throws IOException {
        Job j = current;
        if (j == null || j.fileId != fileId) {
            return fail(IRP_MJ_CLOSE, NtStatus.INVALID_HANDLE);
        }
        current = null;
        if (j.failure != null) {
            return new Reply(NtStatus.SUCCESS, new byte[4]); // already dropped and reported
        }
        j.out.close();
        if (!startsWithPdfMagic(j.file)) {
            PrintJobFolder.deleteQuietly(j.file);
            refused.accept(j.written == 0 ? "The remote desktop sent an empty print job."
                    : "The remote desktop sent a print job in a format jterm doesn't accept (only PDF).");
            return new Reply(NtStatus.SUCCESS, new byte[4]);
        }
        Path pdf = j.file.resolveSibling(j.file.getFileName().toString().replace(".part", ".pdf"));
        Files.move(j.file, pdf, StandardCopyOption.ATOMIC_MOVE);
        queue.add(new PrintJob(nextJobId++, pdf, j.written, clock.instant()));
        return new Reply(NtStatus.SUCCESS, new byte[4]);
    }

    private static boolean startsWithPdfMagic(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return Arrays.equals(in.readNBytes(PDF_MAGIC.length), PDF_MAGIC);
        }
    }

    private void abandon(String why) {
        Job j = current;
        current = null;
        if (j != null) {
            try {
                j.out.close();
            } catch (IOException ignored) {
                // the file goes anyway
            }
            PrintJobFolder.deleteQuietly(j.file);
            if (j.failure == null) {
                refused.accept(why);
            }
        }
    }

    /** Drops a job still being received (the connection ended mid-job). Idempotent, any thread. */
    public synchronized void close() {
        closed = true;
        Job j = current;
        current = null;
        if (j != null) {
            try {
                j.out.close();
            } catch (IOException ignored) {
                // the file goes anyway
            }
            PrintJobFolder.deleteQuietly(j.file);
        }
    }

    /** A failed reply still carries the request's fixed reply fields (the drive design's rule). */
    private static Reply fail(int major, int status) {
        return new Reply(status, new byte[major == IRP_MJ_WRITE ? 5 : 4]);
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
