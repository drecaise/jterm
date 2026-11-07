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
 *                u16 width, u16 height (device pixels), u16 scalePercent (100 = none)
 *   KEY          u8 down, u16 windowsVirtualKey, u8 flags (JRDP_KEY_*)
 *   UNICODE      u8 down, u16 utf16CodeUnit
 *   MOUSE        u16 ptrFlags (PTR_FLAGS_*), u16 x, u16 y
 *   CERT_REPLY   u8 accept (answers CERT_REQUEST; accept means "this session only")
 *   RESIZE       u16 width, u16 height (device pixels), u16 scalePercent — sent to the server over
 *                the Display Control channel once it is ready (servers without it ignore this)
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
 *   DISCONNECTED     u32 freerdpErrorCode, str message (last message before exit)
 */
#ifndef JTERM_RDP_PROTOCOL_H
#define JTERM_RDP_PROTOCOL_H

#define JRDP_PROTOCOL_VERSION 1

/* Inbound messages are small; anything larger is a framing error. */
#define JRDP_MAX_INBOUND (64u * 1024u)

/* jterm -> helper */
#define JRDP_HELLO 0x01
#define JRDP_CONNECT 0x02
#define JRDP_KEY 0x03
#define JRDP_UNICODE 0x04
#define JRDP_MOUSE 0x05
#define JRDP_CERT_REPLY 0x06
#define JRDP_RESIZE 0x07
#define JRDP_BYE 0x0F

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
#define JRDP_DISCONNECTED 0x8F

#endif
