package com.katmoda.jterm.terminal.rdp.print;

import com.katmoda.jterm.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

/**
 * Where the remote desktop's print jobs wait: {@code <local data>/rdp-print-jobs/<pid>/}, owner-only.
 * The local data folder never roams with a Windows profile ({@link AppPaths#localDataDir()}), and the
 * per-process subfolder keeps a second running jterm's pending jobs safe from this one's clean-up.
 * Folders left by processes that no longer run (a crash) are deleted the first time a folder is
 * opened in a process.
 */
public final class PrintJobFolder {

    private static final Logger LOG = LoggerFactory.getLogger(PrintJobFolder.class);

    static final String NAME = "rdp-print-jobs";

    /** A job may not leave the volume with less than this much space... */
    static final long MIN_FREE_BYTES = 1L << 30;
    /** ...or less than this share of it free, whichever is larger (the drive design's floor). */
    static final double MIN_FREE_FRACTION = 0.05;

    private static PrintJobFolder forThisProcess;

    private final Path dir;
    private final AtomicLong counter = new AtomicLong();

    private PrintJobFolder(Path dir) {
        this.dir = dir;
    }

    /** This process's folder, created (and stale folders swept) on first use. */
    public static synchronized PrintJobFolder forThisProcess() throws IOException {
        if (forThisProcess == null) {
            forThisProcess = open(AppPaths.localDataDir().resolve(NAME), ProcessHandle.current().pid(),
                    pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        return forThisProcess;
    }

    /** Testable: {@code base} holds one folder per pid; {@code alive} says which pids still run. */
    static PrintJobFolder open(Path base, long pid, LongPredicate alive) throws IOException {
        Files.createDirectories(base);
        AppPaths.restrictDirToOwner(base);
        sweep(base, pid, alive);
        Path dir = base.resolve(Long.toString(pid));
        Files.createDirectories(dir);
        AppPaths.restrictDirToOwner(dir);
        return new PrintJobFolder(dir);
    }

    private static void sweep(Path base, long self, LongPredicate alive) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                long pid;
                try {
                    pid = Long.parseLong(n);
                } catch (NumberFormatException e) {
                    continue; // not ours: leave it
                }
                if (pid == self || !alive.test(pid)) {
                    deleteTree(p); // our own folder too: a reused pid can't have pending jobs of ours
                }
            }
        } catch (IOException e) {
            LOG.debug("could not sweep {}", base, e);
        }
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    LOG.debug("could not delete {}", f, e);
                }
            });
        } catch (IOException e) {
            LOG.debug("could not delete {}", p, e);
        }
    }

    Path dir() {
        return dir;
    }

    /** A new, empty, owner-only file for a job being received. */
    Path newJobFile() throws IOException {
        Path f = dir.resolve("job-" + counter.incrementAndGet() + ".part");
        Files.createFile(f);
        AppPaths.restrictToOwner(f);
        return f;
    }

    /** Where PDFBox keeps its scratch files for this process's jobs (created on demand). */
    public Path scratchDir() throws IOException {
        Path d = dir.resolve("scratch");
        Files.createDirectories(d);
        AppPaths.restrictDirToOwner(d);
        return d;
    }

    /** Whether {@code more} bytes still fit above the free-space floor of this folder's volume. */
    boolean hasRoomFor(long more) {
        try {
            FileStore store = Files.getFileStore(dir);
            long floor = Math.max(MIN_FREE_BYTES, (long) (store.getTotalSpace() * MIN_FREE_FRACTION));
            return store.getUsableSpace() - more >= floor;
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            LOG.debug("could not delete {}", f, e);
        }
    }
}
