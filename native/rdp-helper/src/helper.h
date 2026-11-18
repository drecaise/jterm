/*
 * jterm-rdp-helper: what the helper's source files share (protocol output).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef JTERM_RDP_HELPER_H
#define JTERM_RDP_HELPER_H

#include <winpr/wtypes.h>

/* A growing payload buffer. The buf_* writers append little-endian values; a failed allocation
 * leaves the buffer as it was (the message is then sent short, which jterm treats as malformed). */
typedef struct
{
	BYTE* data;
	size_t len;
	size_t cap;
} Buf;

BOOL buf_reserve(Buf* b, size_t extra);
void buf_u8(Buf* b, UINT8 v);
void buf_u16(Buf* b, UINT16 v);
void buf_u32(Buf* b, UINT32 v);
void buf_str(Buf* b, const char* s);
void buf_bytes(Buf* b, const BYTE* p, size_t n);

/* Sends one complete message to jterm; thread-safe (serialised by the write lock). */
void send_msg(UINT8 type, const Buf* payload);

#endif
