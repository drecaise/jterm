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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * One shared folder for one connection, and the only way to turn server path components into a
 * local path.
 *
 * <p>{@link #resolve} walks from the root one component at a time without following links. A
 * component that is a symbolic link, a Windows junction or any other reparse point
 * ({@code isSymbolicLink()} or {@code isOther()}) is refused, and so is a protected folder (the
 * vault's config folder, {@code ~/.ssh}) wherever it appears. Protection is decided by file
 * identity ({@link Files#isSameFile}), not by spelling, so a Windows 8.3 short name, a different
 * case or a bind mount of the same folder is caught too.</p>
 *
 * <p>Check-then-use is sufficient: the server has no operation that creates a link (MS-RDPEFS
 * offers none), and local processes, which could swap one in, already hold the user's rights.</p>
 */
public final class DriveShare {

    /** Directories with more entries than this are not searched for a case-insensitive match. */
    private static final int CASE_SCAN_LIMIT = 10_000;

    private final int id;
    private final String name;
    private final Path root;
    private final boolean readOnly;
    private final List<Path> protectedFolders;

    private DriveShare(int id, String name, Path root, boolean readOnly, List<Path> protectedFolders) {
        this.id = id;
        this.name = name;
        this.root = root;
        this.readOnly = readOnly;
        this.protectedFolders = protectedFolders;
    }

    /**
     * Opens {@code folder} for sharing. Refuses a folder that is missing, not a directory, or is or
     * lies inside one of {@code protectedFolders} (only those that exist are kept). The folder is
     * resolved once here, so a share chosen through a link is fixed to its target for the
     * connection.
     */
    public static DriveShare open(int id, String name, Path folder, boolean readOnly,
                                  List<Path> protectedFolders) throws IOException {
        Path root = folder.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(folder + " is not a folder");
        }
        List<Path> kept = new ArrayList<>();
        for (Path p : protectedFolders) {
            Path real;
            try {
                real = p.toRealPath();
            } catch (IOException missing) {
                continue;
            }
            kept.add(real);
            for (Path a = root; a != null; a = a.getParent()) {
                if (Files.isSameFile(a, real)) {
                    throw new IOException(folder + " holds jterm's own secrets and can't be shared");
                }
            }
        }
        return new DriveShare(id, name, root, readOnly, List.copyOf(kept));
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** A resolved request path; {@code attrs} is null when the final component does not exist. */
    record Resolved(Path path, BasicFileAttributes attrs, boolean isRoot) {
    }

    /**
     * Resolves components to a local path inside the share. Every existing component must be a
     * real directory or (the last one) a real file, never a link or a protected folder; a missing
     * intermediate directory is {@code OBJECT_PATH_NOT_FOUND}, a missing last component is
     * returned with {@code attrs == null}.
     */
    Resolved resolve(List<String> components) throws DriveException {
        Path cur = root;
        BasicFileAttributes attrs = stat(root);
        if (attrs == null || !attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new DriveException(NtStatus.OBJECT_PATH_NOT_FOUND); // the share itself went away
        }
        for (int i = 0; i < components.size(); i++) {
            boolean last = i == components.size() - 1;
            Path next = cur.resolve(components.get(i));
            BasicFileAttributes a = stat(next);
            if (a == null) {
                Path alt = caseInsensitiveMatch(cur, components.get(i));
                if (alt != null) {
                    next = alt;
                    a = stat(next);
                }
            }
            if (a == null) {
                if (!last) {
                    throw new DriveException(NtStatus.OBJECT_PATH_NOT_FOUND);
                }
                return new Resolved(next, null, false);
            }
            if (a.isSymbolicLink() || a.isOther()) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            if (a.isDirectory() && isProtected(next)) {
                throw new DriveException(NtStatus.ACCESS_DENIED);
            }
            if (!last && !a.isDirectory()) {
                throw new DriveException(NtStatus.OBJECT_PATH_NOT_FOUND);
            }
            cur = next;
            attrs = a;
        }
        return new Resolved(cur, attrs, components.isEmpty());
    }

    /** Whether {@code dir} is one of the protected folders, by identity. */
    boolean isProtected(Path dir) {
        for (Path p : protectedFolders) {
            try {
                if (Files.isSameFile(dir, p)) {
                    return true;
                }
            } catch (IOException e) {
                // unreadable: not the same file as far as we can tell
            }
        }
        return false;
    }

    /**
     * Whether a listing may show {@code entry} with attributes {@code a}: never a link, a reparse
     * point or a protected folder, and never a name the server could not address.
     */
    boolean listable(Path entry, BasicFileAttributes a) {
        if (a.isSymbolicLink() || a.isOther()) {
            return false;
        }
        Path fileName = entry.getFileName();
        if (fileName == null || !DrivePath.validName(fileName.toString())) {
            return false;
        }
        return !(a.isDirectory() && isProtected(entry));
    }

    /** Attributes without following links, or null when the path does not exist. */
    static BasicFileAttributes stat(Path p) throws DriveException {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (java.nio.file.AccessDeniedException e) {
            throw new DriveException(NtStatus.ACCESS_DENIED);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * On a case-sensitive file system, the one entry of {@code dir} whose name equals {@code name}
     * ignoring case, so a Windows program that changes the case of a name still finds the file.
     * Null when there is none, or more than one.
     */
    private static Path caseInsensitiveMatch(Path dir, String name) {
        Path found = null;
        int seen = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (++seen > CASE_SCAN_LIMIT) {
                    return null;
                }
                Path fn = p.getFileName();
                if (fn != null && fn.toString().equalsIgnoreCase(name)) {
                    if (found != null) {
                        return null;
                    }
                    found = p;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return found;
    }
}
