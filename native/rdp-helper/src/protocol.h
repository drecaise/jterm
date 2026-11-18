/*
 * jterm <-> jterm-rdp-helper wire protocol.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Every message: u32 length (of type + payload), u8 type, payload. All integers little-endian.
 * str = u16 byte length + UTF-8 bytes (no terminator).
 *
 * jterm -> helper
 *   HELLO        u16 version                                     (first message)
 *   CONNECT      str host, u16 port, str user, str domain, str password,
 *                str pinnedFingerprints ("sha256:aa:bb:..,..." or empty),
 *                u16 width, u16 height (device pixels), u16 scalePercent (100 = none),
 *                u8 clipboardFlags (JRDP_CLIP_*; 0 = the clipboard channel is not opened at all),
 *                u8 securityFlags (JRDP_SEC_*; 0 = NLA required),
 *                str serverName (empty = host; else the name the certificate is checked against and
 *                sent as TLS SNI, when host is a local SSH forward such as 127.0.0.1),
 *                u8 gatewayFlags (JRDP_GW_*; 0 = no RD Gateway), str gatewayHost, u16 gatewayPort,
 *                str gatewayUser, str gatewayDomain, str gatewayPassword (the last three empty with
 *                JRDP_GW_SAME_CREDENTIALS, which reuses the desktop's),
 *                u8 driveCount (<= JRDP_MAX_DRIVES; 0 = no device redirection at all), then per shared
 *                folder: u32 id, str name (what the remote desktop shows). Never a local path: jterm
 *                keeps those, and answers every request itself (protocol v7).
 *                u8 audioFlags (JRDP_AUDIO_*; 0 = no audio channel at all), u16 audioLatencyMs (what
 *                the sound device reports to the server as its render delay: jterm's playback buffer)
 *                (protocol v10)
 *                u8 printerFlags (JRDP_PRINTER_*; 0 = no printer), str printerName, str printerDriver
 *                (the driver the server renders with; jterm's choice, never the user's), u8
 *                smartcardFlags (JRDP_SMARTCARD_*; 0 = no smart-card device) (protocol v13). The
 *                printer's requests arrive as DRIVE_IRP with driveId JRDP_PRINTER_DEVICE_ID, the
 *                smart card's (IRP_MJ_DEVICE_CONTROL only) with JRDP_SMARTCARD_DEVICE_ID.
 *   KEY          u8 down, u16 windowsVirtualKey, u8 flags (JRDP_KEY_*)
 *   UNICODE      u8 down, u16 utf16CodeUnit
 *   MOUSE        u16 ptrFlags (PTR_FLAGS_*), u16 x, u16 y
 *   CERT_REPLY   u8 accept (answers CERT_REQUEST; accept means "this session only")
 *   RESIZE       u16 width, u16 height (device pixels), u16 scalePercent — sent to the server over
 *                the Display Control channel once it is ready (servers without it ignore this)
 *   CLIP_OFFER   u8 kinds (JRDP_CLIPKIND_*; 0 = nothing) — what the local clipboard can supply now.
 *                Announced to the server only; the bytes leave jterm when the server pastes. Ignored
 *                unless JRDP_CLIP_LOCAL_TO_REMOTE was set in CONNECT; FILES also needs
 *                JRDP_CLIP_FILES_TO_REMOTE.
 *   CLIP_DATA    u8 kind, u8 ok, bytes — answers CLIP_REQUEST (ok=0: could not supply it). The
 *                bytes are in the server's format: CF_UNICODETEXT, CF_DIB or CF_HTML, or for FILES
 *                a packed file list (u32 count + 592-byte FILEDESCRIPTORW entries, MS-RDPECLIP
 *                2.2.5.2.3), built by jterm; the helper does not parse it.
 *   FILE_DATA    u32 streamId, u8 ok, bytes — answers FILE_REQUEST: for FILECONTENTS_SIZE the
 *                8-byte size, for FILECONTENTS_RANGE at most cbRequested bytes (protocol v8).
 *   CLIP_FETCH   u8 kind — fetch that kind of the server's clipboard (answered by CLIP_REMOTE_DATA).
 *                Ignored unless JRDP_CLIP_REMOTE_TO_LOCAL was set in CONNECT; FILES also needs
 *                JRDP_CLIP_FILES_TO_LOCAL.
 *   FILE_FETCH   u32 streamId, u32 listIndex, u32 flags (FILECONTENTS_SIZE / _RANGE), u32 positionLow,
 *                u32 positionHigh, u32 cbRequested, u8 haveClipDataId, u32 clipDataId — read a file of
 *                the server's FILES list; always answered with FILE_REMOTE_DATA (protocol v9)
 *   CLIP_LOCK_REMOTE  u8 lock (1 = lock, 0 = unlock), u32 clipDataId — ask the server to keep (or
 *                release) its clipboard's files under this id while jterm reads them (protocol v9)
 *   MIC_DATA     u32 streamId, bytes — whole frames in the stream's format (MIC_OPEN), at most
 *                JRDP_AUDIO_MAX_CHUNK bytes. Dropped unless JRDP_AUDIO_MIC was set, a stream is open
 *                and the id is its id (protocol v10)
 *   AUDIO_DELAY  u16 milliseconds — how long sound now waits in jterm before it is heard (queue
 *                plus output). Replaces CONNECT's audioLatencyMs as the render delay reported to
 *                the server; capped at JRDP_AUDIO_DELAY_MAX_MS, ignored without JRDP_AUDIO_PLAYBACK.
 *                Sent when the measured value changes, about once a second at most (protocol v11)
 *   DRIVE_IRP_DONE  u32 completionId, u32 ioStatus (NTSTATUS), bytes — answers DRIVE_IRP: the reply
 *                body that follows the device I/O completion header (MS-RDPEFS)
 *   RESOLVE_REPLY  u8 count, then count x str numeric address (IPv4 or IPv6) — answers RESOLVE;
 *                count 0 = not resolved. At most NETROUTE_MAX_ADDRS are used (protocol v12)
 *   BYE          (or simply close stdin)
 *
 * helper -> jterm
 *   HELLO_REPLY      u16 version, str freerdpVersion
 *   CONNECTED        u16 width, u16 height, u8 unicodeInputSupported
 *   FRAME            u16 x, u16 y, u16 w, u16 h, w*h*4 bytes BGRX (little-endian int = 0x00RRGGBB)
 *   DESKTOP_RESIZED  u16 width, u16 height (a full FRAME follows)
 *   POINTER_NEW      u32 id, u16 w, u16 h, u16 hotX, u16 hotY, w*h*4 bytes BGRA (int = 0xAARRGGBB)
 *   POINTER_FREE     u32 id
 *   POINTER_SET      u32 id
 *   POINTER_HIDE / POINTER_DEFAULT
 *   CERT_REQUEST     str host, u16 port, str commonName, str subject, str issuer,
 *                    str sha256Fingerprint, u32 flags (VERIFY_CERT_FLAG_*)
 *   CLIP_REMOTE_OFFER  u8 kinds — what the server's clipboard now holds (0 = emptied)
 *   CLIP_REMOTE_DATA   u8 kind, u8 ok, bytes — answers CLIP_FETCH. Text is CF_UNICODETEXT as the
 *                      server sent it (UTF-16LE, usually NUL-terminated); an image is a CF_DIB, or a
 *                      CF_DIBV5 from a server without CF_DIB (BITMAPINFOHEADER or BITMAPV5HEADER +
 *                      pixels, no BITMAPFILEHEADER; the header size tells them apart); HTML is
 *                      CF_HTML (UTF-8 with its "Version:/StartHTML:" offset header), as sent.
 *                      ok=0: failed or too large.
 *   CLIP_REQUEST       u8 kind — the server pastes: send CLIP_DATA for it (always answered)
 *   FILE_REQUEST       u32 streamId, u32 listIndex, u32 flags (FILECONTENTS_SIZE / _RANGE),
 *                      u32 positionLow, u32 positionHigh, u32 cbRequested, u8 haveClipDataId,
 *                      u32 clipDataId — the server reads a file jterm described in a FILES
 *                      CLIP_DATA; always answered with FILE_DATA (protocol v8)
 *   CLIP_LOCK          u32 clipDataId — the server pins the clipboard's current files under this id
 *   CLIP_UNLOCK        u32 clipDataId — and releases them (protocol v8)
 *   FILE_REMOTE_DATA   u32 streamId, u8 ok, bytes — answers FILE_FETCH: the server's bytes as sent,
 *                      at most cbRequested (protocol v9)
 *   CLIP_CAPS          u32 generalFlags — the clipboard capabilities both sides agreed on
 *                      (CB_CAN_LOCK_CLIPDATA tells jterm it may lock); sent once the channel is ready
 *   DRIVE_IRP          u32 driveId (from CONNECT, or JRDP_PRINTER/SMARTCARD_DEVICE_ID), u32 fileId, u32 completionId, u32 majorFunction,
 *                      u32 minorFunction, bytes — an I/O request for a shared folder, forwarded as the
 *                      server sent it (the bytes after the 20-byte request header). Always answered
 *                      with DRIVE_IRP_DONE.
 *   AUDIO_OPEN       u32 sampleRate, u8 channels, u8 codec (JRDP_AUDIO_CODEC_*, for display only) —
 *                    the format of the AUDIO_DATA that follows: always signed 16-bit little-endian,
 *                    interleaved; sampleRate from JRDP_AUDIO_RATES, 1 or 2 channels (v10)
 *   AUDIO_DATA       bytes — whole frames, at most JRDP_AUDIO_MAX_CHUNK; larger waves are split
 *   AUDIO_CLOSE      (no payload) — the stream ended (format change or disconnect)
 *   AUDIO_VOLUME     u16 left, u16 right — the server's volume, 0xFFFF = full; jterm only attenuates
 *   MIC_OPEN         u32 streamId, u32 sampleRate, u8 channels, u32 framesPerPacket — the server
 *                    opened a microphone stream; answered by MIC_DATA (v10)
 *   MIC_CLOSE        u32 streamId
 *   RESOLVE          str hostName — the sandboxed helper can't resolve names itself, and FreeRDP
 *                    wants a name not resolved before the lockdown (a server redirect to another
 *                    host): resolve it and answer RESOLVE_REPLY, always (protocol v12)
 *   DISCONNECTED     u32 freerdpErrorCode, str message (last message before exit)
 */
