package com.katmoda.jterm.terminal.rdp.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentsTest {

    @TempDir
    Path dir;

    private Path pdf(int pages, PDRectangle size) throws IOException {
        Path f = dir.resolve("doc-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                if (i == 0) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.addRect(50, 50, 200, 100);
                        cs.fill();
                    }
                }
            }
            doc.save(f.toFile());
        }
        return f;
    }

    @Test
    void countsPagesAndDrawsAThumbnailOfPageOne() throws IOException {
        PrintJob.Preview p = PdfDocuments.inspect(pdf(3, PDRectangle.LETTER), dir);
        assertNull(p.problem());
        assertTrue(p.printable());
        assertEquals(3, p.pages());
        assertNotNull(p.thumbnail());
        assertTrue(Math.abs(p.thumbnail().getWidth() - PdfDocuments.THUMBNAIL_WIDTH) <= 2, "width " + p.thumbnail().getWidth());
        // The filled rectangle shows: not every pixel is white.
        boolean dark = false;
        for (int y = 0; y < p.thumbnail().getHeight() && !dark; y++) {
            for (int x = 0; x < p.thumbnail().getWidth() && !dark; x++) {
                dark = (p.thumbnail().getRGB(x, y) & 0xFF) < 128;
            }
        }
        assertTrue(dark);
    }

    @Test
    void aDocumentOverThePageCapCanBeSavedButNotPrinted() throws IOException {
        PrintJob.Preview p = PdfDocuments.inspect(pdf(PdfDocuments.MAX_PAGES + 1, new PDRectangle(20, 20)), dir);
        assertFalse(p.printable());
        assertEquals(PdfDocuments.MAX_PAGES + 1, p.pages());
        assertNull(p.thumbnail(), "not even page 1 is drawn");
    }

    @Test
    void garbageAfterAPdfHeaderIsAProblem_notAnException() throws IOException {
        Path f = dir.resolve("bad.pdf");
        byte[] junk = new byte[64 * 1024];
        new Random(7).nextBytes(junk);
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, junk, 0, head.length);
        Files.write(f, junk);
        PrintJob.Preview p = PdfDocuments.inspect(f, dir);
        assertFalse(p.printable());
        assertNotNull(p.problem());
    }

    @Test
    void aTruncatedRealDocumentIsAProblemOrReadable_neverAHang() throws IOException {
        byte[] whole = Files.readAllBytes(pdf(2, PDRectangle.A4));
        for (int cut : new int[]{10, whole.length / 3, whole.length / 2, whole.length - 20}) {
            Path f = dir.resolve("cut-" + cut + ".pdf");
            Files.write(f, java.util.Arrays.copyOf(whole, cut));
            PrintJob.Preview p = PdfDocuments.inspect(f, dir); // returns within the time limit either way
            assertTrue(p.printable() ? p.pages() > 0 : p.problem() != null);
        }
    }
}
