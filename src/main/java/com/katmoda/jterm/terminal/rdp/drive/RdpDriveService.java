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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The file-system side of RDP drive redirection (MS-RDPEFS): answers the I/O requests the server
 * sends for the shared folders. The helper only forwards them; every decision is made here.
 *
 * <p>Supported: the request set FreeRDP's own drive device implements, so Windows Explorer,
 * {@code copy}, {@code robocopy} and Office behave the same. Change notification is not supported
 * (Explorer then needs F5 to see local changes). Paths go through {@link DrivePath} and
 * {@link DriveShare#resolve}; nothing else turns a server path into a local one.</p>
 *
 * <p>Differences from FreeRDP's drive device, all deliberate: links and reparse points are never
 * followed or listed; deleting a directory deletes only an empty one (FreeRDP deletes recursively);
 * the allocation size in a create is a hint, not a reason to extend the file; a read-only share
 * refuses every write path with {@code STATUS_MEDIA_WRITE_PROTECTED}; growth that would leave the
 * volume below {@link Limits#minFreeBytes} is refused with {@code STATUS_DISK_FULL}; open handles are
 * capped.</p>
 *
 * <p>{@link #handle} is synchronous and thread-safe for different FileIds; the caller (the RDP
 * pane) runs requests for one FileId in order.</p>
 */
public final class RdpDriveService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RdpDriveService.class);

    // MS-RDPEFS major functions
    static final int IRP_MJ_CREATE = 0x00;
    static final int IRP_MJ_CLOSE = 0x02;
    static final int IRP_MJ_READ = 0x03;
    static final int IRP_MJ_WRITE = 0x04;
    static final int IRP_MJ_QUERY_INFORMATION = 0x05;
    static final int IRP_MJ_SET_INFORMATION = 0x06;
    static final int IRP_MJ_QUERY_VOLUME_INFORMATION = 0x0A;
    static final int IRP_MJ_SET_VOLUME_INFORMATION = 0x0B;
    static final int IRP_MJ_DIRECTORY_CONTROL = 0x0C;
    static final int IRP_MJ_DEVICE_CONTROL = 0x0E;
    static final int IRP_MJ_LOCK_CONTROL = 0x11;
    static final int IRP_MN_QUERY_DIRECTORY = 0x01;

    // create dispositions and options
    static final int FILE_SUPERSEDE = 0;
    static final int FILE_OPEN = 1;
    static final int FILE_CREATE = 2;
    static final int FILE_OPEN_IF = 3;
    static final int FILE_OVERWRITE = 4;
    static final int FILE_OVERWRITE_IF = 5;
    static final int FILE_DIRECTORY_FILE = 0x1;
    static final int FILE_NON_DIRECTORY_FILE = 0x40;
    static final int FILE_DELETE_ON_CLOSE = 0x1000;
    // create results
    static final int FILE_SUPERSEDED = 0;
    static final int FILE_OPENED = 1;
    static final int FILE_OVERWRITTEN = 3;

    // access mask bits that imply changing something
    static final int FILE_WRITE_DATA = 0x2;
    static final int FILE_APPEND_DATA = 0x4;
    static final int FILE_WRITE_EA = 0x10;
    static final int FILE_DELETE_CHILD = 0x40;
    static final int FILE_WRITE_ATTRIBUTES = 0x100;
    static final int DELETE = 0x10000;
    static final int WRITE_DAC = 0x40000;
    static final int WRITE_OWNER = 0x80000;
    static final int MAXIMUM_ALLOWED = 0x02000000;
    static final int GENERIC_ALL = 0x10000000;
    static final int GENERIC_WRITE = 0x40000000;
    static final int WRITE_BITS = FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA | FILE_DELETE_CHILD
            | FILE_WRITE_ATTRIBUTES | DELETE | WRITE_DAC | WRITE_OWNER | GENERIC_ALL | GENERIC_WRITE;
    static final int DATA_WRITE_BITS = FILE_WRITE_DATA | FILE_APPEND_DATA | GENERIC_ALL | GENERIC_WRITE;

    // file information classes
    static final int FILE_DIRECTORY_INFORMATION = 1;
    static final int FILE_FULL_DIRECTORY_INFORMATION = 2;
    static final int FILE_BOTH_DIRECTORY_INFORMATION = 3;
    static final int FILE_BASIC_INFORMATION = 4;
    static final int FILE_STANDARD_INFORMATION = 5;
    static final int FILE_RENAME_INFORMATION = 10;
    static final int FILE_NAMES_INFORMATION = 12;
    static final int FILE_DISPOSITION_INFORMATION = 13;
    static final int FILE_ALLOCATION_INFORMATION = 19;
    static final int FILE_END_OF_FILE_INFORMATION = 20;
    static final int FILE_ATTRIBUTE_TAG_INFORMATION = 35;
    // volume information classes
    static final int FILE_FS_VOLUME_INFORMATION = 1;
    static final int FILE_FS_SIZE_INFORMATION = 3;
    static final int FILE_FS_DEVICE_INFORMATION = 4;
    static final int FILE_FS_ATTRIBUTE_INFORMATION = 5;
    static final int FILE_FS_FULL_SIZE_INFORMATION = 7;

    // file attributes
    static final int ATTR_READONLY = 0x1;
    static final int ATTR_HIDDEN = 0x2;
    static final int ATTR_SYSTEM = 0x4;
    static final int ATTR_DIRECTORY = 0x10;
    static final int ATTR_ARCHIVE = 0x20;

    private static final int FILE_CASE_PRESERVED_NAMES = 0x2;
    private static final int FILE_UNICODE_ON_DISK = 0x4;
    private static final int FILE_READ_ONLY_VOLUME = 0x80000;
    private static final int FILE_DEVICE_DISK = 0x7;
    private static final int FILE_READ_ONLY_DEVICE = 0x2;
    private static final int SECTORS_PER_UNIT = 8;
    private static final int BYTES_PER_SECTOR = 512;
    /** 100 ns intervals between 1601-01-01 (FILETIME's epoch) and 1970-01-01. */
    private static final long FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** What a server can make this service hold or do. */
    public record Limits(int maxHandles, int maxRead, int maxListing, long minFreeBytes, double minFreeFraction) {
        public static final Limits DEFAULT = new Limits(1024, 16 << 20, 100_000, 1L << 30, 0.05);
    }

    /** One answer: the NTSTATUS and the body that follows the device I/O completion header. */
    public record Reply(int ioStatus, byte[] output) {
    }

    private final Map<Integer, DriveShare> shares;
    private final Limits limits;
    private final OriginMark originMark;
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();
    private final AtomicInteger nextFileId = new AtomicInteger(1);
    private volatile boolean closed;

    public RdpDriveService(List<DriveShare> shares) {
        this(shares, Limits.DEFAULT, OriginMark.forThisPlatform());
    }

    RdpDriveService(List<DriveShare> shares, Limits limits, OriginMark originMark) {
        Map<Integer, DriveShare> m = new ConcurrentHashMap<>();
        for (DriveShare s : shares) {
            m.put(s.id(), s);
        }
        this.shares = m;
        this.limits = limits;
        this.originMark = originMark;
    }

    /** An open file or directory. Methods on it run under its own lock. */
    private static final class Handle {
        final DriveShare share;
        Path path;
        final boolean directory;
        final boolean isRoot;
        final FileChannel channel; // null for a directory
        final boolean canWrite;
        boolean deletePending;
        List<Entry> listing;
        int cursor;

        Handle(DriveShare share, Path path, boolean directory, boolean isRoot, FileChannel channel,
               boolean canWrite, boolean deleteOnClose) {
            this.share = share;
            this.path = path;
            this.directory = directory;
            this.isRoot = isRoot;
            this.channel = channel;
            this.canWrite = canWrite;
            this.deletePending = deleteOnClose;
        }
    }

    /** One directory listing entry, taken when the listing starts. */
    private record Entry(String name, Stat stat) {
    }

    /** What every information reply needs about a file. */
    record Stat(boolean directory, long size, long creation, long access, long write, int attributes) {
    }

    // ---------------------------------------------------------------------------------- entry

    /**
     * Answers one request. {@code input} is the request body after the 20-byte header; never
     * throws, whatever it holds.
     */
    public Reply handle(int deviceId, int fileId, int major, int minor, byte[] input) {
        Out out = new Out();
        int status;
        try {
            if (closed) {
                throw new DriveException(NtStatus.UNSUCCESSFUL);
            }
            DriveShare share = shares.get(deviceId);
            if (share == null) {
                throw new DriveException(NtStatus.INVALID_HANDLE);
            }
            ByteBuffer in = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
            status = dispatch(share, deviceId, fileId, major, minor, in, out);
        } catch (DriveException e) {
            status = e.status;
            out.failureBody(major, minor);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            status = NtStatus.INVALID_PARAMETER;
            out.failureBody(major, minor);
        } catch (RuntimeException e) {
            LOG.debug("drive request failed", e);
            status = NtStatus.UNSUCCESSFUL;
            out.failureBody(major, minor);
        }
        return new Reply(status, out.bytes());
    }

    private int dispatch(DriveShare share, int deviceId, int fileId, int major, int minor, ByteBuffer in,
                         Out out) throws DriveException {
        switch (major) {
            case IRP_MJ_CREATE:
                return create(share, deviceId, in, out);
            case IRP_MJ_CLOSE:
                return close(deviceId, fileId, out);
            case IRP_MJ_READ:
                return read(handle(deviceId, fileId), in, out);
            case IRP_MJ_WRITE:
                return write(handle(deviceId, fileId), in, out);
            case IRP_MJ_QUERY_INFORMATION:
                return queryInformation(handle(deviceId, fileId), in, out);
            case IRP_MJ_SET_INFORMATION:
                return setInformation(handle(deviceId, fileId), in, out);
            case IRP_MJ_QUERY_VOLUME_INFORMATION:
                return queryVolume(share, in, out);
            case IRP_MJ_DIRECTORY_CONTROL:
                if (minor == IRP_MN_QUERY_DIRECTORY) {
                    return queryDirectory(handle(deviceId, fileId), in, out);
                }
                out.u32(0); // Length; change notification is not supported
                return NtStatus.NOT_SUPPORTED;
            case IRP_MJ_DEVICE_CONTROL:
                out.u32(0); // OutputBufferLength: no I/O controls, answered as FreeRDP's drive does
                return NtStatus.SUCCESS;
            case IRP_MJ_LOCK_CONTROL:
                out.u32(0); // byte-range locks accepted without locking, as FreeRDP's drive does
                return NtStatus.SUCCESS;
            default: // SET_VOLUME_INFORMATION and anything unknown
                return NtStatus.NOT_SUPPORTED;
        }
    }

    private static long key(int deviceId, int fileId) {
        return ((long) deviceId << 32) | (fileId & 0xFFFFFFFFL);
    }

    private Handle handle(int deviceId, int fileId) throws DriveException {
        Handle h = handles.get(key(deviceId, fileId));
        if (h == null) {
            throw new DriveException(NtStatus.INVALID_HANDLE);
        }
        return h;
    }

    // --------------------------------------------------------------------------------- create

    private int create(DriveShare share, int deviceId, ByteBuffer in, Out out) throws DriveException {
        int access = in.getInt();
        in.getLong(); // AllocationSize: a hint, never a reason to extend the file
        in.getInt();  // FileAttributes
        in.getInt();  // SharedAccess
        int disposition = in.getInt();
        int options = in.getInt();
        long pathLength = Integer.toUnsignedLong(in.getInt());
        String path = DrivePath.decode(in, pathLength);
        List<String> components = DrivePath.parse(path);

        if (handles.size() >= limits.maxHandles()) {
            throw new DriveException(NtStatus.INSUFFICIENT_RESOURCES);
        }
        DriveShare.Resolved r = share.resolve(components);
        boolean exists = r.attrs() != null;
        boolean wantsDir = (options & FILE_DIRECTORY_FILE) != 0;
        boolean deleteOnClose = (options & FILE_DELETE_ON_CLOSE) != 0;

        if (share.readOnly()) {
            boolean changes = (access & WRITE_BITS) != 0 || deleteOnClose
                    || !(disposition == FILE_OPEN || (disposition == FILE_OPEN_IF && exists));
            if (changes) {
                throw new DriveException(NtStatus.MEDIA_WRITE_PROTECTED);
            }
        }
        if ((r.isRoot()) && (deleteOnClose || disposition == FILE_CREATE)) {
            throw new DriveException(r.isRoot() && disposition == FILE_CREATE
                    ? NtStatus.OBJECT_NAME_COLLISION : NtStatus.ACCESS_DENIED);
        }

        int information;
        Handle h;
        if (exists && r.attrs().isDirectory()) {
            if (disposition == FILE_CREATE) {
                throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
            }
            if ((options & FILE_NON_DIRECTORY_FILE) != 0) {
                throw new DriveException(NtStatus.FILE_IS_A_DIRECTORY);
            }
            h = new Handle(share, r.path(), true, r.isRoot(), null, false, deleteOnClose);
            information = FILE_OPENED;
        } else if (exists) {
            if (wantsDir) {
                throw new DriveException(NtStatus.NOT_A_DIRECTORY);
            }
            if (disposition == FILE_CREATE) {
                throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
            }
            boolean truncate = disposition == FILE_SUPERSEDE || disposition == FILE_OVERWRITE
                    || disposition == FILE_OVERWRITE_IF;
            h = openFile(share, r.path(), access, truncate, false, deleteOnClose);
            if (truncate) {
                originMark.mark(r.path());
            }
            information = disposition == FILE_SUPERSEDE ? FILE_SUPERSEDED
                    : truncate ? FILE_OVERWRITTEN : FILE_OPENED;
        } else {
            if (disposition == FILE_OPEN || disposition == FILE_OVERWRITE) {
                throw new DriveException(NtStatus.OBJECT_NAME_NOT_FOUND);
            }
            if (wantsDir) {
                try {
                    Files.createDirectory(r.path());
                } catch (FileAlreadyExistsException e) {
                    throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
                } catch (AccessDeniedException e) {
                    throw new DriveException(NtStatus.ACCESS_DENIED);
                } catch (IOException e) {
                    throw new DriveException(NtStatus.UNSUCCESSFUL);
                }
                h = new Handle(share, r.path(), true, false, null, false, deleteOnClose);
            } else {
                h = openFile(share, r.path(), access, false, true, deleteOnClose);
                originMark.mark(r.path());
            }
            information = FILE_SUPERSEDED; // MS-RDPEFS: "a new file was created"
        }

        int fileId = nextFileId.getAndIncrement();
        if (fileId == 0) {
            fileId = nextFileId.getAndIncrement();
        }
        handles.put(key(deviceId, fileId), h);
        out.u32(fileId);
        out.u8(information);
        return NtStatus.SUCCESS;
    }

    private static Handle openFile(DriveShare share, Path path, int access, boolean truncate, boolean createNew,
                                   boolean deleteOnClose) throws DriveException {
        boolean writable = !share.readOnly()
                && ((access & DATA_WRITE_BITS) != 0 || (access & MAXIMUM_ALLOWED) != 0 || truncate || createNew);
        Set<OpenOption> opts = new HashSet<>();
        opts.add(StandardOpenOption.READ);
        opts.add(LinkOption.NOFOLLOW_LINKS);
        if (writable) {
            opts.add(StandardOpenOption.WRITE);
        }
        if (createNew) {
            opts.add(StandardOpenOption.CREATE_NEW);
        }
        if (truncate) {
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        FileChannel ch;
        try {
            ch = FileChannel.open(path, opts);
        } catch (AccessDeniedException e) {
            boolean explicitWrite = (access & DATA_WRITE_BITS) != 0 || truncate || createNew;
            if (!writable || explicitWrite) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            writable = false; // MAXIMUM_ALLOWED on a file we may only read
            opts.remove(StandardOpenOption.WRITE);
            try {
                ch = FileChannel.open(path, opts);
            } catch (IOException e2) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
        } catch (FileAlreadyExistsException e) {
            throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
        } catch (NoSuchFileException e) {
            throw new DriveException(NtStatus.OBJECT_NAME_NOT_FOUND);
        } catch (IOException e) {
            // includes ELOOP from NOFOLLOW_LINKS: a link appeared since the check
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
        return new Handle(share, path, false, false, ch, writable, deleteOnClose);
    }

    // ---------------------------------------------------------------------------------- close

    private int close(int deviceId, int fileId, Out out) {
        out.zeros(4); // Padding
        Handle h = handles.remove(key(deviceId, fileId));
        if (h == null) {
            return NtStatus.UNSUCCESSFUL;
        }
        synchronized (h) {
            return closeHandle(h);
        }
    }

    private static int closeHandle(Handle h) {
        int status = NtStatus.SUCCESS;
        if (h.channel != null) {
            try {
                h.channel.close();
            } catch (IOException e) {
                status = NtStatus.UNSUCCESSFUL;
            }
        }
        if (h.deletePending && !h.isRoot && !h.share.readOnly()) {
            try {
                BasicFileAttributes a = DriveShare.stat(h.path);
                if (a != null && !a.isSymbolicLink() && !a.isOther()) {
                    Files.delete(h.path); // a directory only when empty: never recursive
                }
            } catch (DirectoryNotEmptyException e) {
                status = NtStatus.DIRECTORY_NOT_EMPTY;
            } catch (IOException | DriveException e) {
                status = NtStatus.CANNOT_DELETE;
            }
        }
        return status;
    }

    // ------------------------------------------------------------------------------ read/write

    private int read(Handle h, ByteBuffer in, Out out) throws DriveException {
        long length = Integer.toUnsignedLong(in.getInt());
        long offset = in.getLong();
        synchronized (h) {
            if (h.channel == null) {
                throw new DriveException(NtStatus.INVALID_PARAMETER);
            }
            if (offset < 0) {
                throw new DriveException(NtStatus.INVALID_PARAMETER);
            }
            int want = (int) Math.min(length, limits.maxRead());
            ByteBuffer buf = ByteBuffer.allocate(want);
            try {
                long pos = offset;
                while (buf.hasRemaining()) {
                    int n = h.channel.read(buf, pos);
                    if (n <= 0) {
                        break;
                    }
                    pos += n;
                }
            } catch (IOException e) {
                throw new DriveException(NtStatus.UNSUCCESSFUL);
            }
            out.u32(buf.position());
            out.bytes(buf.array(), 0, buf.position());
            return NtStatus.SUCCESS;
        }
    }

    private int write(Handle h, ByteBuffer in, Out out) throws DriveException {
        long length = Integer.toUnsignedLong(in.getInt());
        long offset = in.getLong();
        in.position(in.position() + 20); // Padding
        if (length > in.remaining() || offset < 0) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        synchronized (h) {
            if (h.share.readOnly()) {
                throw new DriveException(NtStatus.MEDIA_WRITE_PROTECTED);
            }
            if (h.channel == null || !h.canWrite) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            try {
                long growth = offset + length - h.channel.size();
                ensureSpace(h.share, growth);
                ByteBuffer data = in.slice(in.position(), (int) length);
                long pos = offset;
                while (data.hasRemaining()) {
                    pos += h.channel.write(data, pos);
                }
            } catch (IOException e) {
                throw new DriveException(NtStatus.UNSUCCESSFUL);
            }
            out.u32((int) length);
            out.u8(0); // Padding
            return NtStatus.SUCCESS;
        }
    }

    /** Refuses growth that would leave the share's volume below the free-space floor. */
    private void ensureSpace(DriveShare share, long growth) throws DriveException {
        if (growth <= 0) {
            return;
        }
        try {
            FileStore store = Files.getFileStore(share.root());
            long floor = Math.max(limits.minFreeBytes(), (long) (store.getTotalSpace() * limits.minFreeFraction()));
            if (store.getUsableSpace() - growth < floor) {
                throw new DriveException(NtStatus.DISK_FULL);
            }
        } catch (IOException e) {
            throw new DriveException(NtStatus.DISK_FULL);
        }
    }

    // --------------------------------------------------------------------- query information

    private int queryInformation(Handle h, ByteBuffer in, Out out) throws DriveException {
        int cls = in.getInt();
        synchronized (h) {
            Stat s = stat(h.share, h.path);
            switch (cls) {
                case FILE_BASIC_INFORMATION -> {
                    out.u32(36);
                    out.u64(s.creation()).u64(s.access()).u64(s.write()).u64(s.write()); // ChangeTime = write
                    out.u32(s.attributes());
                }
                case FILE_STANDARD_INFORMATION -> {
                    out.u32(22);
                    out.u64(s.size()).u64(s.size());
                    out.u32(1); // NumberOfLinks
                    out.u8(h.deletePending ? 1 : 0);
                    out.u8(s.directory() ? 1 : 0);
                }
                case FILE_ATTRIBUTE_TAG_INFORMATION -> {
                    out.u32(8);
                    out.u32(s.attributes());
                    out.u32(0); // ReparseTag: links are never shown
                }
                default -> throw new DriveException(NtStatus.NOT_SUPPORTED);
            }
            return NtStatus.SUCCESS;
        }
    }

    // ----------------------------------------------------------------------- set information

    private int setInformation(Handle h, ByteBuffer in, Out out) throws DriveException {
        int cls = in.getInt();
        long length = Integer.toUnsignedLong(in.getInt());
        in.position(in.position() + 24); // Padding
        if (length > in.remaining()) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        ByteBuffer body = in.slice(in.position(), (int) length).order(ByteOrder.LITTLE_ENDIAN);
        synchronized (h) {
            if (h.share.readOnly()) {
                throw new DriveException(NtStatus.MEDIA_WRITE_PROTECTED);
            }
            switch (cls) {
                case FILE_BASIC_INFORMATION -> setBasic(h, body, length);
                case FILE_END_OF_FILE_INFORMATION -> setEndOfFile(h, body, length, true);
                case FILE_ALLOCATION_INFORMATION -> setEndOfFile(h, body, length, false);
                case FILE_DISPOSITION_INFORMATION -> setDisposition(h, body, length);
                case FILE_RENAME_INFORMATION -> rename(h, body, length);
                default -> throw new DriveException(NtStatus.NOT_SUPPORTED);
            }
            out.u32((int) length);
            return NtStatus.SUCCESS;
        }
    }

    private void setBasic(Handle h, ByteBuffer b, long length) throws DriveException {
        if (length != 36) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        long creation = b.getLong();
        long access = b.getLong();
        long write = b.getLong();
        long change = b.getLong();
        int attributes = b.getInt();
        if (change != 0 && change != -1 && Long.compareUnsigned(change, write) > 0) {
            write = change;
        }
        BasicFileAttributes a = DriveShare.stat(h.path);
        if (a == null || a.isSymbolicLink() || a.isOther()) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
        try {
            BasicFileAttributeView view = Files.getFileAttributeView(h.path, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            view.setTimes(toFileTime(write), toFileTime(access), toFileTime(creation));
            if (WINDOWS && attributes != 0) {
                DosFileAttributeView dos = Files.getFileAttributeView(h.path, DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (dos != null) {
                    dos.setReadOnly((attributes & ATTR_READONLY) != 0);
                    dos.setHidden((attributes & ATTR_HIDDEN) != 0);
                    dos.setSystem((attributes & ATTR_SYSTEM) != 0);
                    dos.setArchive((attributes & ATTR_ARCHIVE) != 0);
                }
            }
        } catch (IOException e) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
    }

    /** End of file sets the size either way; allocation only ever shrinks a file down to it. */
    private void setEndOfFile(Handle h, ByteBuffer b, long length, boolean endOfFile) throws DriveException {
        if (length != 8) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        long size = b.getLong();
        if (size < 0) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        if (h.channel == null || !h.canWrite) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
        try {
            long current = h.channel.size();
            if (size < current) {
                h.channel.truncate(size);
            } else if (size > current && endOfFile) {
                ensureSpace(h.share, size - current);
                h.channel.write(ByteBuffer.wrap(new byte[1]), size - 1);
            }
        } catch (IOException e) {
            throw new DriveException(NtStatus.UNSUCCESSFUL);
        }
    }

    private static void setDisposition(Handle h, ByteBuffer b, long length) throws DriveException {
        boolean delete = length == 0 || b.get() != 0;
        if (delete) {
            if (h.isRoot) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            if (h.directory) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(h.path)) {
                    if (ds.iterator().hasNext()) {
                        throw new DriveException(NtStatus.DIRECTORY_NOT_EMPTY);
                    }
                } catch (IOException e) {
                    throw new DriveException(NtStatus.CANNOT_DELETE);
                }
            } else if (WINDOWS) {
                try {
                    DosFileAttributes dos = Files.readAttributes(h.path, DosFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (dos.isReadOnly()) {
                        throw new DriveException(NtStatus.CANNOT_DELETE);
                    }
                } catch (IOException e) {
                    throw new DriveException(NtStatus.CANNOT_DELETE);
                }
            }
        }
        h.deletePending = delete;
    }

    private void rename(Handle h, ByteBuffer b, long length) throws DriveException {
        if (length < 6) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        boolean replace = b.get() != 0;
        b.get(); // RootDirectory
        long nameLength = Integer.toUnsignedLong(b.getInt());
        if (length != 6 + nameLength) {
            throw new DriveException(NtStatus.INVALID_PARAMETER);
        }
        if (h.isRoot) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
        List<String> target = DrivePath.parse(DrivePath.decode(b, nameLength));
        if (target.isEmpty()) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        }
        DriveShare.Resolved dst = h.share.resolve(target);
        if (dst.attrs() != null) {
            boolean sameFile;
            try {
                sameFile = Files.isSameFile(h.path, dst.path()); // a change of case only
            } catch (IOException e) {
                sameFile = false;
            }
            if (!sameFile && !replace) {
                throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
            }
            if (!sameFile && dst.attrs().isDirectory()) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
        }
        try {
            if (dst.attrs() != null && replace) {
                Files.move(h.path, dst.path(), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(h.path, dst.path(), LinkOption.NOFOLLOW_LINKS);
            }
        } catch (FileAlreadyExistsException e) {
            throw new DriveException(NtStatus.OBJECT_NAME_COLLISION);
        } catch (AccessDeniedException e) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        } catch (IOException e) {
            throw new DriveException(NtStatus.UNSUCCESSFUL);
        }
        h.path = dst.path();
    }

    // ---------------------------------------------------------------------- volume information

    private int queryVolume(DriveShare share, ByteBuffer in, Out out) throws DriveException {
        int cls = in.getInt();
        long total = 0;
        long usable = 0;
        try {
            FileStore store = Files.getFileStore(share.root());
            total = store.getTotalSpace();
            usable = store.getUsableSpace();
        } catch (IOException e) {
            // reported as an empty volume
        }
        long unit = (long) SECTORS_PER_UNIT * BYTES_PER_SECTOR;
        switch (cls) {
            case FILE_FS_VOLUME_INFORMATION -> {
                byte[] label = (share.name() + "\0").getBytes(StandardCharsets.UTF_16LE);
                Stat root = stat(share, share.root());
                out.u32(17 + label.length);
                out.u64(root.creation());
                out.u32(share.name().hashCode() ^ share.id()); // VolumeSerialNumber
                out.u32(label.length);
                out.u8(0); // SupportsObjects
                out.bytes(label, 0, label.length);
            }
            case FILE_FS_SIZE_INFORMATION -> {
                out.u32(24);
                out.u64(total / unit).u64(usable / unit);
                out.u32(SECTORS_PER_UNIT).u32(BYTES_PER_SECTOR);
            }
            case FILE_FS_FULL_SIZE_INFORMATION -> {
                out.u32(32);
                out.u64(total / unit).u64(usable / unit).u64(usable / unit);
                out.u32(SECTORS_PER_UNIT).u32(BYTES_PER_SECTOR);
            }
            case FILE_FS_ATTRIBUTE_INFORMATION -> {
                // "NTFS", not FreeRDP's "FAT32": Windows refuses to copy a file over 4 GiB onto a
                // FAT32 volume. The flags still say what is really supported (no ACLs, no streams).
                byte[] fsName = "NTFS\0".getBytes(StandardCharsets.UTF_16LE);
                out.u32(12 + fsName.length);
                out.u32(FILE_CASE_PRESERVED_NAMES | FILE_UNICODE_ON_DISK
                        | (share.readOnly() ? FILE_READ_ONLY_VOLUME : 0));
                out.u32(DrivePath.MAX_COMPONENT);
                out.u32(fsName.length);
                out.bytes(fsName, 0, fsName.length);
            }
            case FILE_FS_DEVICE_INFORMATION -> {
                out.u32(8);
                out.u32(FILE_DEVICE_DISK);
                out.u32(share.readOnly() ? FILE_READ_ONLY_DEVICE : 0);
            }
            default -> throw new DriveException(NtStatus.NOT_SUPPORTED);
        }
        return NtStatus.SUCCESS;
    }

    // ------------------------------------------------------------------------ query directory

    private int queryDirectory(Handle h, ByteBuffer in, Out out) throws DriveException {
        int cls = in.getInt();
        boolean initial = in.get() != 0;
        long pathLength = Integer.toUnsignedLong(in.getInt());
        in.position(in.position() + 23); // Padding
        if (cls != FILE_DIRECTORY_INFORMATION && cls != FILE_FULL_DIRECTORY_INFORMATION
                && cls != FILE_BOTH_DIRECTORY_INFORMATION && cls != FILE_NAMES_INFORMATION) {
            throw new DriveException(NtStatus.NOT_SUPPORTED);
        }
        synchronized (h) {
            if (initial) {
                List<String> parts = DrivePath.parsePattern(DrivePath.decode(in, pathLength));
                String pattern = parts.remove(parts.size() - 1);
                DriveShare.Resolved dir = h.share.resolve(parts);
                if (dir.attrs() == null || !dir.attrs().isDirectory()) {
                    throw new DriveException(NtStatus.OBJECT_PATH_NOT_FOUND);
                }
                h.listing = list(h.share, dir.path(), dir.isRoot(), pattern);
                h.cursor = 0;
                if (h.listing.isEmpty()) {
                    throw new DriveException(NtStatus.NO_SUCH_FILE);
                }
            }
            if (h.listing == null || h.cursor >= h.listing.size()) {
                throw new DriveException(NtStatus.NO_MORE_FILES);
            }
            writeEntry(out, cls, h.listing.get(h.cursor++));
            return NtStatus.SUCCESS;
        }
    }

    private List<Entry> list(DriveShare share, Path dir, boolean isRoot, String pattern) throws DriveException {
        List<Entry> entries = new ArrayList<>();
        Stat self = stat(share, dir);
        if (DrivePath.matches(pattern, ".")) {
            entries.add(new Entry(".", self));
        }
        if (!isRoot && DrivePath.matches(pattern, "..")) {
            entries.add(new Entry("..", self)); // the parent's own attributes are not shown
        }
        List<Entry> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (files.size() >= limits.maxListing()) {
                    break;
                }
                Path fn = p.getFileName();
                if (fn == null || !DrivePath.matches(pattern, fn.toString())) {
                    continue;
                }
                BasicFileAttributes a = DriveShare.stat(p);
                if (a == null || !share.listable(p, a)) {
                    continue;
                }
                try {
                    files.add(new Entry(fn.toString(), stat(share, p)));
                } catch (DriveException gone) {
                    // vanished or became unreadable while listing
                }
            }
        } catch (AccessDeniedException e) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        } catch (IOException e) {
            throw new DriveException(NtStatus.UNSUCCESSFUL);
        }
        files.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        entries.addAll(files);
        return entries;
    }

    private static void writeEntry(Out out, int cls, Entry e) {
        byte[] name = e.name().getBytes(StandardCharsets.UTF_16LE);
        Stat s = e.stat();
        switch (cls) {
            case FILE_NAMES_INFORMATION -> {
                out.u32(12 + name.length);
                out.u32(0).u32(0); // NextEntryOffset, FileIndex
                out.u32(name.length);
            }
            default -> {
                int fixed = cls == FILE_DIRECTORY_INFORMATION ? 64 : cls == FILE_FULL_DIRECTORY_INFORMATION ? 68 : 93;
                out.u32(fixed + name.length);
                out.u32(0).u32(0); // NextEntryOffset, FileIndex
                out.u64(s.creation()).u64(s.access()).u64(s.write()).u64(s.write());
                out.u64(s.size()).u64(s.size()); // EndOfFile, AllocationSize
                out.u32(s.attributes());
                out.u32(name.length);
                if (cls != FILE_DIRECTORY_INFORMATION) {
                    out.u32(0); // EaSize
                }
                if (cls == FILE_BOTH_DIRECTORY_INFORMATION) {
                    out.u8(0); // ShortNameLength
                    out.zeros(24); // ShortName
                }
            }
        }
        out.bytes(name, 0, name.length);
    }

    // --------------------------------------------------------------------------------- helpers

    /** The attributes a reply reports for {@code p}, read without following links. */
    static Stat stat(DriveShare share, Path p) throws DriveException {
        try {
            BasicFileAttributes a;
            int attributes = 0;
            if (WINDOWS) {
                DosFileAttributes dos = Files.readAttributes(p, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                a = dos;
                attributes |= (dos.isReadOnly() ? ATTR_READONLY : 0) | (dos.isHidden() ? ATTR_HIDDEN : 0)
                        | (dos.isSystem() ? ATTR_SYSTEM : 0) | (dos.isArchive() ? ATTR_ARCHIVE : 0);
            } else {
                PosixFileAttributes posix = Files.readAttributes(p, PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                a = posix;
                Path fn = p.getFileName();
                if (fn != null && fn.toString().startsWith(".") && !p.equals(share.root())) {
                    attributes |= ATTR_HIDDEN;
                }
                if (!posix.permissions().contains(PosixFilePermission.OWNER_WRITE)) {
                    attributes |= ATTR_READONLY;
                }
                if (!a.isDirectory()) {
                    attributes |= ATTR_ARCHIVE;
                }
            }
            if (a.isSymbolicLink() || a.isOther()) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            if (a.isDirectory()) {
                attributes |= ATTR_DIRECTORY;
                attributes &= ~ATTR_READONLY; // on a directory it means something else to Windows
            } else if (share.readOnly()) {
                attributes |= ATTR_READONLY;
            }
            return new Stat(a.isDirectory(), a.isDirectory() ? 0 : a.size(),
                    toFiletime(a.creationTime()), toFiletime(a.lastAccessTime()), toFiletime(a.lastModifiedTime()),
                    attributes);
        } catch (NoSuchFileException e) {
            throw new DriveException(NtStatus.OBJECT_NAME_NOT_FOUND);
        } catch (AccessDeniedException e) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        } catch (IOException | UnsupportedOperationException e) {
            throw new DriveException(NtStatus.UNSUCCESSFUL);
        }
    }

    static long toFiletime(FileTime t) {
        if (t == null) {
            return 0;
        }
        Instant i = t.toInstant();
        long hundredNs = Math.addExact(Math.multiplyExact(i.getEpochSecond(), 10_000_000L), i.getNano() / 100);
        return Math.max(0, hundredNs + FILETIME_EPOCH_OFFSET);
    }

    /** A FILETIME to set, or null for "leave unchanged" (0, and -1 which means "stop updating"). */
    static FileTime toFileTime(long filetime) {
        if (filetime == 0 || filetime == -1) {
            return null;
        }
        long sinceEpoch = filetime - FILETIME_EPOCH_OFFSET;
        long seconds = Math.floorDiv(sinceEpoch, 10_000_000L);
        long nanos = Math.floorMod(sinceEpoch, 10_000_000L) * 100;
        return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
    }

    /** Open handles, for tests and the pane's indicator. */
    public int openHandles() {
        return handles.size();
    }

    /** Closes every handle. Pending deletes still happen: the server asked for them before going. */
    @Override
    public void close() {
        closed = true;
        for (Long k : List.copyOf(handles.keySet())) {
            Handle h = handles.remove(k);
            if (h != null) {
                synchronized (h) {
                    closeHandle(h);
                }
            }
        }
    }

    /** A little-endian byte builder for reply bodies. */
    private static final class Out {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        private final ByteBuffer scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

        Out u8(int v) {
            buf.write(v);
            return this;
        }

        Out u32(int v) {
            scratch.clear();
            scratch.putInt(v);
            buf.write(scratch.array(), 0, 4);
            return this;
        }

        Out u64(long v) {
            scratch.clear();
            scratch.putLong(v);
            buf.write(scratch.array(), 0, 8);
            return this;
        }

        Out zeros(int n) {
            for (int i = 0; i < n; i++) {
                buf.write(0);
            }
            return this;
        }

        Out bytes(byte[] b, int off, int len) {
            buf.write(b, off, len);
            return this;
        }

        /**
         * The fixed fields a failed reply still carries, so the server can parse it. Anything
         * written before the failure is discarded first.
         */
        void failureBody(int major, int minor) {
            buf.reset();
            switch (major) {
                case IRP_MJ_CREATE -> u32(0).u8(0);             // FileId, Information
                case IRP_MJ_CLOSE -> zeros(4);                  // Padding
                case IRP_MJ_READ -> u32(0);                     // Length
                case IRP_MJ_WRITE -> u32(0).u8(0);              // Length, Padding
                case IRP_MJ_QUERY_INFORMATION, IRP_MJ_SET_INFORMATION,
                     IRP_MJ_QUERY_VOLUME_INFORMATION, IRP_MJ_DEVICE_CONTROL, IRP_MJ_LOCK_CONTROL -> u32(0);
                case IRP_MJ_DIRECTORY_CONTROL -> {
                    u32(0);
                    if (minor == IRP_MN_QUERY_DIRECTORY) {
                        u8(0); // Padding
                    }
                }
                default -> { }
            }
        }

        byte[] bytes() {
            return buf.toByteArray();
        }
    }
}
