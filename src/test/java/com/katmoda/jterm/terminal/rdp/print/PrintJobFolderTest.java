package com.katmoda.jterm.terminal.rdp.print;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrintJobFolderTest {

    @TempDir
    Path base;

    @Test
    void foldersOfProcessesThatAreGoneAreSwept_othersKept() throws IOException {
        Files.createDirectories(base.resolve("100"));
        Files.writeString(base.resolve("100/job-1.pdf"), "left by a crash");
        Files.createDirectories(base.resolve("200"));
        Files.writeString(base.resolve("200/job-1.pdf"), "another jterm's pending job");
        Files.createDirectories(base.resolve("notes"));

        PrintJobFolder f = PrintJobFolder.open(base, 300, pid -> pid == 200);

        assertFalse(Files.exists(base.resolve("100")), "dead process: swept");
        assertTrue(Files.exists(base.resolve("200/job-1.pdf")), "a running jterm's jobs are left alone");
        assertTrue(Files.exists(base.resolve("notes")), "not a pid folder: not ours to delete");
        assertEquals(base.resolve("300"), f.dir());
    }

    @Test
    void ownStaleFolderIsEmptiedToo() throws IOException {
        Files.createDirectories(base.resolve("300"));
        Files.writeString(base.resolve("300/job-9.pdf"), "a previous process with this pid");
        PrintJobFolder.open(base, 300, pid -> true);
        assertFalse(Files.exists(base.resolve("300/job-9.pdf")));
    }

    @Test
    void jobFilesAreNewAndOwnerOnly() throws IOException {
        PrintJobFolder f = PrintJobFolder.open(base, 1, pid -> false);
        Path a = f.newJobFile();
        Path b = f.newJobFile();
        assertTrue(Files.isRegularFile(a));
        assertFalse(a.equals(b));
        assumeTrue(Files.getFileStore(a).supportsFileAttributeView("posix"));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(a));
        Set<PosixFilePermission> dir = Files.getPosixFilePermissions(f.dir());
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), dir);
    }
}
