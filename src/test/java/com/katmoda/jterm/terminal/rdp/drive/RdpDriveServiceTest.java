/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.rdp.drive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.katmoda.jterm.terminal.rdp.drive.RdpDriveService.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drive service against real temporary directories, driven with byte-exact MS-RDPEFS request
 * bodies, the way the helper forwards them. Every promise of the design's threat model has a test
 * here: no escape through {@code ..} or links, protected folders invisible, read-only means
 * read-only, resources capped, malformed input harmless.
 */
class RdpDriveServiceTest {

    private static final int DEV = 1;
    private static final int READ = 0x1;           // FILE_READ_DATA
    private static final int WRITE = 0x2 | 0x4;    // FILE_WRITE_DATA | FILE_APPEND_DATA
    private static final int GENERIC_READ = 0x80000000;

    @TempDir
    Path tmp;
    private Path share;
    private Path outside;
    private final List<Path> marked = new CopyOnWriteArrayList<>();
    private RdpDriveService svc;

    @BeforeEach
    void setUp() throws IOException {
        share = Files.createDirectory(tmp.resolve("share"));
        outside = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.writeString(share.resolve("a.txt"), "hello world");
        Files.createDirectory(share.resolve("sub"));
        Files.writeString(share.resolve("sub").resolve("b.txt"), "bee");
        Files.writeString(share.resolve(".hidden"), "h");
        Files.createSymbolicLink(share.resolve("escape"), outside);
        Files.createSymbolicLink(share.resolve("filelink.txt"), outside.resolve("secret.txt"));
        svc = service(false, Limits.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        svc.close();
    }

    private RdpDriveService service(boolean readOnly, Limits limits, Path... protectedFolders) throws IOException {
        if (svc != null) {
            svc.close();
        }
        DriveShare s = DriveShare.open(DEV, "share", share, readOnly, List.of(protectedFolders));
        svc = new RdpDriveService(List.of(s), limits, marked::add);
        return svc;
    }

    // ------------------------------------------------------------------------- request bodies

    private static final class Body {
        final ByteBuffer b = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);

        Body u8(int v) { b.put((byte) v); return this; }
        Body u32(int v) { b.putInt(v); return this; }
        Body u64(long v) { b.putLong(v); return this; }
        Body zeros(int n) { b.put(new byte[n]); return this; }
        Body raw(byte[] x) { b.put(x); return this; }
        byte[] build() { return java.util.Arrays.copyOf(b.array(), b.position()); }
    }

    private static byte[] utf16z(String s) {
        return (s + "\0").getBytes(StandardCharsets.UTF_16LE);
    }

    private static byte[] createBody(String path, int access, int disposition, int options) {
        byte[] p = utf16z(path);
        return new Body().u32(access).u64(0).u32(0).u32(7).u32(disposition).u32(options).u32(p.length).raw(p).build();
    }

    private record Opened(int status, int fileId, int information) {
    }

    private Opened create(String path, int access, int disposition, int options) {
        Reply r = svc.handle(DEV, 0, IRP_MJ_CREATE, 0, createBody(path, access, disposition, options));
        ByteBuffer o = ByteBuffer.wrap(r.output()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5, r.output().length, "CREATE always answers FileId + Information");
        return new Opened(r.ioStatus(), o.getInt(), o.get() & 0xFF);
    }

    private int open(String path) {
        Opened o = create(path, GENERIC_READ, FILE_OPEN, 0);
        assertEquals(NtStatus.SUCCESS, o.status(), "open " + path);
        return o.fileId();
    }

    private Reply read(int fileId, int length, long offset) {
        return svc.handle(DEV, fileId, IRP_MJ_READ, 0, new Body().u32(length).u64(offset).zeros(20).build());
    }

    private Reply write(int fileId, long offset, byte[] data) {
        return svc.handle(DEV, fileId, IRP_MJ_WRITE, 0,
                new Body().u32(data.length).u64(offset).zeros(20).raw(data).build());
    }

    private Reply setInfo(int fileId, int cls, byte[] buffer) {
        return svc.handle(DEV, fileId, IRP_MJ_SET_INFORMATION, 0,
                new Body().u32(cls).u32(buffer.length).zeros(24).raw(buffer).build());
    }