#ifndef JTERM_RDP_PROTOCOL_H
#define JTERM_RDP_PROTOCOL_H

#define JRDP_PROTOCOL_VERSION 13

/* Most shared folders one CONNECT can declare. */
#define JRDP_MAX_DRIVES 24

/* Largest clipboard payload either way. Both sides refuse (ok=0) rather than truncate. */
#define JRDP_CLIP_MAX_BYTES (32u * 1024u * 1024u)

/* Inbound messages are small, except CLIP_DATA; anything larger is a framing error. */
#define JRDP_MAX_INBOUND (JRDP_CLIP_MAX_BYTES + 1024u)

/* jterm -> helper */
#define JRDP_HELLO 0x01
#define JRDP_CONNECT 0x02
#define JRDP_KEY 0x03
#define JRDP_UNICODE 0x04
#define JRDP_MOUSE 0x05
#define JRDP_CERT_REPLY 0x06
#define JRDP_RESIZE 0x07
#define JRDP_CLIP_OFFER 0x08
#define JRDP_CLIP_DATA 0x09
#define JRDP_CLIP_FETCH 0x0A
#define JRDP_DRIVE_IRP_DONE 0x0B
#define JRDP_FILE_DATA 0x0C
#define JRDP_FILE_FETCH 0x0D
#define JRDP_CLIP_LOCK_REMOTE 0x0E
#define JRDP_BYE 0x0F
#define JRDP_MIC_DATA 0x10
#define JRDP_AUDIO_DELAY 0x11
#define JRDP_RESOLVE_REPLY 0x12

