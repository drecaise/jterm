package com.katmoda.jterm.terminal.rdp.print;

import com.katmoda.jterm.config.AppPaths;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.Orientation;
import org.apache.pdfbox.printing.PDFPageable;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Everything jterm does with a print job's PDF, through PDFBox (printer design D4): count its pages and
 * draw page 1 small for the preview, and print it. The document is the server's and hostile; PDFBox is
 * memory-safe Java, but a crafted file can still make it loop or allocate, so:
 * <ul>
 *   <li>its scratch data goes to files in the job folder, never the heap
 *       ({@link MemoryUsageSetting#setupTempFileOnly()});</li>
 *   <li>a look at a document runs on a thread of its own with {@link #INSPECT_TIMEOUT_SECONDS}; past
 *       it the job can still be saved, but not printed;</li>
 *   <li>at most {@link #MAX_PAGES} pages;</li>
 *   <li>XMP metadata is never read, and PDFBox's font cache lives in jterm's local data folder rather
 *       than a file in the home folder.</li>
 * </ul>
 * What the printer driver or CUPS receives is what Java draws from PDFBox's rendering, never the
 * server's document (R2).
 */
public final class PdfDocuments {

    public static final int MAX_PAGES = 2000;
    static final long INSPECT_TIMEOUT_SECONDS = 20;
    /** Width of the preview thumbnail, in pixels. */
    public static final int THUMBNAIL_WIDTH = 160;

    static {
        // PDFBox maps fonts a PDF doesn't embed onto system fonts, and caches that scan in a file in
        // the home folder unless told otherwise.
        if (System.getProperty("pdfbox.fontcache") == null) {
            try {
                Path cache = AppPaths.localDataDir().resolve("pdfbox");
                Files.createDirectories(cache);
                System.setProperty("pdfbox.fontcache", cache.toString());
            } catch (IOException | SecurityException ignored) {
                // PDFBox then uses its own default
            }
        }
    }

    private PdfDocuments() {
    }

    private static PDDocument load(Path pdf, Path scratch) throws IOException {
        return Loader.loadPDF(pdf.toFile(), MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch.toFile()).streamCache);
    }

    /**
     * Counts the pages and draws page 1, within the limits above. Never throws: a document that can't
     * be read comes back with a {@link PrintJob.Preview#problem()}.
     */
    public static PrintJob.Preview inspect(Path pdf, Path scratch) {
        CompletableFuture<PrintJob.Preview> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                f.complete(inspectNow(pdf, scratch));
            } catch (Throwable e) { // OutOfMemoryError included: the job is unreadable, jterm goes on
                f.complete(PrintJob.Preview.failed("jterm could not read this document."));
            }
        }, "rdp-print-preview");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        try {
            return f.get(INSPECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            t.interrupt();
            return PrintJob.Preview.failed("This document took too long to read, so it can be saved but not printed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PrintJob.Preview.failed("Interrupted.");
        } catch (ExecutionException e) {
            return PrintJob.Preview.failed("jterm could not read this document.");
        }
    }

    static PrintJob.Preview inspectNow(Path pdf, Path scratch) throws IOException {
        try (PDDocument doc = load(pdf, scratch)) {
            if (doc.isEncrypted()) {
                return PrintJob.Preview.failed("This document is encrypted.");
            }
            int pages = doc.getNumberOfPages();
            if (pages < 1) {
                return PrintJob.Preview.failed("This document has no pages.");
            }
            if (pages > MAX_PAGES) {
                return new PrintJob.Preview(pages, null,
                        "This document has more than " + MAX_PAGES + " pages, so it can be saved but not printed.");
            }
            PDRectangle box = doc.getPage(0).getCropBox();
            float widthPt = Math.max(1f, Math.min(box.getWidth(), box.getHeight() * 4));
            float dpi = THUMBNAIL_WIDTH * 72f / widthPt;
            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(true);
            BufferedImage thumb = renderer.renderImageWithDPI(0, Math.min(dpi, 72f), ImageType.RGB);
            return new PrintJob.Preview(pages, thumb, null);
        }
    }

    /**
     * Prints {@code pdf} through {@code job}, whose printer, copies and pages the caller has set (the
     * OS print dialog, or the session's automatic printer). Each page is shrunk to the paper if it is
     * larger, and centred: jobs from Windows arrive as US Letter by default (spike). Blocks; run it
     * off the EDT.
     */
    public static void print(Path pdf, Path scratch, PrinterJob job) throws IOException, PrinterException {
        try (PDDocument doc = load(pdf, scratch)) {
            if (doc.getNumberOfPages() > MAX_PAGES) {
                throw new IOException("more than " + MAX_PAGES + " pages");
            }
            job.setPageable(new PDFPageable(doc, Orientation.AUTO, false, 0f, true));
            job.print();
        }
    }
}