    private Reply rename(int fileId, String target, boolean replace) {
        byte[] name = utf16z(target);
        return setInfo(fileId, FILE_RENAME_INFORMATION,
                new Body().u8(replace ? 1 : 0).u8(0).u32(name.length).raw(name).build());
    }

    private Reply closeFile(int fileId) {
        return svc.handle(DEV, fileId, IRP_MJ_CLOSE, 0, new byte[32]);
    }

    private static String readText(Reply r) {
        ByteBuffer o = ByteBuffer.wrap(r.output()).order(ByteOrder.LITTLE_ENDIAN);
        int n = o.getInt();
        return new String(r.output(), 4, n, StandardCharsets.UTF_8);
    }

    /** Every name a directory query returns for {@code pattern}, in order. */
    private List<String> list(int dirId, String pattern) {
        List<String> names = new ArrayList<>();
        boolean initial = true;
        while (true) {
            byte[] p = utf16z(pattern);
            Reply r = svc.handle(DEV, dirId, IRP_MJ_DIRECTORY_CONTROL, IRP_MN_QUERY_DIRECTORY,
                    new Body().u32(FILE_BOTH_DIRECTORY_INFORMATION).u8(initial ? 1 : 0).u32(p.length).zeros(23)
                            .raw(p).build());
            initial = false;
            if (r.ioStatus() != NtStatus.SUCCESS) {
                assertTrue(r.ioStatus() == NtStatus.NO_MORE_FILES || r.ioStatus() == NtStatus.NO_SUCH_FILE,
                        "end of listing, got " + Integer.toHexString(r.ioStatus()));
                assertEquals(5, r.output().length, "a failed query still has Length + Padding");
                return names;
            }
            ByteBuffer o = ByteBuffer.wrap(r.output()).order(ByteOrder.LITTLE_ENDIAN);
            int length = o.getInt();
            assertEquals(r.output().length - 4, length);
            o.position(4 + 60);
            int nameLength = o.getInt();
            names.add(new String(r.output(), 4 + 93, nameLength, StandardCharsets.UTF_16LE));
        }
    }

    // ------------------------------------------------------------------------ confinement

    @Test
    void readsAFileInTheShare() {
        int id = open("\\a.txt");
        assertEquals("hello world", readText(read(id, 100, 0)));
        assertEquals("world", readText(read(id, 5, 6)));
        assertEquals(NtStatus.SUCCESS, closeFile(id).ioStatus());
        assertEquals(0, svc.openHandles());
    }

    @Test
    void dotDotAndOddNamesNeverLeaveTheShare() {
        for (String p : List.of("\\..\\outside\\secret.txt", "\\sub\\..\\..\\outside\\secret.txt", "\\..",
                "\\sub\\.\\b.txt", "\\a.txt:stream", "\\CON", "\\nul.txt", "\\a.txt.", "\\a.txt ",
                "\\sub/../a.txt", "\\a*", "\\bad\u0001name", "\\\\a.txt")) {
            int status = create(p, GENERIC_READ, FILE_OPEN, 0).status();
            assertTrue(status == NtStatus.OBJECT_NAME_INVALID, p + " -> " + Integer.toHexString(status));
        }
    }

