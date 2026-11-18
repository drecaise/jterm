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
package com.katmoda.jterm.terminal.rdp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The jterm side of the {@code jterm-rdp-helper} wire protocol — the single source of truth is
 * {@code native/rdp-helper/src/protocol.h}; keep the two in step.
 *
 * <p>Every message is {@code u32 length (type + payload), u8 type, payload}, little-endian;
 * strings are {@code u16 length + UTF-8}. {@link #encode} builds outbound messages; {@link #decode}
 * turns an inbound payload into an {@link Inbound} record. Swing-free and I/O-free so it is
 * unit-tested directly.</p>
 */
public final class RdpProtocol {

    public static final int VERSION = 13;

    // jterm -> helper
    static final int HELLO = 0x01;
    static final int CONNECT = 0x02;
    static final int KEY = 0x03;
    static final int UNICODE = 0x04;
    static final int MOUSE = 0x05;
    static final int CERT_REPLY = 0x06;
    static final int RESIZE = 0x07;
    static final int CLIP_OFFER = 0x08;
    static final int CLIP_DATA = 0x09;
    static final int CLIP_FETCH = 0x0A;
    static final int DRIVE_IRP_DONE = 0x0B;
    static final int FILE_DATA = 0x0C;
    static final int FILE_FETCH = 0x0D;
    static final int CLIP_LOCK_REMOTE = 0x0E;
    static final int BYE = 0x0F;
    static final int MIC_DATA = 0x10;
    static final int AUDIO_DELAY = 0x11;
    static final int RESOLVE_REPLY = 0x12;

    /** CONNECT clipboardFlags: which directions of clipboard sharing the session allows. */
    public static final int CLIP_REMOTE_TO_LOCAL = 0x01;
    public static final int CLIP_LOCAL_TO_REMOTE = 0x02;
    /** ...and files too (needs {@link #CLIP_LOCAL_TO_REMOTE}; protocol v8). */
    public static final int CLIP_FILES_TO_REMOTE = 0x04;
    /** The server's files may be read (needs {@link #CLIP_REMOTE_TO_LOCAL}; protocol v9). */
    public static final int CLIP_FILES_TO_LOCAL = 0x08;

    /** MS-RDPECLIP general capability flag: the clipboard can be locked while files are read. */
    public static final int CB_CAN_LOCK_CLIPDATA = 0x10;

    /**
     * CONNECT securityFlags. 0 requires NLA; this also accepts TLS without NLA. The legacy RDP
     * security layer is never offered.
     */
    public static final int SEC_ALLOW_TLS = 0x01;

    /** CONNECT gatewayFlags. */
    public static final int GW_ENABLED = 0x01;
    public static final int GW_SAME_CREDENTIALS = 0x02;

    /**
     * The RD Gateway part of CONNECT. {@link #NONE} for a session without one; with
     * {@code sameCredentials} the user, domain and password are left empty and the helper uses the
     * desktop's.
     */
    public record Gateway(String host, int port, boolean sameCredentials, String user, String domain,
                          char[] password) {
        public static final Gateway NONE = new Gateway("", 0, false, "", "", null);

        public boolean enabled() {
            return host != null && !host.isBlank();
        }
    }

    /** CONNECT audioFlags (protocol v10). 0 opens no audio channel at all. */
    public static final int AUDIO_PLAYBACK = 0x01;
    public static final int AUDIO_MIC = 0x02;
    /** The helper's sound device may claim AAC when it finds a decoder (needs {@link #AUDIO_PLAYBACK}). */
    public static final int AUDIO_AAC = 0x04;

    /** CONNECT printerFlags (protocol v13). 0 announces no printer. */
    public static final int PRINTER_ENABLED = 0x01;
    /** ...as the session's default printer. */
    public static final int PRINTER_DEFAULT = 0x02;
    /** The printer's id in {@link DriveIrp#driveId()}: outside the ids shared folders get. */
    public static final int PRINTER_DEVICE_ID = 0xFFFF0001;
    /** Longest printer or driver name the helper accepts, in characters. */
    public static final int PRINTER_NAME_MAX = 64;

    /** CONNECT smartcardFlags (protocol v13). 0 announces no smart-card device. */
    public static final int SMARTCARD_ENABLED = 0x01;
    /** The smart-card device's id in {@link DriveIrp#driveId()}. */
    public static final int SMARTCARD_DEVICE_ID = 0xFFFF0002;

    /**
     * The session's printer as CONNECT announces it (protocol v13): {@code flags} a mask of
     * {@link #PRINTER_ENABLED} / {@link #PRINTER_DEFAULT}, the name the remote desktop shows and the
     * driver the server renders with. {@link #NONE} announces nothing.
     */
    public record Printer(int flags, String name, String driver) {
        public static final Printer NONE = new Printer(0, "", "");

        public Printer {
            if ((flags & PRINTER_ENABLED) != 0 && (name.isEmpty() || driver.isEmpty()
                    || name.codePointCount(0, name.length()) > PRINTER_NAME_MAX
                    || driver.length() > PRINTER_NAME_MAX || !driver.chars().allMatch(c -> c >= 0x20 && c < 0x7F))) {
                throw new IllegalArgumentException("invalid printer announcement");
            }
        }
    }

    /** {@link AudioOpen#codec()}: where the PCM came from (display only). */
    public static final int AUDIO_CODEC_PCM = 0;
    public static final int AUDIO_CODEC_AAC = 1;

    /** Largest sample payload of one AUDIO_DATA or MIC_DATA message. */
    public static final int AUDIO_MAX_CHUNK = 65536;

    /** Clipboard kinds (a mask in offers, one value elsewhere). */
    public static final int CLIPKIND_TEXT = 0x01;
    public static final int CLIPKIND_IMAGE = 0x02;
    /** CF_HTML, the registered "HTML Format" (protocol v6). */
    public static final int CLIPKIND_HTML = 0x04;
    /** A file list, "FileGroupDescriptorW" (local to remote protocol v8, remote to local v9). */
    public static final int CLIPKIND_FILES = 0x08;

    /** {@link FileRequest} flags (MS-RDPECLIP {@code FILECONTENTS_*}). */
    public static final int FILECONTENTS_SIZE = 0x01;
    public static final int FILECONTENTS_RANGE = 0x02;

    /** Largest clipboard payload either way; both sides refuse rather than truncate. */
    public static final int CLIP_MAX_BYTES = 32 * 1024 * 1024;

    public static final int KEY_FORCE_EXTENDED = 0x01;
    public static final int KEY_FORCE_NOT_EXTENDED = 0x02;
    public static final int KEY_REPEAT = 0x04;

    // helper -> jterm
    static final int HELLO_REPLY = 0x81;
    static final int CONNECTED = 0x82;
    static final int FRAME = 0x83;
    static final int DESKTOP_RESIZED = 0x84;
    static final int POINTER_NEW = 0x85;
    static final int POINTER_FREE = 0x86;
    static final int POINTER_SET = 0x87;
    static final int POINTER_HIDE = 0x88;
    static final int POINTER_DEFAULT = 0x89;
    static final int CERT_REQUEST = 0x8A;
    static final int CLIP_REMOTE_OFFER = 0x8B;
    static final int CLIP_REMOTE_DATA = 0x8C;
    static final int CLIP_REQUEST = 0x8D;
    static final int DRIVE_IRP = 0x8E;
    static final int FILE_REQUEST = 0x90;
    static final int CLIP_LOCK = 0x91;
    static final int CLIP_UNLOCK = 0x92;
    static final int FILE_REMOTE_DATA = 0x93;
    static final int CLIP_CAPS = 0x94;
    static final int AUDIO_OPEN = 0x95;
    static final int AUDIO_DATA = 0x96;
    static final int AUDIO_CLOSE = 0x97;
    static final int AUDIO_VOLUME = 0x98;
    static final int MIC_OPEN = 0x99;
    static final int MIC_CLOSE = 0x9A;
    static final int RESOLVE = 0x9B;
    static final int DISCONNECTED = 0x8F;

    /** Certificate flags (FreeRDP {@code VERIFY_CERT_FLAG_*}). */
    public static final int CERT_FLAG_GATEWAY = 0x20;
    public static final int CERT_FLAG_CHANGED = 0x40;
    public static final int CERT_FLAG_MISMATCH = 0x80;

    /** Mouse flags (FreeRDP {@code PTR_FLAGS_*}). */
    public static final int PTR_MOVE = 0x0800;
    public static final int PTR_DOWN = 0x8000;
    public static final int PTR_BUTTON1 = 0x1000; // left
    public static final int PTR_BUTTON2 = 0x2000; // right
    public static final int PTR_BUTTON3 = 0x4000; // middle
    public static final int PTR_WHEEL = 0x0200;
    public static final int PTR_HWHEEL = 0x0400;
    /** One wheel notch away from / towards the user, as the 9-bit rotation field. */
    public static final int WHEEL_UP = 0x078;
    public static final int WHEEL_DOWN = 0x188;

    /** Largest inbound message accepted: a full 8192x8192 BGRX frame plus header. */
    static final int MAX_INBOUND = 1 + 8 + 8192 * 8192 * 4;

    private RdpProtocol() {
    }

    // ------------------------------------------------------------------------------- outbound

    /** Builder for one outbound message. */
    static final class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        private final int type;

        Writer(int type) {
            this.type = type;
        }

        Writer u8(int v) {
            buf.write(v & 0xFF);
            return this;
        }

        Writer u16(int v) {
            buf.write(v & 0xFF);
            buf.write((v >>> 8) & 0xFF);
            return this;
        }

        Writer u32(int v) {
            u16(v & 0xFFFF);
            return u16(v >>> 16);
        }

        Writer bytes(byte[] b) {
            buf.write(b, 0, b.length);
            return this;
        }

        Writer str(String s) {
            byte[] b = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
            int n = Math.min(b.length, 0xFFFF);
            u16(n);
            buf.write(b, 0, n);
            return this;
        }

        /** The framed message. The payload copy is scrubbed, since CONNECT carries a password. */
        byte[] build() {
            byte[] payload = buf.toByteArray();
            ByteBuffer out = ByteBuffer.allocate(5 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(1 + payload.length).put((byte) type).put(payload);
            Arrays.fill(payload, (byte) 0);
            return out.array();
        }
    }

    static byte[] hello() {
        return new Writer(HELLO).u16(VERSION).build();
    }

    /**
     * CONNECT. {@code password} is copied into the message and the caller should zero its own array.
     * {@code pins} is a comma-separated {@code sha256:aa:bb:...} list (empty for none).
     * {@code clipboardFlags} is a mask of {@link #CLIP_REMOTE_TO_LOCAL} / {@link #CLIP_LOCAL_TO_REMOTE};
     * 0 keeps the clipboard channel closed. {@code securityFlags} is 0 (NLA required) or
     * {@link #SEC_ALLOW_TLS}. {@code serverName} is the name the certificate is checked against when
     * {@code host} is a local SSH forward; empty or {@code null} means {@code host} itself.
     */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent, int clipboardFlags,
                          int securityFlags, String serverName, Gateway gateway) {
        return connect(host, port, user, domain, password, pins, width, height, scalePercent, clipboardFlags,
                securityFlags, serverName, gateway, List.of());
    }

    /**
     * A shared folder as the helper announces it (protocol v7): an id that comes back in every
     * {@link DriveIrp}, and the name the remote desktop shows. No path: the helper never learns one.
     */
    public record Drive(int id, String name) {
    }

    /** Most drives a CONNECT can carry. */
    public static final int MAX_DRIVES = 24;

    /** CONNECT with the session's shared folders appended (see {@link Drive}) and no sound. */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent, int clipboardFlags,
                          int securityFlags, String serverName, Gateway gateway, List<Drive> drives) {
        return connect(host, port, user, domain, password, pins, width, height, scalePercent, clipboardFlags,
                securityFlags, serverName, gateway, drives, 0, 0, Printer.NONE);
    }

    /** CONNECT with shared folders, sound and a printer but no smart card. */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent, int clipboardFlags,
                          int securityFlags, String serverName, Gateway gateway, List<Drive> drives,
                          int audioFlags, int audioLatencyMs, Printer printer) {
        return connect(host, port, user, domain, password, pins, width, height, scalePercent, clipboardFlags,
                securityFlags, serverName, gateway, drives, audioFlags, audioLatencyMs, printer, 0);
    }

    /** CONNECT with shared folders and sound but no printer. */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent, int clipboardFlags,
                          int securityFlags, String serverName, Gateway gateway, List<Drive> drives,
                          int audioFlags, int audioLatencyMs) {
        return connect(host, port, user, domain, password, pins, width, height, scalePercent, clipboardFlags,
                securityFlags, serverName, gateway, drives, audioFlags, audioLatencyMs, Printer.NONE);
    }

    /**
     * The full CONNECT (protocol v10): {@code audioFlags} is a mask of {@link #AUDIO_PLAYBACK},
     * {@link #AUDIO_MIC} and {@link #AUDIO_AAC} (0: no audio channel exists), {@code audioLatencyMs}
     * the render delay the helper reports to the server (jterm's playback buffer). {@code printer}
     * (protocol v13) is the session's printer, or {@link Printer#NONE}; {@code smartcardFlags}
     * {@link #SMARTCARD_ENABLED} or 0.
     */
    static byte[] connect(String host, int port, String user, String domain, char[] password,
                          String pins, int width, int height, int scalePercent, int clipboardFlags,
                          int securityFlags, String serverName, Gateway gateway, List<Drive> drives,
                          int audioFlags, int audioLatencyMs, Printer printer, int smartcardFlags) {
        if ((smartcardFlags & ~SMARTCARD_ENABLED) != 0) {
            throw new IllegalArgumentException("unknown smart-card flags");
        }
        if (drives.size() > MAX_DRIVES) {
            throw new IllegalArgumentException("too many shared folders");
        }
        String pw = password != null ? new String(password) : "";
        Writer w = new Writer(CONNECT).str(host).u16(port).str(user).str(domain).str(pw).str(pins)
                .u16(width).u16(height).u16(scalePercent).u8(clipboardFlags).u8(securityFlags)
                .str(serverName)
                .u8(gateway.enabled() ? GW_ENABLED | (gateway.sameCredentials() ? GW_SAME_CREDENTIALS : 0) : 0)
                .str(gateway.enabled() ? gateway.host() : "")
                .u16(gateway.enabled() ? gateway.port() : 0)
                .str(gateway.enabled() && !gateway.sameCredentials() ? gateway.user() : "")
                .str(gateway.enabled() && !gateway.sameCredentials() ? gateway.domain() : "")
                .str(gateway.enabled() && !gateway.sameCredentials() && gateway.password() != null
                        ? new String(gateway.password()) : "")
                .u8(drives.size());
        for (Drive d : drives) {
            w.u32(d.id()).str(d.name());
        }
        w.u8(audioFlags).u16(Math.max(0, Math.min(0xFFFF, audioLatencyMs)));
        w.u8(printer.flags()).str(printer.name()).str(printer.driver());
        w.u8(smartcardFlags);
        return w.build();
    }

    /**
     * Microphone samples for the stream {@code streamId} announced by {@link MicOpen}: whole frames
     * in its format, at most {@link #AUDIO_MAX_CHUNK} bytes.
     */
    static byte[] micData(int streamId, byte[] data, int off, int len) {
        if (len < 0 || len > AUDIO_MAX_CHUNK || off < 0 || off + len > data.length) {
            throw new IllegalArgumentException("microphone chunk out of range");
        }
        Writer w = new Writer(MIC_DATA).u32(streamId);
        w.buf.write(data, off, len);
        return w.build();
    }

    /** Largest delay {@link #audioDelay} carries (protocol.h {@code JRDP_AUDIO_DELAY_MAX_MS}). */
    public static final int AUDIO_DELAY_MAX_MS = 1000;

    /**
     * How long sound now waits in jterm before it is heard, which the helper reports to the server
     * as the render delay in place of CONNECT's {@code audioLatencyMs} (protocol v11).
     */
    static byte[] audioDelay(int millis) {
        return new Writer(AUDIO_DELAY).u16(Math.max(0, Math.min(AUDIO_DELAY_MAX_MS, millis))).build();
    }

    /** The CONNECT audio mask for a session's settings; AAC only counts with playback. */
    public static int audioFlags(boolean playback, boolean microphone, boolean aac) {
        return (playback ? AUDIO_PLAYBACK : 0) | (microphone ? AUDIO_MIC : 0) | (playback && aac ? AUDIO_AAC : 0);
    }

    /** The answer to a {@link DriveIrp}: its NTSTATUS and the body after the completion header. */
    static byte[] driveIrpDone(int completionId, int ioStatus, byte[] output) {
        return new Writer(DRIVE_IRP_DONE).u32(completionId).u32(ioStatus).bytes(output).build();
    }

    /** The CONNECT clipboard mask for a session's two settings. */
    public static int clipboardFlags(boolean remoteToLocal, boolean localToRemote) {
        return clipboardFlags(remoteToLocal, localToRemote, false);
    }

    /** As above, plus files to the remote, which only counts together with {@code localToRemote}. */
    public static int clipboardFlags(boolean remoteToLocal, boolean localToRemote, boolean filesToRemote) {
        return clipboardFlags(remoteToLocal, localToRemote, filesToRemote, false);
    }

    /** All four: each files direction only counts together with its own clipboard direction. */
    public static int clipboardFlags(boolean remoteToLocal, boolean localToRemote, boolean filesToRemote,
                                     boolean filesToLocal) {
        return (remoteToLocal ? CLIP_REMOTE_TO_LOCAL : 0) | (localToRemote ? CLIP_LOCAL_TO_REMOTE : 0)
                | (localToRemote && filesToRemote ? CLIP_FILES_TO_REMOTE : 0)
                | (remoteToLocal && filesToLocal ? CLIP_FILES_TO_LOCAL : 0);
    }

    /** Reads a file of the server's list; answered by {@link FileRemoteData}. */
    static byte[] fileFetch(int streamId, int listIndex, int flags, long position, int cbRequested,
                            boolean haveClipDataId, int clipDataId) {
        return new Writer(FILE_FETCH).u32(streamId).u32(listIndex).u32(flags)
                .u32((int) position).u32((int) (position >>> 32)).u32(cbRequested)
                .u8(haveClipDataId ? 1 : 0).u32(clipDataId).build();
    }

    /** Asks the server to keep ({@code lock}) or release its clipboard's files under {@code clipDataId}. */
    static byte[] clipLockRemote(boolean lock, int clipDataId) {
        return new Writer(CLIP_LOCK_REMOTE).u8(lock ? 1 : 0).u32(clipDataId).build();
    }

    /** The answer to a {@link FileRequest}: the bytes read, or {@code ok == false}. */
    static byte[] fileData(int streamId, boolean ok, byte[] data) {
        Writer w = new Writer(FILE_DATA).u32(streamId).u8(ok ? 1 : 0);
        if (ok && data != null) {
            w.bytes(data);
        }
        return w.build();
    }

    /** What the local clipboard can supply now ({@code kinds} is a mask; 0 means nothing). */
    static byte[] clipOffer(int kinds) {
        return new Writer(CLIP_OFFER).u8(kinds).build();
    }

    /** The answer to {@link ClipRequest}: the data, or {@code ok == false} when it can't be supplied. */
    static byte[] clipData(int kind, boolean ok, byte[] data) {
        Writer w = new Writer(CLIP_DATA).u8(kind).u8(ok ? 1 : 0);
        if (ok && data != null) {
            w.bytes(data);
        }
        return w.build();
    }

    /** Asks for one kind of the server's clipboard; answered by {@link ClipRemoteData}. */
    static byte[] clipFetch(int kind) {
        return new Writer(CLIP_FETCH).u8(kind).build();
    }

    static byte[] key(boolean down, int windowsVk, int flags) {
        return new Writer(KEY).u8(down ? 1 : 0).u16(windowsVk).u8(flags).build();
    }

    static byte[] unicode(boolean down, char utf16Unit) {
        return new Writer(UNICODE).u8(down ? 1 : 0).u16(utf16Unit).build();
    }

    static byte[] mouse(int flags, int x, int y) {
        return new Writer(MOUSE).u16(flags).u16(clampU16(x)).u16(clampU16(y)).build();
    }

    static byte[] resize(int width, int height, int scalePercent) {
        return new Writer(RESIZE).u16(width).u16(height).u16(scalePercent).build();
    }

    static byte[] certReply(boolean accept) {
        return new Writer(CERT_REPLY).u8(accept ? 1 : 0).build();
    }

    static byte[] bye() {
        return new Writer(BYE).build();
    }

    /** Most addresses one {@link #resolveReply} carries (protocol.h {@code NETROUTE_MAX_ADDRS}). */
    public static final int RESOLVE_MAX_ADDRESSES = 16;

    /**
     * The answer to a {@link ResolveRequest} (protocol v12): numeric addresses, at most
     * {@link #RESOLVE_MAX_ADDRESSES}; an empty list means "not resolved".
     */
    static byte[] resolveReply(List<String> addresses) {
        int n = Math.min(addresses.size(), RESOLVE_MAX_ADDRESSES);
        Writer w = new Writer(RESOLVE_REPLY).u8(n);
        for (int i = 0; i < n; i++) {
            w.str(addresses.get(i));
        }
        return w.build();
    }

    private static int clampU16(int v) {
        return Math.max(0, Math.min(0xFFFF, v));
    }

    // -------------------------------------------------------------------------------- inbound

    /** A decoded helper message. */
    public sealed interface Inbound {
    }

    public record HelloReply(int version, String freerdpVersion) implements Inbound {
    }

    public record Connected(int width, int height, boolean unicodeInput) implements Inbound {
    }

    /**
     * A dirty rectangle. {@code pixels} is a little-endian view over BGRX pixel data (read as ints:
     * {@code 0x00RRGGBB}), row-major, {@code width} ints per row. It aliases the reader's buffer and
     * is only valid during the callback.
     */
    public record Frame(int x, int y, int width, int height, ByteBuffer pixels) implements Inbound {
    }

    public record DesktopResized(int width, int height) implements Inbound {
    }

    /** A cursor shape: {@code argb} is {@code width*height} ints, {@code 0xAARRGGBB}. */
    public record PointerNew(int id, int width, int height, int hotX, int hotY, int[] argb)
            implements Inbound {
    }

    public record PointerFree(int id) implements Inbound {
    }

    public record PointerSet(int id) implements Inbound {
    }

    public record PointerHide() implements Inbound {
    }

    public record PointerDefault() implements Inbound {
    }

    /** The server's certificate failed CA/hostname validation and isn't pinned: ask the user. */
    public record CertRequest(String host, int port, String commonName, String subject,
                              String issuer, String sha256Fingerprint, int flags) implements Inbound {

        public boolean nameMismatch() {
            return (flags & CERT_FLAG_MISMATCH) != 0;
        }

        /** Whether this is the RD Gateway's certificate rather than the desktop's. */
        public boolean gateway() {
            return (flags & CERT_FLAG_GATEWAY) != 0;
        }
    }

    /** The last message: FreeRDP's error code (0 for a clean disconnect) and message. */
    public record Disconnected(long code, String message) implements Inbound {

        /**
         * Whether the server rejected the credentials (FreeRDP connect-class codes
         * AUTHENTICATION_FAILED, LOGON_FAILURE, WRONG_PASSWORD, NO_OR_MISSING_CREDENTIALS) — the case
         * where re-prompting for the password is the right response.
         */
        public boolean credentialsRejected() {
            return code == 0x00020009L || code == 0x00020014L || code == 0x00020015L
                    || code == 0x0002001BL;
        }

        /**
         * Whether security negotiation failed (SECURITY_NEGO_CONNECT_FAILED): the server offered
         * nothing the session accepts. That is a server without NLA when the session requires it,
         * or one offering only the legacy RDP security layer, which is always refused.
         */
        public boolean securityNegotiationFailed() {
            return code == 0x0002000CL;
        }
    }

    /** What the server's clipboard now holds, as a {@code CLIPKIND_*} mask (0 = emptied). */
    public record ClipRemoteOffer(int kinds) implements Inbound {
    }

    /**
     * The server's clipboard content for a fetch. Text is CF_UNICODETEXT as sent (UTF-16LE, usually
     * NUL-terminated); an image is a CF_DIB, or a CF_DIBV5 from a server without CF_DIB, without a
     * file header; HTML is CF_HTML as sent. {@code data} is a copy.
     */
    public record ClipRemoteData(int kind, boolean ok, byte[] data) implements Inbound {
    }

    /** The server pastes: answer with {@link RdpConnection#sendClipboardData}, always. */
    public record ClipRequest(int kind) implements Inbound {
    }

    /**
     * An I/O request the server sent to a shared folder (MS-RDPEFS), forwarded as received: the
     * drive's id from CONNECT, the request header fields, and the body after the 20-byte header.
     * Answer with {@link RdpConnection#completeDriveIrp}, always.
     */
    public record DriveIrp(int driveId, int fileId, int completionId, int major, int minor, byte[] input)
            implements Inbound {
    }

    /**
     * The server reads a file described in a {@link #CLIPKIND_FILES} answer: its size
     * ({@link #FILECONTENTS_SIZE}) or {@code cbRequested} bytes at {@code position}
     * ({@link #FILECONTENTS_RANGE}). {@code clipDataId} is meaningful only with {@code haveClipDataId}.
     * Answer with {@link RdpConnection#sendFileData}, always.
     */
    public record FileRequest(int streamId, int listIndex, int flags, long position, int cbRequested,
                              boolean haveClipDataId, int clipDataId) implements Inbound {
    }

    /** The server pins the clipboard's current files under {@code clipDataId}. */
    public record ClipLock(int clipDataId) implements Inbound {
    }

    /** The server releases what it pinned under {@code clipDataId}. */
    public record ClipUnlock(int clipDataId) implements Inbound {
    }

    /** The server's answer to a {@link #fileFetch}: its bytes as sent, or {@code ok == false}. */
    public record FileRemoteData(int streamId, boolean ok, byte[] data) implements Inbound {
    }

    /** The clipboard capability flags both sides agreed on (MS-RDPECLIP general flags). */
    public record ClipCaps(int generalFlags) implements Inbound {

        public boolean canLock() {
            return (generalFlags & CB_CAN_LOCK_CLIPDATA) != 0;
        }
    }

    /**
     * The format of the sound that follows: signed 16-bit little-endian, interleaved. The values
     * are the helper's and still to be validated against the allowed list.
     */
    public record AudioOpen(int sampleRate, int channels, int codec) implements Inbound {
    }

    /**
     * Sound samples in the last {@link AudioOpen}'s format. {@code samples} aliases the reader's
     * buffer and is only valid during the callback: copy it.
     */
    public record AudioData(ByteBuffer samples) implements Inbound {
    }

    /** The sound stream ended. */
    public record AudioClose() implements Inbound {
    }

    /** The server's volume per channel, 0..0xFFFF (full). */
    public record AudioVolume(int left, int right) implements Inbound {
    }

    /** The server opened a microphone stream (values still to be validated). */
    public record MicOpen(int streamId, int sampleRate, int channels, int framesPerPacket) implements Inbound {
    }

    /** The server closed the microphone stream {@code streamId}. */
    public record MicClose(int streamId) implements Inbound {
    }

    /**
     * The sandboxed helper can't resolve names, and FreeRDP wants one it didn't resolve before its
     * lockdown, typically the host a server redirected to (protocol v12). The name comes from the
     * server: answer with {@link RdpConnection}'s resolver, always.
     */
    public record ResolveRequest(String name) implements Inbound {
    }

    public record Unknown(int type, int length) implements Inbound {
    }

    /**
     * Decodes one message body. {@code body} is little-endian and positioned at the first payload
     * byte (after the type).
     *
     * @throws java.nio.BufferUnderflowException on a truncated message
     */
    static Inbound decode(int type, ByteBuffer body) {
        body.order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case HELLO_REPLY -> new HelloReply(u16(body), str(body));
            case CONNECTED -> new Connected(u16(body), u16(body), body.get() != 0);
            case FRAME -> {
                int x = u16(body), y = u16(body), w = u16(body), h = u16(body);
                int bytes = w * h * 4;
                if (body.remaining() < bytes) {
                    throw new java.nio.BufferUnderflowException();
                }
                ByteBuffer pixels = body.slice(body.position(), bytes).order(ByteOrder.LITTLE_ENDIAN);
                yield new Frame(x, y, w, h, pixels);
            }
            case DESKTOP_RESIZED -> new DesktopResized(u16(body), u16(body));
            case POINTER_NEW -> {
                int id = body.getInt();
                int w = u16(body), h = u16(body), hx = u16(body), hy = u16(body);
                int[] argb = new int[w * h];
                body.asIntBuffer().get(argb);
                yield new PointerNew(id, w, h, hx, hy, argb);
            }
            case POINTER_FREE -> new PointerFree(body.getInt());
            case POINTER_SET -> new PointerSet(body.getInt());
            case POINTER_HIDE -> new PointerHide();
            case POINTER_DEFAULT -> new PointerDefault();
            case CERT_REQUEST -> new CertRequest(str(body), u16(body), str(body), str(body),
                    str(body), str(body), body.getInt());
            case CLIP_REMOTE_OFFER -> new ClipRemoteOffer(body.get() & 0xFF);
            case CLIP_REMOTE_DATA -> {
                int kind = body.get() & 0xFF;
                boolean ok = body.get() != 0;
                byte[] data = new byte[ok ? body.remaining() : 0];
                body.get(data);
                yield new ClipRemoteData(kind, ok, data);
            }
            case CLIP_REQUEST -> new ClipRequest(body.get() & 0xFF);
            case DRIVE_IRP -> {
                int drive = body.getInt(), file = body.getInt(), completion = body.getInt();
                int major = body.getInt(), minor = body.getInt();
                byte[] input = new byte[body.remaining()];
                body.get(input);
                yield new DriveIrp(drive, file, completion, major, minor, input);
            }
            case FILE_REQUEST -> {
                int stream = body.getInt(), index = body.getInt(), flags = body.getInt();
                long low = Integer.toUnsignedLong(body.getInt());
                long high = Integer.toUnsignedLong(body.getInt());
                int cb = body.getInt();
                boolean haveId = body.get() != 0;
                int id = body.getInt();
                yield new FileRequest(stream, index, flags, (high << 32) | low, cb, haveId, id);
            }
            case CLIP_LOCK -> new ClipLock(body.getInt());
            case FILE_REMOTE_DATA -> {
                int stream = body.getInt();
                boolean ok = body.get() != 0;
                byte[] data = new byte[ok ? body.remaining() : 0];
                body.get(data);
                yield new FileRemoteData(stream, ok, data);
            }
            case CLIP_CAPS -> new ClipCaps(body.getInt());
            case CLIP_UNLOCK -> new ClipUnlock(body.getInt());
            case AUDIO_OPEN -> new AudioOpen(body.getInt(), body.get() & 0xFF, body.get() & 0xFF);
            // Not size-checked here: the player drops and counts a bad chunk, where a throw would
            // end the whole connection.
            case AUDIO_DATA -> new AudioData(body.slice().order(ByteOrder.LITTLE_ENDIAN));
            case AUDIO_CLOSE -> new AudioClose();
            case AUDIO_VOLUME -> new AudioVolume(u16(body), u16(body));
            case MIC_OPEN -> new MicOpen(body.getInt(), body.getInt(), body.get() & 0xFF, body.getInt());
            case MIC_CLOSE -> new MicClose(body.getInt());
            case RESOLVE -> new ResolveRequest(str(body));
            case DISCONNECTED -> new Disconnected(Integer.toUnsignedLong(body.getInt()), str(body));
            default -> new Unknown(type, body.remaining());
        };
    }

    private static int u16(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }

    private static String str(ByteBuffer b) {
        int n = u16(b);
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }
}