/* Largest AUDIO_DELAY the helper reports on; a larger value is taken as this. */
#define JRDP_AUDIO_DELAY_MAX_MS 1000u

/* CONNECT clipboardFlags */
#define JRDP_CLIP_REMOTE_TO_LOCAL 0x01 /* the server's clipboard may be fetched into jterm */
#define JRDP_CLIP_LOCAL_TO_REMOTE 0x02 /* the server may paste from jterm's clipboard */
#define JRDP_CLIP_FILES_TO_REMOTE 0x04 /* ...files too (needs LOCAL_TO_REMOTE; protocol v8) */
#define JRDP_CLIP_FILES_TO_LOCAL 0x08  /* the server's files may be read (needs REMOTE_TO_LOCAL; v9) */

/* CONNECT securityFlags. NLA is always offered and is the only choice by default; the legacy RDP
 * security layer is never offered (it cannot verify the server's certificate). */
#define JRDP_SEC_ALLOW_TLS 0x01 /* also accept TLS without NLA: certificate checked, password sent
                                   to the server after the handshake rather than via CredSSP */

/* CONNECT gatewayFlags */
#define JRDP_GW_ENABLED 0x01          /* connect through the RD Gateway, always (no local bypass) */
#define JRDP_GW_SAME_CREDENTIALS 0x02 /* the gateway takes the desktop's user, domain and password */