    @Test
    void linksAreNeverFollowedOrListed() {
        assertEquals(NtStatus.ACCESS_DENIED, create("\\escape\\secret.txt", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.ACCESS_DENIED, create("\\escape", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.ACCESS_DENIED, create("\\filelink.txt", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.ACCESS_DENIED,
                create("\\escape\\new.txt", WRITE, FILE_CREATE, 0).status(), "nor created through");

        int root = open("\\");
        List<String> names = list(root, "\\*");
        assertEquals(List.of(".", ".hidden", "a.txt", "sub"), names, "the links are not there at all");
    }

    @Test
    void aProtectedFolderInsideTheShareIsInvisibleAndClosed() throws IOException {
        Path ssh = Files.createDirectory(share.resolve(".ssh"));
        Files.writeString(ssh.resolve("id_ed25519"), "key");
        service(false, Limits.DEFAULT, ssh);

        assertEquals(NtStatus.ACCESS_DENIED, create("\\.ssh\\id_ed25519", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.ACCESS_DENIED, create("\\.ssh", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.ACCESS_DENIED, create("\\.SSH\\id_ed25519", GENERIC_READ, FILE_OPEN, 0).status(),
                "a case variant resolves to the same folder, caught by identity");
        assertFalse(list(open("\\"), "\\*").contains(".ssh"));

        int a = create("\\a.txt", READ | 0x10000, FILE_OPEN, 0).fileId();
        assertEquals(NtStatus.ACCESS_DENIED, rename(a, "\\.ssh\\a.txt", false).ioStatus(), "nothing moves in");
    }

    @Test
    void aShareThatIsOrLiesInsideAProtectedFolderIsRefused() throws IOException {
        assertThrows(IOException.class, () -> DriveShare.open(2, "x", share, true, List.of(share)));
        assertThrows(IOException.class, () -> DriveShare.open(2, "x", share.resolve("sub"), true, List.of(share)));
        assertThrows(IOException.class, () -> DriveShare.open(2, "x", share.resolve("escape"), true, List.of(outside)),
                "even when chosen through a link");
        DriveShare.open(2, "x", share, true, List.of(tmp.resolve("missing"))); // a missing one is ignored
    }

    // -------------------------------------------------------------------------- read-only

    @Test
    void aReadOnlyShareRefusesEveryWayOfChangingSomething() throws IOException {
        service(true, Limits.DEFAULT);
        int wp = NtStatus.MEDIA_WRITE_PROTECTED;
        assertEquals(wp, create("\\a.txt", WRITE, FILE_OPEN, 0).status(), "write access");
        assertEquals(wp, create("\\a.txt", GENERIC_READ | GENERIC_WRITE, FILE_OPEN, 0).status());
        assertEquals(wp, create("\\a.txt", 0x10000, FILE_OPEN, 0).status(), "delete access");
        assertEquals(wp, create("\\a.txt", GENERIC_READ, FILE_OVERWRITE_IF, 0).status(), "truncate");
        assertEquals(wp, create("\\a.txt", GENERIC_READ, FILE_SUPERSEDE, 0).status());
        assertEquals(wp, create("\\new.txt", GENERIC_READ, FILE_CREATE, 0).status(), "create");
        assertEquals(wp, create("\\new.txt", GENERIC_READ, FILE_OPEN_IF, 0).status(), "create if missing");
        assertEquals(wp, create("\\newdir", GENERIC_READ, FILE_CREATE, FILE_DIRECTORY_FILE).status());
        assertEquals(wp, create("\\a.txt", GENERIC_READ, FILE_OPEN, FILE_DELETE_ON_CLOSE).status());

        int id = create("\\a.txt", GENERIC_READ | MAXIMUM_ALLOWED, FILE_OPEN_IF, 0).fileId();
        assertEquals("hello world", readText(read(id, 50, 0)), "reading still works");
        assertEquals(wp, write(id, 0, "x".getBytes()).ioStatus());
        assertEquals(wp, setInfo(id, FILE_END_OF_FILE_INFORMATION, new Body().u64(0).build()).ioStatus());
        assertEquals(wp, setInfo(id, FILE_DISPOSITION_INFORMATION, new byte[]{1}).ioStatus());
        assertEquals(wp, rename(id, "\\b.txt", false).ioStatus());
        closeFile(id);
        assertEquals("hello world", Files.readString(share.resolve("a.txt")));
        assertTrue(Files.exists(share.resolve("a.txt")));
        assertFalse(Files.exists(share.resolve("new.txt")));
        assertTrue(marked.isEmpty());
    }

    @Test
    void aReadOnlyShareSaysSoInItsVolumeInformation() throws IOException {
        service(true, Limits.DEFAULT);
        Reply attr = svc.handle(DEV, 0, IRP_MJ_QUERY_VOLUME_INFORMATION, 0,
                new Body().u32(FILE_FS_ATTRIBUTE_INFORMATION).u32(0).zeros(24).build());
        ByteBuffer o = ByteBuffer.wrap(attr.output()).order(ByteOrder.LITTLE_ENDIAN);
        int length = o.getInt();
        int flags = o.getInt();
        assertEquals(attr.output().length - 4, length);
        assertTrue((flags & 0x80000) != 0, "FILE_READ_ONLY_VOLUME");
        o.getInt();
        int nameLen = o.getInt();
        assertEquals("NTFS\0", new String(attr.output(), 16, nameLen, StandardCharsets.UTF_16LE));
    }

    // ------------------------------------------------------------------------- read-write

    @Test
    void createWriteReadRenameAndDeleteAFile() throws IOException {
        Opened o = create("\\sub\\new.txt", GENERIC_READ | GENERIC_WRITE, FILE_CREATE, FILE_NON_DIRECTORY_FILE);
        assertEquals(NtStatus.SUCCESS, o.status());
        assertEquals(FILE_SUPERSEDED, o.information(), "MS-RDPEFS: a new file was created");
        assertEquals(List.of(share.resolve("sub").resolve("new.txt")), marked, "marked as downloaded");

        Reply w = write(o.fileId(), 0, "data from the server".getBytes(StandardCharsets.UTF_8));
        assertEquals(NtStatus.SUCCESS, w.ioStatus());
        assertEquals(20, ByteBuffer.wrap(w.output()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals("server", readText(read(o.fileId(), 6, 14)));
        assertEquals(NtStatus.SUCCESS,
                setInfo(o.fileId(), FILE_END_OF_FILE_INFORMATION, new Body().u64(4).build()).ioStatus());
        assertEquals(NtStatus.SUCCESS, rename(o.fileId(), "\\renamed.txt", false).ioStatus());
        closeFile(o.fileId());
        assertEquals("data", Files.readString(share.resolve("renamed.txt")));

        int id = create("\\renamed.txt", 0x10000, FILE_OPEN, 0).fileId();
        assertEquals(NtStatus.SUCCESS, setInfo(id, FILE_DISPOSITION_INFORMATION, new byte[]{1}).ioStatus());
        assertTrue(Files.exists(share.resolve("renamed.txt")), "deleted on close, not before");
        assertEquals(NtStatus.SUCCESS, closeFile(id).ioStatus());
        assertFalse(Files.exists(share.resolve("renamed.txt")));
    }

    @Test
    void renameRespectsCollisionsAndReplace() throws IOException {
        Files.writeString(share.resolve("other.txt"), "other");
        int id = create("\\a.txt", READ | 0x10000, FILE_OPEN, 0).fileId();
        assertEquals(NtStatus.OBJECT_NAME_COLLISION, rename(id, "\\other.txt", false).ioStatus());
        assertEquals(NtStatus.ACCESS_DENIED, rename(id, "\\sub", true).ioStatus(), "never replaces a directory");
        assertEquals(NtStatus.OBJECT_NAME_INVALID, rename(id, "\\..\\outside\\x.txt", true).ioStatus());
        assertEquals(NtStatus.ACCESS_DENIED, rename(id, "\\escape\\x.txt", true).ioStatus(), "nor through a link");
        assertEquals(NtStatus.SUCCESS, rename(id, "\\other.txt", true).ioStatus());
        closeFile(id);
        assertEquals("hello world", Files.readString(share.resolve("other.txt")));
        assertTrue(Files.exists(outside.resolve("secret.txt")));
        assertFalse(Files.exists(outside.resolve("x.txt")));
    }

    @Test
    void directoriesAreDeletedOnlyWhenEmpty() throws IOException {
        int sub = create("\\sub", 0x10000, FILE_OPEN, FILE_DIRECTORY_FILE).fileId();
        assertEquals(NtStatus.DIRECTORY_NOT_EMPTY,
                setInfo(sub, FILE_DISPOSITION_INFORMATION, new byte[]{1}).ioStatus());
        closeFile(sub);
        assertTrue(Files.exists(share.resolve("sub").resolve("b.txt")));

        Opened d = create("\\empty", GENERIC_READ, FILE_CREATE, FILE_DIRECTORY_FILE);
        assertEquals(NtStatus.SUCCESS, d.status());
        assertEquals(NtStatus.SUCCESS, setInfo(d.fileId(), FILE_DISPOSITION_INFORMATION, new byte[]{1}).ioStatus());
        closeFile(d.fileId());
        assertFalse(Files.exists(share.resolve("empty")));

        int root = open("\\");
        assertEquals(NtStatus.ACCESS_DENIED, setInfo(root, FILE_DISPOSITION_INFORMATION, new byte[]{1}).ioStatus());
    }

    @Test
    void createDispositionsBehaveAsWindowsExpects() throws IOException {
        assertEquals(NtStatus.OBJECT_NAME_COLLISION, create("\\a.txt", WRITE, FILE_CREATE, 0).status());
        assertEquals(NtStatus.OBJECT_NAME_NOT_FOUND, create("\\nope.txt", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.OBJECT_PATH_NOT_FOUND, create("\\nodir\\x.txt", GENERIC_READ, FILE_OPEN_IF, 0).status());
        assertEquals(NtStatus.FILE_IS_A_DIRECTORY, create("\\sub", GENERIC_READ, FILE_OPEN, FILE_NON_DIRECTORY_FILE).status());
        assertEquals(NtStatus.NOT_A_DIRECTORY, create("\\a.txt", GENERIC_READ, FILE_OPEN, FILE_DIRECTORY_FILE).status());

        Opened ow = create("\\a.txt", WRITE, FILE_OVERWRITE_IF, 0);
        assertEquals(FILE_OVERWRITTEN, ow.information());
        closeFile(ow.fileId());
        assertEquals(0, Files.size(share.resolve("a.txt")), "overwritten means truncated");
        assertEquals(List.of(share.resolve("a.txt")), marked);
    }

    @Test
    void writeNeedsWriteAccessOnTheHandle() {
        int id = open("\\a.txt");
        assertEquals(NtStatus.ACCESS_DENIED, write(id, 0, "x".getBytes()).ioStatus());
    }

    @Test
    void setBasicInformationChangesTimes() throws IOException {
        int id = create("\\a.txt", 0x100, FILE_OPEN, 0).fileId(); // FILE_WRITE_ATTRIBUTES
        long t = RdpDriveService.toFiletime(FileTime.from(Instant.parse("2020-01-02T03:04:05Z")));
        assertEquals(NtStatus.SUCCESS,
                setInfo(id, FILE_BASIC_INFORMATION, new Body().u64(0).u64(0).u64(t).u64(0).u32(0).build()).ioStatus());
        assertEquals(Instant.parse("2020-01-02T03:04:05Z"), Files.getLastModifiedTime(share.resolve("a.txt")).toInstant());
    }

    // ------------------------------------------------------------------------- listings

    @Test
    void directoryQueriesFilterByPatternAndEnd() {
        int root = open("\\");
        assertEquals(List.of("a.txt"), list(root, "\\*.txt"));
        assertEquals(List.of("a.txt"), list(root, "\\A.TXT"), "case-insensitive");
        assertEquals(List.of(), list(root, "\\zzz*"), "no match is NO_SUCH_FILE");
        int sub = open("\\sub");
        assertEquals(List.of(".", "..", "b.txt"), list(sub, "\\sub\\*"));
    }

    @Test
    void patternsMatchLikeWindows() {
        assertTrue(DrivePath.matches("*", "anything"));
        assertTrue(DrivePath.matches("*.*", "noext"));
        assertTrue(DrivePath.matches("a?c", "ABC"));
        assertFalse(DrivePath.matches("a?c", "abbc"));
        assertTrue(DrivePath.matches("<\"txt", "file.txt"), "DOS wildcards");
        assertFalse(DrivePath.matches("*.txt", "file.txt.bak"));
        assertFalse(DrivePath.matches("*a*a*a*a*a*a*a*a*a*b", "a".repeat(200)), "and a hostile pattern ends");
    }

    // -------------------------------------------------------------------------- limits

    @Test
    void openHandlesAreCapped() throws IOException {
        service(false, new Limits(2, 1 << 20, 1000, 0, 0));
        assertEquals(NtStatus.SUCCESS, create("\\a.txt", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.SUCCESS, create("\\a.txt", GENERIC_READ, FILE_OPEN, 0).status());
        assertEquals(NtStatus.INSUFFICIENT_RESOURCES, create("\\a.txt", GENERIC_READ, FILE_OPEN, 0).status());
    }

    @Test
    void growthBelowTheFreeSpaceFloorIsRefusedButOverwritingIsNot() throws IOException {
        service(false, new Limits(16, 1 << 20, 1000, Long.MAX_VALUE / 2, 0));
        int id = create("\\a.txt", GENERIC_READ | GENERIC_WRITE, FILE_OPEN, 0).fileId();
        assertEquals(NtStatus.SUCCESS, write(id, 0, "HELLO".getBytes()).ioStatus(), "within the file: no growth");
        assertEquals(NtStatus.DISK_FULL, write(id, 11, "!".getBytes()).ioStatus());
        assertEquals(NtStatus.DISK_FULL,
                setInfo(id, FILE_END_OF_FILE_INFORMATION, new Body().u64(1L << 40).build()).ioStatus());
        closeFile(id);
        assertEquals("HELLO world", Files.readString(share.resolve("a.txt")));
    }

    @Test
    void readsAreCappedNotRefused() throws IOException {
        service(false, new Limits(16, 4, 1000, 0, 0));
        int id = open("\\a.txt");
        assertEquals("hell", readText(read(id, 1000, 0)));
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    void unknownHandlesAndDevicesAreRefused() {
        assertEquals(NtStatus.INVALID_HANDLE, read(4242, 10, 0).ioStatus());
        assertEquals(4, read(4242, 10, 0).output().length, "still carries Length");
        assertEquals(NtStatus.INVALID_HANDLE, svc.handle(99, 0, IRP_MJ_CREATE, 0,
                createBody("\\a.txt", GENERIC_READ, FILE_OPEN, 0)).ioStatus());
        assertEquals(NtStatus.UNSUCCESSFUL, closeFile(4242).ioStatus());
    }

    @Test
    void truncatedOrLyingRequestsAreRefusedWithAParsableReply() {
        byte[] full = createBody("\\a.txt", GENERIC_READ, FILE_OPEN, 0);
        for (int cut = 0; cut < full.length - 2; cut++) {
            Reply r = svc.handle(DEV, 0, IRP_MJ_CREATE, 0, java.util.Arrays.copyOf(full, cut));
            assertFalse(r.ioStatus() == NtStatus.SUCCESS, "cut at " + cut);
            assertEquals(5, r.output().length);
        }
        int id = create("\\a.txt", GENERIC_READ | GENERIC_WRITE, FILE_OPEN, 0).fileId();
        Reply lying = svc.handle(DEV, id, IRP_MJ_WRITE, 0, new Body().u32(1 << 20).u64(0).zeros(20).raw(new byte[3]).build());
        assertEquals(NtStatus.INVALID_PARAMETER, lying.ioStatus(), "claims more data than it carries");
        assertEquals(NtStatus.INVALID_PARAMETER, read(id, 10, -1).ioStatus(), "negative offset");
    }

    @Test
    void randomRequestsNeverThrowOrEscape() throws IOException {
        Random rnd = new Random(42);
        int[] majors = {IRP_MJ_CREATE, IRP_MJ_CLOSE, IRP_MJ_READ, IRP_MJ_WRITE, IRP_MJ_QUERY_INFORMATION,
                IRP_MJ_SET_INFORMATION, IRP_MJ_QUERY_VOLUME_INFORMATION, IRP_MJ_DIRECTORY_CONTROL,
                IRP_MJ_DEVICE_CONTROL, IRP_MJ_LOCK_CONTROL, 0x7F};
        for (int i = 0; i < 20_000; i++) {
            byte[] body = new byte[rnd.nextInt(80)];
            rnd.nextBytes(body);
            svc.handle(DEV, rnd.nextInt(4), majors[rnd.nextInt(majors.length)], rnd.nextInt(3), body);
        }
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
        assertEquals(1, Files.list(outside).count());
    }

    @Test
    void closingTheServiceClosesEveryHandle() {
        open("\\a.txt");
        open("\\sub");
        svc.close();
        assertEquals(0, svc.openHandles());
        assertEquals(NtStatus.UNSUCCESSFUL, create("\\a.txt", GENERIC_READ, FILE_OPEN, 0).status());
    }

    @Test
    void fileTimesRoundTrip() {
        FileTime t = FileTime.from(Instant.parse("2026-11-09T12:34:56.789Z"));
        assertEquals(t, RdpDriveService.toFileTime(RdpDriveService.toFiletime(t)));
        assertEquals(null, RdpDriveService.toFileTime(0));
        assertEquals(null, RdpDriveService.toFileTime(-1));
        assertArrayEquals(new byte[0], new byte[0]);
    }
}
