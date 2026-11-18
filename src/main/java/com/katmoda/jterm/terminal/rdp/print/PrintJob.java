package com.katmoda.jterm.terminal.rdp.print;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;

/**
 * One print job the remote desktop sent: a PDF in the job folder (it began with {@code %PDF-}), waiting
 * for the user. Its {@link Preview} is filled in once PDFBox has looked at it.
 */
public final class PrintJob {

    /** What PDFBox found: the page count and a thumbnail of page 1, or why it couldn't tell. */
    public record Preview(int pages, BufferedImage thumbnail, String problem) {
        /** Whether the job may be printed: only a document PDFBox could open within its limits. */
        public boolean printable() {
            return problem == null;
        }

        static Preview failed(String problem) {
            return new Preview(-1, null, problem);
        }
    }

    private final long id;
    private final Path file;
    private final long bytes;
    private final Instant received;
    private volatile Preview preview;

    PrintJob(long id, Path file, long bytes, Instant received) {
        this.id = id;
        this.file = file;
        this.bytes = bytes;
        this.received = received;
    }

    public long id() {
        return id;
    }

    public Path file() {
        return file;
    }

    public long bytes() {
        return bytes;
    }

    public Instant received() {
        return received;
    }

    /** Null until inspected. */
    public Preview preview() {
        return preview;
    }

    public void setPreview(Preview preview) {
        this.preview = preview;
    }
}
