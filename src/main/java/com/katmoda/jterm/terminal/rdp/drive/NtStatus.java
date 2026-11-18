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

/** The NTSTATUS values a redirected drive answers with (MS-ERREF; values as in WinPR's nt.h). */
public final class NtStatus {

    public static final int SUCCESS = 0x00000000;
    public static final int NO_MORE_FILES = 0x80000006;
    public static final int UNSUCCESSFUL = 0xC0000001;
    public static final int INVALID_HANDLE = 0xC0000008;
    public static final int INVALID_PARAMETER = 0xC000000D;
    public static final int NO_SUCH_FILE = 0xC000000F;
    public static final int ACCESS_DENIED = 0xC0000022;
    public static final int OBJECT_NAME_INVALID = 0xC0000033;
    public static final int OBJECT_NAME_NOT_FOUND = 0xC0000034;
    public static final int OBJECT_NAME_COLLISION = 0xC0000035;
    public static final int OBJECT_PATH_NOT_FOUND = 0xC000003A;
    public static final int DISK_FULL = 0xC000007F;
    public static final int INSUFFICIENT_RESOURCES = 0xC000009A;
    public static final int MEDIA_WRITE_PROTECTED = 0xC00000A2;
    public static final int FILE_IS_A_DIRECTORY = 0xC00000BA;
    public static final int NOT_SUPPORTED = 0xC00000BB;
    public static final int DIRECTORY_NOT_EMPTY = 0xC0000101;
    public static final int NOT_A_DIRECTORY = 0xC0000103;
    public static final int CANNOT_DELETE = 0xC0000121;

    private NtStatus() {
    }
}