/* CONNECT audioFlags (protocol v10). Without PLAYBACK and MIC no audio channel exists. */
#define JRDP_AUDIO_PLAYBACK 0x01 /* load rdpsnd with jterm's forwarding device */
#define JRDP_AUDIO_MIC 0x02      /* load audin with jterm's forwarding device */
#define JRDP_AUDIO_AAC 0x04      /* the sound device may claim AAC if a decoder is found (needs PLAYBACK) */

/* CONNECT printerFlags (protocol v13). Without ENABLED no printer is announced. */
#define JRDP_PRINTER_ENABLED 0x01 /* announce one printer; its jobs go to jterm */
#define JRDP_PRINTER_DEFAULT 0x02 /* ...as the session's default printer */

/* The printer's device id in DRIVE_IRP: outside the range jterm gives shared folders. */
#define JRDP_PRINTER_DEVICE_ID 0xFFFF0001u

/* CONNECT smartcardFlags (protocol v13). Without ENABLED no smart-card device is announced. */
#define JRDP_SMARTCARD_ENABLED 0x01 /* announce one smart-card device; its calls go to jterm */

/* The smart-card device's id in DRIVE_IRP. */
#define JRDP_SMARTCARD_DEVICE_ID 0xFFFF0002u

/* Longest printer or driver name CONNECT may carry, in characters. */
#define JRDP_PRINTER_NAME_MAX 64

/* AUDIO_OPEN codec: where the PCM came from (display only) */
#define JRDP_AUDIO_CODEC_PCM 0
#define JRDP_AUDIO_CODEC_AAC 1

/* Largest AUDIO_DATA / MIC_DATA sample payload. */
#define JRDP_AUDIO_MAX_CHUNK 65536u

/* The only stream formats either side accepts: these rates, 1 or 2 channels, 16 bits. */
#define JRDP_AUDIO_RATES { 8000, 11025, 16000, 22050, 32000, 44100, 48000 }

/* CLIP_* kinds (a bit mask in offers, a single value elsewhere) */
#define JRDP_CLIPKIND_TEXT 0x01
#define JRDP_CLIPKIND_IMAGE 0x02
#define JRDP_CLIPKIND_HTML 0x04 /* protocol v6 */
#define JRDP_CLIPKIND_FILES 0x08 /* "FileGroupDescriptorW": local -> remote v8, remote -> local v9 */

/* Most file-contents requests awaiting jterm's FILE_DATA; beyond that the server is refused at once. */
#define JRDP_FILE_PENDING_MAX 64
/* Most of jterm's own FILE_FETCH requests outstanding at the server; beyond that, refused at once. */
#define JRDP_FETCH_PENDING_MAX 16

#define JRDP_KEY_FORCE_EXTENDED 0x01     /* e.g. numpad Enter */
#define JRDP_KEY_FORCE_NOT_EXTENDED 0x02 /* e.g. numpad arrows with NumLock off */
#define JRDP_KEY_REPEAT 0x04

/* helper -> jterm */
#define JRDP_HELLO_REPLY 0x81
#define JRDP_CONNECTED 0x82
#define JRDP_FRAME 0x83
#define JRDP_DESKTOP_RESIZED 0x84
#define JRDP_POINTER_NEW 0x85
#define JRDP_POINTER_FREE 0x86
#define JRDP_POINTER_SET 0x87
#define JRDP_POINTER_HIDE 0x88
#define JRDP_POINTER_DEFAULT 0x89
#define JRDP_CERT_REQUEST 0x8A
#define JRDP_CLIP_REMOTE_OFFER 0x8B
#define JRDP_CLIP_REMOTE_DATA 0x8C
#define JRDP_CLIP_REQUEST 0x8D
#define JRDP_DRIVE_IRP 0x8E
#define JRDP_FILE_REQUEST 0x90
#define JRDP_CLIP_LOCK 0x91
#define JRDP_CLIP_UNLOCK 0x92
#define JRDP_FILE_REMOTE_DATA 0x93
#define JRDP_CLIP_CAPS 0x94
#define JRDP_AUDIO_OPEN 0x95
#define JRDP_AUDIO_DATA 0x96
#define JRDP_AUDIO_CLOSE 0x97
#define JRDP_AUDIO_VOLUME 0x98
#define JRDP_MIC_OPEN 0x99
#define JRDP_MIC_CLOSE 0x9A
#define JRDP_RESOLVE 0x9B
#define JRDP_DISCONNECTED 0x8F

#endif
