package com.katmoda.jterm.terminal.rdp.print;

import com.katmoda.jterm.terminal.rdp.drive.NtStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpPrintServiceTest {

    @TempDir
    Path base;

    private PrintJobFolder folder;
    private final List<PrintJob> added = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private boolean room = true;

    private final PrintJobQueue.Listener listener = new PrintJobQueue.Listener() {
        @Override
        public void jobAdded(PrintJob job) {
            added.add(job);
        }

        @Override
        public void changed() {
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        folder = PrintJobFolder.open(base, 4242, pid -> false);
    }

    private RdpPrintService service(PrintJobQueue queue) {
        return new RdpPrintService(folder, queue, more -> room, refusals::add,
                Clock.fixed(Instant.parse("2026-11-17T11:44:25Z"), ZoneOffset.UTC));
    }

    private RdpPrintService service() {
        return service(new PrintJobQueue(listener));
    }

    /** IRP_MJ_WRITE's input as the helper forwards it: Length, Offset (always 0 from Windows), 20 bytes padding, data. */
    private static byte[] write(byte[] data) {
        return ByteBuffer.allocate(32 + data.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.length).putLong(0).put(new byte[20]).put(data).array();
    }

    private static int fileId(RdpPrintService.Reply r) {
        assertEquals(NtStatus.SUCCESS, r.ioStatus());
        assertEquals(4, r.output().length, "DR_CREATE_RSP: FileId, as FreeRDP's printer answers");
        return ByteBuffer.wrap(r.output()).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private long filesIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aJobIsCreateWritesClose_andArrivesAsAPdfInTheQueue() throws IOException {
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        byte[] a = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "rest of the document".getBytes(StandardCharsets.US_ASCII);
        RdpPrintService.Reply w1 = svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write(a));
        assertEquals(NtStatus.SUCCESS, w1.ioStatus());
        assertEquals(5, w1.output().length, "DR_WRITE_RSP: Length + padding");
        assertEquals(a.length, ByteBuffer.wrap(w1.output()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        // The second write also says offset 0 (Windows' spooler, measured): appended, not overwritten.
        svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write(b));
        RdpPrintService.Reply c = svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        assertEquals(NtStatus.SUCCESS, c.ioStatus());
        assertEquals(4, c.output().length);

        assertEquals(1, added.size());
        PrintJob job = added.get(0);
        assertTrue(job.file().getFileName().toString().endsWith(".pdf"));
        byte[] expected = new byte[a.length + b.length];
        System.arraycopy(a, 0, expected, 0, a.length);
        System.arraycopy(b, 0, expected, a.length, b.length);
        assertArrayEquals(expected, Files.readAllBytes(job.file()));
        assertEquals(expected.length, job.bytes());
        assertEquals(Instant.parse("2026-11-17T11:44:25Z"), job.received());
        assertTrue(refusals.isEmpty());
    }

    @Test
    void aSecondCreateBeforeCloseIsBusy_soTheSpoolerRetries() {
        RdpPrintService svc = service();
        fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        assertEquals(RdpPrintService.STATUS_DEVICE_BUSY, svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]).ioStatus());
    }

    @Test
    void anythingButAPdfIsDropped() throws IOException {
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%!PS-Adobe-3.0\r\n".getBytes(StandardCharsets.US_ASCII)));
        svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        assertTrue(added.isEmpty());
        assertEquals(1, refusals.size());
        assertTrue(refusals.get(0).contains("only PDF"), refusals.get(0));
        assertEquals(0, filesIn(folder.dir()), "the PostScript is gone from disk");
    }

    @Test
    void anEmptyJobIsDropped() throws IOException {
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        assertTrue(added.isEmpty());
        assertTrue(refusals.get(0).contains("empty"));
        assertEquals(0, filesIn(folder.dir()));
    }

    @Test
    void aJobOverItsCapIsDroppedWhole_neverTruncated() throws IOException {
        RdpPrintService svc = service(new PrintJobQueue(listener, 16, 8, 1 << 20));
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        assertEquals(NtStatus.SUCCESS, svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%PDF-1.7 0123".getBytes())).ioStatus());
        RdpPrintService.Reply over = svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("more than fits".getBytes()));
        assertEquals(NtStatus.DISK_FULL, over.ioStatus());
        assertEquals(5, over.output().length, "a failed write still carries its fixed fields");
        assertEquals(0, filesIn(folder.dir()), "what came so far is deleted at once");
        // The rest of that job keeps failing, and its close leaves nothing behind.
        assertEquals(NtStatus.DISK_FULL, svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("x".getBytes())).ioStatus());
        svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        assertTrue(added.isEmpty());
        assertEquals(1, refusals.size(), "reported once");
        // And the next job is accepted again.
        int next = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        assertTrue(next != id);
    }

    @Test
    void theWaitingJobsCapRefusesANewJob() {
        PrintJobQueue q = new PrintJobQueue(listener, 1 << 20, 1, 1 << 20);
        RdpPrintService svc = service(q);
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%PDF-1.7".getBytes()));
        svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        assertEquals(1, q.size());
        assertEquals(RdpPrintService.STATUS_PRINT_QUEUE_FULL, svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]).ioStatus());
        assertTrue(refusals.get(0).contains("already waiting"));
        q.remove(q.jobs().get(0));
        fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
    }

    @Test
    void theFreeSpaceFloorRefusesAJob() throws IOException {
        room = false;
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        assertEquals(NtStatus.DISK_FULL, svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%PDF-1.7".getBytes())).ioStatus());
        assertTrue(refusals.get(0).contains("disk"));
        assertEquals(0, filesIn(folder.dir()));
    }

    @Test
    void lengthsAndHandlesAreCheckedAgainstTheRequest() {
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        byte[] lying = write("abc".getBytes());
        ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 1000);
        assertEquals(NtStatus.INVALID_PARAMETER, svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, lying).ioStatus());
        assertEquals(NtStatus.INVALID_HANDLE, svc.handle(id + 1, RdpPrintService.IRP_MJ_WRITE, 0, write("a".getBytes())).ioStatus());
        assertEquals(NtStatus.INVALID_HANDLE, svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, new byte[10]).ioStatus());
        assertEquals(NtStatus.INVALID_HANDLE, svc.handle(id + 1, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]).ioStatus());
    }

    @Test
    void otherRequestsAreAnsweredWithoutTouchingAnything() {
        RdpPrintService svc = service();
        RdpPrintService.Reply ioctl = svc.handle(0, RdpPrintService.IRP_MJ_DEVICE_CONTROL, 0, new byte[32]);
        assertEquals(NtStatus.SUCCESS, ioctl.ioStatus());
        assertArrayEquals(new byte[4], ioctl.output(), "OutputBufferLength 0, as FreeRDP's printer");
        RdpPrintService.Reply read = svc.handle(0, 0x03, 0, new byte[32]);
        assertEquals(NtStatus.NOT_SUPPORTED, read.ioStatus());
    }

    @Test
    void closingMidJobDeletesTheJobAndRefusesLaterRequests() throws IOException {
        RdpPrintService svc = service();
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%PDF-1.7".getBytes()));
        svc.close();
        assertEquals(0, filesIn(folder.dir()));
        assertEquals(NtStatus.UNSUCCESSFUL, svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]).ioStatus());
        assertTrue(added.isEmpty());
    }

    @Test
    void aClosedQueueTakesNoJob_andClearDeletesWaitingOnes() throws IOException {
        PrintJobQueue q = new PrintJobQueue(listener);
        RdpPrintService svc = service(q);
        int id = fileId(svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]));
        svc.handle(id, RdpPrintService.IRP_MJ_WRITE, 0, write("%PDF-1.7".getBytes()));
        svc.handle(id, RdpPrintService.IRP_MJ_CLOSE, 0, new byte[0]);
        Path f = q.jobs().get(0).file();
        q.clear();
        assertTrue(Files.notExists(f));
        assertEquals(0, q.size());
        assertEquals(RdpPrintService.STATUS_PRINT_QUEUE_FULL, svc.handle(0, RdpPrintService.IRP_MJ_CREATE, 0, new byte[32]).ioStatus());
    }
}
