/*
 * jterm-rdp-helper — out-of-process RDP client for jterm.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * jterm launches this program and talks to it over stdin/stdout using the binary protocol in
 * protocol.h. The helper owns the FreeRDP connection and the decoded framebuffer; jterm paints the
 * frames and sends input. Running FreeRDP in its own process means a crash or a memory-safety bug
 * triggered by a hostile server cannot reach jterm's JVM (and its unlocked credential vault).
 *
 * Rules this file relies on:
 *  - The protocol stream is a private dup of fd 1; fd 1 itself is pointed at stderr, so nothing
 *    FreeRDP/WLog prints can corrupt the protocol.
 *  - Secrets arrive only in the CONNECT message — never argv or the environment.
 *  - EOF on stdin (jterm exited or closed us) aborts the connection and exits: no orphans.
 *  - Certificates: a fingerprint pinned by jterm is accepted by FreeRDP before anything else;
 *    otherwise normal CA + hostname validation runs, and only a certificate that fails it is
 *    referred to jterm (CERT_REQUEST). Accepts are returned as "this session only" (2) so
 *    FreeRDP's own known_hosts store is never written — jterm owns the trust store.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <winpr/crt.h>
#include <winpr/interlocked.h>
#include <winpr/synch.h>
#include <winpr/thread.h>
#include <winpr/input.h>
#include <winpr/user.h>
#include <winpr/winsock.h>
#include <winpr/crypto.h>

#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/client/channels.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/graphics.h>
#include <freerdp/codec/color.h>
#include <freerdp/codec/h264.h>
#include <freerdp/client/disp.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/channels/rdpgfx.h>
#include <winpr/sysinfo.h>
#include <freerdp/channels/cliprdr.h>
#include <freerdp/channels/rdpdr.h>
#include <freerdp/addin.h>
#include <freerdp/utils/helpers.h>
#include <freerdp/version.h>

#ifdef _WIN32
#include <io.h>
#include <fcntl.h>
#define dup _dup
#define dup2 _dup2
#define fdopen _fdopen
#define fileno _fileno
#else
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#endif

#include "protocol.h"
#include "helper.h"
#include "audio.h"
#include "netroute.h"
#ifdef __APPLE__
#include "h264_test_frame.h"
#endif
#include "sandbox.h"

/* ------------------------------------------------------------------------------------------ */
/* Client context                                                                             */

typedef struct
{
	rdpClientContext common; /* must be first */
} jrdpContext;

typedef struct
{
	rdpPointer pointer; /* must be first */
	UINT32 id;
} jrdpPointer;

static FILE* g_out = NULL;            /* protocol output (private dup of the original stdout) */
static CRITICAL_SECTION g_write_lock; /* serialises whole messages on g_out */
static CRITICAL_SECTION g_input_lock; /* guards input sends against teardown */
static volatile LONG g_connected = 0; /* input is forwarded only while this is set */
static rdpContext* g_context = NULL;
static UINT32 g_next_pointer_id = 0;

/* Display Control (monitor layout = size + scale). GNOME Remote Desktop ignores the scale in the
 * connection's core data and only honours the per-monitor scale sent over this channel. The
 * layout is always sent from the RDP thread: the reader thread only records the wanted layout
 * and signals g_layout_event, which the main loop waits on alongside FreeRDP's handles. */
static DispClientContext* g_disp = NULL;
static volatile LONG g_disp_ready = 0;
static HANDLE g_layout_event = NULL;
static volatile LONG g_want_w = 0, g_want_h = 0, g_want_scale = 100;

/* CERT_REQUEST is answered by the stdin reader thread. */
static HANDLE g_cert_event = NULL;
static volatile LONG g_cert_decision = 0;

/* RESOLVE is answered by the stdin reader thread too (RESOLVE_REPLY), into these, under the lock. */
static HANDLE g_resolve_event = NULL;
static CRITICAL_SECTION g_resolve_lock;
static char g_resolve_addrs[NETROUTE_MAX_ADDRS][NETROUTE_ADDR_LEN];
static int g_resolve_count = 0;

/* ------------------------------------------------------------------------------------------ */
/* Protocol output                                                                            */

BOOL buf_reserve(Buf* b, size_t extra)
{
	if (b->len + extra <= b->cap)
		return TRUE;
	size_t cap = b->cap ? b->cap : 256;
	while (cap < b->len + extra)
		cap *= 2;
	BYTE* p = realloc(b->data, cap);
	if (!p)
		return FALSE;
	b->data = p;
	b->cap = cap;
	return TRUE;
}

void buf_u8(Buf* b, UINT8 v)
{
	if (buf_reserve(b, 1))
		b->data[b->len++] = v;
}

void buf_u16(Buf* b, UINT16 v)
{
	if (buf_reserve(b, 2))
	{
		b->data[b->len++] = (BYTE)(v & 0xFF);
		b->data[b->len++] = (BYTE)(v >> 8);
	}
}

void buf_u32(Buf* b, UINT32 v)
{
	if (buf_reserve(b, 4))
	{
		for (int i = 0; i < 4; i++)
			b->data[b->len++] = (BYTE)((v >> (8 * i)) & 0xFF);
	}
}

void buf_str(Buf* b, const char* s)
{
	size_t n = s ? strlen(s) : 0;
	if (n > 0xFFFF)
		n = 0xFFFF;
	buf_u16(b, (UINT16)n);
	if (n && buf_reserve(b, n))
	{
		memcpy(b->data + b->len, s, n);
		b->len += n;
	}
}

void buf_bytes(Buf* b, const BYTE* p, size_t n)
{
	if (n && buf_reserve(b, n))
	{
		memcpy(b->data + b->len, p, n);
		b->len += n;
	}
}

static void write_u32_raw(UINT32 v)
{
	BYTE le[4] = { (BYTE)v, (BYTE)(v >> 8), (BYTE)(v >> 16), (BYTE)(v >> 24) };
	fwrite(le, 1, 4, g_out);
}

/* Send a complete message: u32 length (type + payload), u8 type, payload. */
void send_msg(UINT8 type, const Buf* payload)
{
	EnterCriticalSection(&g_write_lock);
	const size_t n = payload ? payload->len : 0;
	write_u32_raw((UINT32)(1 + n));
	fputc(type, g_out);
	if (n)
		fwrite(payload->data, 1, n, g_out);
	fflush(g_out);
	LeaveCriticalSection(&g_write_lock);
}

/* ------------------------------------------------------------------------------------------ */
/* Statistics: one line a second on stderr while frames flow, which jterm shows on request.     */
/*
 * "Why does video stutter" has four possible answers (the server sends few frames, the decoder is
 * slow, the pipe to jterm is slow, jterm paints slowly), and they can't be told apart by eye. This
 * counts what passes through here: frames and megabytes sent to jterm, how long the writes took,
 * and which codec the server's graphics commands used. jterm adds its own half.
 */
static volatile LONG g_stat_frames = 0;    /* EndPaint calls that sent something */
static volatile LONG g_stat_rects = 0;     /* FRAME messages */
static volatile LONG g_stat_kbytes = 0;    /* pixel data sent, in KiB */
static volatile LONG g_stat_write_us = 0;  /* time inside the frame writes, total */
static volatile LONG g_stat_write_max_us = 0;
static volatile LONG g_stat_h264 = 0;      /* graphics commands by codec */
static volatile LONG g_stat_avc444 = 0;
static volatile LONG g_stat_progressive = 0;
static volatile LONG g_stat_other = 0;
static UINT64 g_stat_since_ns = 0; /* RDP thread only */

/* RDP thread, after a frame went out. Prints and resets once a second. */
static void stats_tick(void)
{
	const UINT64 now = winpr_GetTickCount64NS();
	if (g_stat_since_ns == 0)
		g_stat_since_ns = now;
	const UINT64 elapsed = now - g_stat_since_ns;
	if (elapsed < 1000000000ull)
		return;
	g_stat_since_ns = now;
	const LONG frames = InterlockedExchange(&g_stat_frames, 0);
	const LONG rects = InterlockedExchange(&g_stat_rects, 0);
	const LONG kbytes = InterlockedExchange(&g_stat_kbytes, 0);
	const LONG writeUs = InterlockedExchange(&g_stat_write_us, 0);
	const LONG writeMaxUs = InterlockedExchange(&g_stat_write_max_us, 0);
	const LONG h264 = InterlockedExchange(&g_stat_h264, 0);
	const LONG avc444 = InterlockedExchange(&g_stat_avc444, 0);
	const LONG progressive = InterlockedExchange(&g_stat_progressive, 0);
	const LONG other = InterlockedExchange(&g_stat_other, 0);
	/* Parsed by jterm (RdpStats): keep the "stats " prefix and the key=value form. */
	fprintf(stderr,
	        "jterm-rdp-helper: stats ms=%u frames=%ld rects=%ld kib=%ld write_us=%ld write_max_us=%ld "
	        "h264=%ld avc444=%ld progressive=%ld other=%ld\n",
	        (unsigned)(elapsed / 1000000ull), (long)frames, (long)rects, (long)kbytes, (long)writeUs,
	        (long)writeMaxUs, (long)h264, (long)avc444, (long)progressive, (long)other);
}

/* Rows of a rectangle that is narrower than the framebuffer, packed here to go out in one write. */
static BYTE* g_frame_scratch = NULL; /* guarded by g_write_lock */
static size_t g_frame_scratch_cap = 0;

/* FRAME: header, then the dirty rectangle's pixels out of the framebuffer (BGRX32).
 *
 * The pixels go out in ONE write. They used to go a row at a time, which on Windows was the whole
 * reason video stuttered: the pipe Java gives a child process holds 4 KiB, a write larger than that
 * waits for the reader, and a 2560x1440 frame was 1440 such round trips. Measured on Windows 11
 * with that write pattern: 1 frame a second (15 MB/s) by rows, 118 frames a second in one write.
 * Linux pipes hold 64 KiB and never showed it. Don't go back to a write per row. */
static void send_frame(rdpGdi* gdi, INT32 x, INT32 y, INT32 w, INT32 h)
{
	if (x < 0)
	{
		w += x;
		x = 0;
	}
	if (y < 0)
	{
		h += y;
		y = 0;
	}
	if (x + w > gdi->width)
		w = gdi->width - x;
	if (y + h > gdi->height)
		h = gdi->height - y;
	if (w <= 0 || h <= 0)
		return;

	const size_t rowBytes = (size_t)w * 4;
	const size_t total = rowBytes * (size_t)h;
	const BYTE* first = gdi->primary_buffer + (size_t)y * gdi->stride + (size_t)x * 4;
	EnterCriticalSection(&g_write_lock);
	const BYTE* pixels = first;
	if (rowBytes != (size_t)gdi->stride && h > 1)
	{
		/* The rows are not adjacent in the framebuffer: pack them first. */
		if (g_frame_scratch_cap < total)
		{
			BYTE* grown = realloc(g_frame_scratch, total);
			if (!grown)
			{
				LeaveCriticalSection(&g_write_lock);
				return; /* out of memory: this update is lost, the next full one repairs it */
			}
			g_frame_scratch = grown;
			g_frame_scratch_cap = total;
		}
		for (INT32 row = 0; row < h; row++)
			memcpy(g_frame_scratch + (size_t)row * rowBytes, first + (size_t)row * gdi->stride,
			       rowBytes);
		pixels = g_frame_scratch;
	}
	const UINT64 t0 = winpr_GetTickCount64NS();
	write_u32_raw((UINT32)(1 + 8 + total));
	fputc(JRDP_FRAME, g_out);
	BYTE hdr[8] = { (BYTE)x, (BYTE)(x >> 8), (BYTE)y, (BYTE)(y >> 8),
		            (BYTE)w, (BYTE)(w >> 8), (BYTE)h, (BYTE)(h >> 8) };
	fwrite(hdr, 1, 8, g_out);
	fwrite(pixels, 1, total, g_out);
	fflush(g_out);
	const LONG us = (LONG)((winpr_GetTickCount64NS() - t0) / 1000ull);
	LeaveCriticalSection(&g_write_lock);

	InterlockedIncrement(&g_stat_rects);
	InterlockedExchangeAdd(&g_stat_kbytes, (LONG)(total / 1024));
	InterlockedExchangeAdd(&g_stat_write_us, us);
	if (us > InterlockedCompareExchange(&g_stat_write_max_us, 0, 0))
		InterlockedExchange(&g_stat_write_max_us, us); /* RDP thread only writes it */
}

static void send_disconnected(UINT32 code, const char* message)
{
	Buf b = { 0 };
	buf_u32(&b, code);
	buf_str(&b, message);
	send_msg(JRDP_DISCONNECTED, &b);
	free(b.data);
}

/* ------------------------------------------------------------------------------------------ */
/* Protocol input                                                                             */

static BOOL read_full(void* dst, size_t n)
{
	BYTE* p = dst;
	while (n)
	{
		size_t r = fread(p, 1, n, stdin);
		if (r == 0)
			return FALSE;
		p += r;
		n -= r;
	}
	return TRUE;
}

typedef struct
{
	BYTE* data;
	size_t len;
	size_t pos;
	UINT8 type;
} Msg;

/* Reads one message; FALSE on EOF or a malformed/oversized frame. */
static BOOL read_msg(Msg* m)
{
	BYTE le[4];
	if (!read_full(le, 4))
		return FALSE;
	const UINT32 len = (UINT32)le[0] | ((UINT32)le[1] << 8) | ((UINT32)le[2] << 16) |
	                   ((UINT32)le[3] << 24);
	if (len < 1 || len > JRDP_MAX_INBOUND)
		return FALSE;
	m->data = malloc(len);
	if (!m->data || !read_full(m->data, len))
	{
		free(m->data);
		m->data = NULL;
		return FALSE;
	}
	m->type = m->data[0];
	m->len = len;
	m->pos = 1;
	return TRUE;
}

static void msg_free(Msg* m)
{
	if (m->data)
	{
		/* CONNECT carries a password: scrub before freeing. */
		SecureZeroMemory(m->data, m->len);
		free(m->data);
	}
	m->data = NULL;
}

static UINT8 get_u8(Msg* m)
{
	return (m->pos + 1 <= m->len) ? m->data[m->pos++] : 0;
}

static UINT16 get_u16(Msg* m)
{
	if (m->pos + 2 > m->len)
		return 0;
	UINT16 v = (UINT16)(m->data[m->pos] | (m->data[m->pos + 1] << 8));
	m->pos += 2;
	return v;
}

static UINT32 get_u32(Msg* m)
{
	if (m->pos + 4 > m->len)
		return 0;
	UINT32 v = (UINT32)m->data[m->pos] | ((UINT32)m->data[m->pos + 1] << 8) |
	           ((UINT32)m->data[m->pos + 2] << 16) | ((UINT32)m->data[m->pos + 3] << 24);
	m->pos += 4;
	return v;
}

/* Returns a NUL-terminated copy (caller frees, scrubbing if secret). */
static char* get_str(Msg* m)
{
	UINT16 n = get_u16(m);
	if (m->pos + n > m->len)
		n = 0;
	char* s = calloc(1, (size_t)n + 1);
	if (s && n)
		memcpy(s, m->data + m->pos, n);
	m->pos += n;
	return s;
}

static void free_secret(char* s)
{
	if (s)
	{
		SecureZeroMemory(s, strlen(s));
		free(s);
	}
}

/* ------------------------------------------------------------------------------------------ */
/* Pointer (cursor) — GDI does not draw the cursor into the framebuffer, so forward shapes.    */

static BOOL jp_new(rdpContext* context, rdpPointer* pointer)
{
	jrdpPointer* jp = (jrdpPointer*)pointer;
	const UINT32 w = pointer->width;
	const UINT32 h = pointer->height;
	jp->id = ++g_next_pointer_id;
	if (w == 0 || h == 0 || w > 384 || h > 384)
		return TRUE; /* nothing sensible to show; Set() will fall back to the default cursor */

	BYTE* argb = calloc((size_t)w * h, 4);
	if (!argb)
		return FALSE;
	if (!freerdp_image_copy_from_pointer_data(argb, PIXEL_FORMAT_BGRA32, w * 4, 0, 0, w, h,
	                                          pointer->xorMaskData, pointer->lengthXorMask,
	                                          pointer->andMaskData, pointer->lengthAndMask,
	                                          pointer->xorBpp, &context->gdi->palette))
	{
		free(argb);
		return FALSE;
	}
	Buf b = { 0 };
	buf_u32(&b, jp->id);
	buf_u16(&b, (UINT16)w);
	buf_u16(&b, (UINT16)h);
	buf_u16(&b, (UINT16)pointer->xPos);
	buf_u16(&b, (UINT16)pointer->yPos);
	if (buf_reserve(&b, (size_t)w * h * 4))
	{
		memcpy(b.data + b.len, argb, (size_t)w * h * 4);
		b.len += (size_t)w * h * 4;
	}
	send_msg(JRDP_POINTER_NEW, &b);
	free(b.data);
	free(argb);
	return TRUE;
}

static void jp_free(rdpContext* context, rdpPointer* pointer)
{
	WINPR_UNUSED(context);
	Buf b = { 0 };
	buf_u32(&b, ((jrdpPointer*)pointer)->id);
	send_msg(JRDP_POINTER_FREE, &b);
	free(b.data);
}

static BOOL jp_set(rdpContext* context, rdpPointer* pointer)
{
	WINPR_UNUSED(context);
	Buf b = { 0 };
	buf_u32(&b, ((jrdpPointer*)pointer)->id);
	send_msg(JRDP_POINTER_SET, &b);
	free(b.data);
	return TRUE;
}

static BOOL jp_set_null(rdpContext* context)
{
	WINPR_UNUSED(context);
	send_msg(JRDP_POINTER_HIDE, NULL);
	return TRUE;
}

static BOOL jp_set_default(rdpContext* context)
{
	WINPR_UNUSED(context);
	send_msg(JRDP_POINTER_DEFAULT, NULL);
	return TRUE;
}

static BOOL jp_set_position(rdpContext* context, UINT32 x, UINT32 y)
{
	WINPR_UNUSED(context);
	WINPR_UNUSED(x);
	WINPR_UNUSED(y);
	return TRUE; /* server-driven warps are ignored: jterm never moves the user's mouse */
}

/* ------------------------------------------------------------------------------------------ */
/* Paint                                                                                      */

static BOOL j_begin_paint(rdpContext* context)
{
	HGDI_WND hwnd = context->gdi->primary->hdc->hwnd;
	hwnd->invalid->null = TRUE;
	hwnd->ninvalid = 0;
	return TRUE;
}

static BOOL j_end_paint(rdpContext* context)
{
	rdpGdi* gdi = context->gdi;
	HGDI_WND hwnd = gdi->primary->hdc->hwnd;
	if (!hwnd || hwnd->invalid->null)
		return TRUE;

	InterlockedIncrement(&g_stat_frames);
	if (hwnd->ninvalid <= 0)
	{
		send_frame(gdi, hwnd->invalid->x, hwnd->invalid->y, hwnd->invalid->w, hwnd->invalid->h);
		stats_tick();
		return TRUE;
	}
	/* Many small rects: send the bounding box instead once that's cheaper in headers. */
	INT64 area = 0;
	for (INT32 i = 0; i < hwnd->ninvalid; i++)
		area += (INT64)hwnd->cinvalid[i].w * hwnd->cinvalid[i].h;
	const INT64 bbox = (INT64)hwnd->invalid->w * hwnd->invalid->h;
	if (hwnd->ninvalid > 32 || area >= bbox)
	{
		send_frame(gdi, hwnd->invalid->x, hwnd->invalid->y, hwnd->invalid->w, hwnd->invalid->h);
		stats_tick();
		return TRUE;
	}
	for (INT32 i = 0; i < hwnd->ninvalid; i++)
		send_frame(gdi, hwnd->cinvalid[i].x, hwnd->cinvalid[i].y, hwnd->cinvalid[i].w,
		           hwnd->cinvalid[i].h);
	stats_tick();
	return TRUE;
}

static BOOL j_desktop_resize(rdpContext* context)
{
	rdpSettings* settings = context->settings;
	const UINT32 w = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
	const UINT32 h = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
	if (!gdi_resize(context->gdi, w, h))
		return FALSE;
	Buf b = { 0 };
	buf_u16(&b, (UINT16)w);
	buf_u16(&b, (UINT16)h);
	send_msg(JRDP_DESKTOP_RESIZED, &b);
	free(b.data);
	send_frame(context->gdi, 0, 0, (INT32)w, (INT32)h);
	return TRUE;
}

/* ------------------------------------------------------------------------------------------ */
/* Certificates                                                                               */

static DWORD ask_cert(const char* host, UINT16 port, const char* cn, const char* subject,
                      const char* issuer, const char* fingerprint, DWORD flags)
{
	if (flags & VERIFY_CERT_FLAG_FP_IS_PEM)
		return 0; /* never requested; refuse rather than show a PEM as a fingerprint */
	ResetEvent(g_cert_event);
	InterlockedExchange(&g_cert_decision, 0);
	Buf b = { 0 };
	buf_str(&b, host);
	buf_u16(&b, port);
	buf_str(&b, cn);
	buf_str(&b, subject);
	buf_str(&b, issuer);
	buf_str(&b, fingerprint);
	buf_u32(&b, flags);
	send_msg(JRDP_CERT_REQUEST, &b);
	free(b.data);
	WaitForSingleObject(g_cert_event, INFINITE);
	/* 2 = accept for this session only: FreeRDP's own store is never written. */
	return InterlockedCompareExchange(&g_cert_decision, 0, 0) ? 2 : 0;
}

/* netroute.h: a name first seen after the lockdown (a redirect to another host) is resolved by
 * jterm. Called on the RDP thread, like a certificate prompt, and blocks the same way. */
int netroute_ask_jterm(const char* name, char addrs[][NETROUTE_ADDR_LEN], int max)
{
	EnterCriticalSection(&g_resolve_lock);
	ResetEvent(g_resolve_event);
	g_resolve_count = 0;
	LeaveCriticalSection(&g_resolve_lock);
	Buf b = { 0 };
	buf_str(&b, name);
	send_msg(JRDP_RESOLVE, &b);
	free(b.data);
	WaitForSingleObject(g_resolve_event, INFINITE);
	EnterCriticalSection(&g_resolve_lock);
	const int n = g_resolve_count < max ? g_resolve_count : max;
	for (int i = 0; i < n; i++)
		memcpy(addrs[i], g_resolve_addrs[i], NETROUTE_ADDR_LEN);
	LeaveCriticalSection(&g_resolve_lock);
	return n;
}

static DWORD j_verify_cert(freerdp* instance, const char* host, UINT16 port, const char* cn,
                           const char* subject, const char* issuer, const char* fingerprint,
                           DWORD flags)
{
	WINPR_UNUSED(instance);
	return ask_cert(host, port, cn, subject, issuer, fingerprint, flags);
}

static DWORD j_verify_changed_cert(freerdp* instance, const char* host, UINT16 port,
                                   const char* cn, const char* subject, const char* issuer,
                                   const char* fingerprint, const char* old_subject,
                                   const char* old_issuer, const char* old_fingerprint,
                                   DWORD flags)
{
	WINPR_UNUSED(instance);
	WINPR_UNUSED(old_subject);
	WINPR_UNUSED(old_issuer);
	WINPR_UNUSED(old_fingerprint);
	return ask_cert(host, port, cn, subject, issuer, fingerprint, flags | VERIFY_CERT_FLAG_CHANGED);
}

/* Credentials come only from CONNECT; a server asking again fails the connection for now. */
static BOOL j_authenticate(freerdp* instance, char** username, char** password, char** domain,
                           rdp_auth_reason reason)
{
	WINPR_UNUSED(instance);
	WINPR_UNUSED(username);
	WINPR_UNUSED(domain);
	WINPR_UNUSED(reason);
	return (password && *password && **password);
}

/* ------------------------------------------------------------------------------------------ */
/* Clipboard (cliprdr)                                                                         */
/*
 * Two directions, each opt-in per session (CONNECT's clipboardFlags; the channel is not even opened
 * when both are off). Both rely on the protocol's delayed rendering, so nothing is copied until it
 * is wanted:
 *  - local -> remote: jterm offers a *kind* (CLIP_OFFER); the helper announces the matching format.
 *    The bytes cross only when the server pastes (ServerFormatDataRequest -> CLIP_REQUEST ->
 *    CLIP_DATA -> ClientFormatDataResponse).
 *  - remote -> local: the server's format list becomes CLIP_REMOTE_OFFER; jterm answers CLIP_FETCH
 *    and the helper requests that one format (ClientFormatDataRequest -> CLIP_REMOTE_DATA).
 * Understood: text (CF_UNICODETEXT), images (CF_DIB, or CF_DIBV5 from a server that offers no
 * CF_DIB) and HTML (the registered "HTML Format", i.e. CF_HTML). Every other format is ignored.
 *
 * Files, local -> remote only and only with JRDP_CLIP_FILES_TO_REMOTE (protocol v8): jterm offers
 * FILES, the helper announces "FileGroupDescriptorW", and relays the server's requests as they are.
 * The descriptor list is jterm's CLIP_DATA, passed through unparsed; each FileContentsRequest
 * becomes FILE_REQUEST and its answer FILE_DATA; Lock/Unlock become CLIP_LOCK/CLIP_UNLOCK. The
 * helper never sees a local path: descriptors carry names only (CB_FILECLIP_NO_FILE_PATHS), and
 * jterm alone decides which file a request may read.
 *
 * Files, remote -> local, only with JRDP_CLIP_FILES_TO_LOCAL (protocol v9): a server format list
 * holding "FileGroupDescriptorW" adds FILES to CLIP_REMOTE_OFFER; CLIP_FETCH FILES returns the list
 * as the server sent it (jterm parses it, never this code); FILE_FETCH becomes a
 * ClientFileContentsRequest and its response FILE_REMOTE_DATA; CLIP_LOCK_REMOTE becomes Lock/Unlock.
 * Nothing is written by the helper: jterm writes the files.
 *
 * "HTML Format" has no fixed id: each side registers it and announces its own number with the name.
 * The server's number is learnt from its format list; ours is JRDP_LOCAL_HTML_FORMAT_ID.
 *
 * Threads: FreeRDP's channel calls happen on the RDP thread, like the monitor layout. The stdin
 * reader thread therefore only queues a job and signals g_clip_event; the main loop drains it.
 */
typedef struct ClipJob
{
	struct ClipJob* next;
	UINT8 type; /* JRDP_CLIP_OFFER / CLIP_DATA / CLIP_FETCH / FILE_DATA */
	UINT8 kind; /* for an offer: the kinds mask */
	UINT32 id;  /* FILE_DATA / FILE_FETCH: the stream id; CLIP_LOCK_REMOTE: the clipDataId */
	CLIPRDR_FILE_CONTENTS_REQUEST fetch; /* FILE_FETCH */
	UINT8 ok;
	BYTE* data;
	size_t len;
} ClipJob;

static CRITICAL_SECTION g_clip_lock;
static ClipJob* g_clip_head = NULL;
static ClipJob* g_clip_tail = NULL;
static HANDLE g_clip_event = NULL;
static UINT8 g_clip_flags = 0; /* written once by apply_connect, before the reader thread starts */

/* RDP thread only from here. */
static CliprdrClientContext* g_clip = NULL;
static BOOL g_clip_ready = FALSE;    /* MonitorReady seen: the server accepts our format list */
static UINT8 g_clip_local_kinds = 0; /* what jterm last offered */
static BOOL g_remote_has_text = FALSE;
static UINT32 g_remote_image_id = 0; /* CF_DIB, else CF_DIBV5, else 0 */
static UINT32 g_remote_html_id = 0;  /* the server's id for "HTML Format", 0 = not offered */
static UINT32 g_remote_files_id = 0; /* ...for "FileGroupDescriptorW", 0 = not offered (or off) */
static UINT32 g_server_clip_flags = 0; /* the server's general capability flags */
static UINT8 g_fetch_kind = 0; /* our request to the server awaiting its response */
static UINT8 g_serve_kind = 0; /* the server's request awaiting jterm's CLIP_DATA */

/* File-contents requests forwarded to jterm and not yet answered. */
typedef struct
{
	BOOL used;
	UINT32 streamId;
	UINT32 flags; /* FILECONTENTS_SIZE / FILECONTENTS_RANGE */
	UINT32 cbRequested;
} PendingFile;
static PendingFile g_file_pending[JRDP_FILE_PENDING_MAX];

/* jterm's FILE_FETCH requests sent to the server and not yet answered. */
typedef struct
{
	BOOL used;
	UINT32 streamId;
	UINT32 cbRequested;
} PendingFetch;
static PendingFetch g_fetch_pending[JRDP_FETCH_PENDING_MAX];

/* The registered-format id this client announces "HTML Format" under (any id in 0xC000-0xFFFF). */
#define JRDP_LOCAL_HTML_FORMAT_ID 0xD010
static const char CLIP_HTML_FORMAT_NAME[] = "HTML Format";
/* ...and the one it announces a file list under (protocol v8). */
#define JRDP_LOCAL_FILES_FORMAT_ID 0xD011
static const char CLIP_FILES_FORMAT_NAME[] = "FileGroupDescriptorW";

static BOOL clip_files_enabled(void)
{
	return (g_clip_flags & JRDP_CLIP_LOCAL_TO_REMOTE) && (g_clip_flags & JRDP_CLIP_FILES_TO_REMOTE);
}

static BOOL clip_files_to_local_enabled(void)
{
	return (g_clip_flags & JRDP_CLIP_REMOTE_TO_LOCAL) && (g_clip_flags & JRDP_CLIP_FILES_TO_LOCAL);
}

/* The server's format id to fetch for a kind, 0 when the server does not hold it. */
static UINT32 clip_remote_format_for(UINT8 kind)
{
	switch (kind)
	{
		case JRDP_CLIPKIND_TEXT:
			return g_remote_has_text ? CF_UNICODETEXT : 0;
		case JRDP_CLIPKIND_IMAGE:
			return g_remote_image_id;
		case JRDP_CLIPKIND_HTML:
			return g_remote_html_id;
		case JRDP_CLIPKIND_FILES:
			return g_remote_files_id;
		default:
			return 0;
	}
}

static void clip_push(ClipJob* job);

/* Reader thread. Takes ownership of data. */
static void clip_post(UINT8 type, UINT8 kind, UINT32 id, UINT8 ok, BYTE* data, size_t len)
{
	ClipJob* job = calloc(1, sizeof(*job));
	if (!job)
	{
		free(data);
		return;
	}
	job->type = type;
	job->kind = kind;
	job->id = id;
	job->ok = ok;
	job->data = data;
	job->len = len;
	clip_push(job);
}

/* Reader thread. Queues a filled-in job for the RDP thread, which frees it. */
static void clip_push(ClipJob* job)
{
	EnterCriticalSection(&g_clip_lock);
	if (g_clip_tail)
		g_clip_tail->next = job;
	else
		g_clip_head = job;
	g_clip_tail = job;
	LeaveCriticalSection(&g_clip_lock);
	SetEvent(g_clip_event);
}

static ClipJob* clip_pop(void)
{
	EnterCriticalSection(&g_clip_lock);
	ClipJob* job = g_clip_head;
	if (job)
	{
		g_clip_head = job->next;
		if (!g_clip_head)
			g_clip_tail = NULL;
	}
	LeaveCriticalSection(&g_clip_lock);
	return job;
}

static void send_clip_kinds(UINT8 type, UINT8 kinds)
{
	Buf b = { 0 };
	buf_u8(&b, kinds);
	send_msg(type, &b);
	free(b.data);
}

static void send_clip_data(UINT8 type, UINT8 kind, BOOL ok, const BYTE* data, size_t len)
{
	Buf b = { 0 };
	buf_u8(&b, kind);
	buf_u8(&b, ok ? 1 : 0);
	if (ok)
		buf_bytes(&b, data, len);
	send_msg(type, &b);
	free(b.data);
}

/* Announces what jterm offered (nothing, when local -> remote is off or the clipboard is empty). */
static void clip_send_format_list(void)
{
	if (!g_clip || !g_clip_ready)
		return;
	CLIPRDR_FORMAT formats[4] = { 0 };
	UINT32 n = 0;
	if (g_clip_flags & JRDP_CLIP_LOCAL_TO_REMOTE)
	{
		if (g_clip_local_kinds & JRDP_CLIPKIND_TEXT)
			formats[n++].formatId = CF_UNICODETEXT;
		/* CF_DIB only: Windows synthesises CF_DIBV5 (and CF_BITMAP) from it on the server side. */
		if (g_clip_local_kinds & JRDP_CLIPKIND_IMAGE)
			formats[n++].formatId = CF_DIB;
		if (g_clip_local_kinds & JRDP_CLIPKIND_HTML)
		{
			formats[n].formatId = JRDP_LOCAL_HTML_FORMAT_ID;
			formats[n++].formatName = (char*)CLIP_HTML_FORMAT_NAME; /* only read by FreeRDP */
		}
		if ((g_clip_local_kinds & JRDP_CLIPKIND_FILES) && clip_files_enabled())
		{
			formats[n].formatId = JRDP_LOCAL_FILES_FORMAT_ID;
			formats[n++].formatName = (char*)CLIP_FILES_FORMAT_NAME;
		}
	}
	CLIPRDR_FORMAT_LIST list = { 0 };
	list.common.msgType = CB_FORMAT_LIST;
	list.numFormats = n;
	list.formats = formats;
	const UINT rc = g_clip->ClientFormatList(g_clip, &list);
	if (rc != CHANNEL_RC_OK)
		fprintf(stderr, "jterm-rdp-helper: clipboard format list failed (%u)\n", (unsigned)rc);
}

static UINT clip_respond(BOOL ok, const BYTE* data, size_t len)
{
	CLIPRDR_FORMAT_DATA_RESPONSE r = { 0 };
	r.common.msgType = CB_FORMAT_DATA_RESPONSE;
	r.common.msgFlags = ok ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
	r.common.dataLen = ok ? (UINT32)len : 0;
	r.requestedFormatData = ok ? data : NULL;
	return g_clip->ClientFormatDataResponse(g_clip, &r);
}

static UINT j_clip_monitor_ready(CliprdrClientContext* ctx, const CLIPRDR_MONITOR_READY* ready)
{
	WINPR_UNUSED(ready);
	CLIPRDR_GENERAL_CAPABILITY_SET general = { 0 };
	general.capabilitySetType = CB_CAPSTYPE_GENERAL;
	general.capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN;
	general.version = CB_CAPS_VERSION_2;
	general.generalFlags = CB_USE_LONG_FORMAT_NAMES;
	/* Files: streamed by FileContents (never as paths), over 4 GiB too, and the server may lock
	 * the clipboard while it reads. FreeRDP drops whatever the server did not announce. */
	if (clip_files_enabled() || clip_files_to_local_enabled())
		general.generalFlags |= CB_STREAM_FILECLIP_ENABLED | CB_FILECLIP_NO_FILE_PATHS |
		                        CB_HUGE_FILE_SUPPORT_ENABLED | CB_CAN_LOCK_CLIPDATA;
	CLIPRDR_CAPABILITIES caps = { 0 };
	caps.common.msgType = CB_CLIP_CAPS;
	caps.cCapabilitiesSets = 1;
	caps.capabilitySets = (CLIPRDR_CAPABILITY_SET*)&general;
	const UINT rc = ctx->ClientCapabilities(ctx, &caps);
	if (rc != CHANNEL_RC_OK)
		return rc;
	{
		/* What both sides agreed on: FreeRDP sends no flag the server did not announce. */
		Buf b = { 0 };
		buf_u32(&b, general.generalFlags & g_server_clip_flags);
		send_msg(JRDP_CLIP_CAPS, &b);
		free(b.data);
	}
	g_clip_ready = TRUE;
	clip_send_format_list(); /* the first list is required, even an empty one */
	return CHANNEL_RC_OK;
}

static UINT j_clip_server_capabilities(CliprdrClientContext* ctx, const CLIPRDR_CAPABILITIES* caps)
{
	WINPR_UNUSED(ctx);
	g_server_clip_flags = 0;
	if (caps && caps->capabilitySets && caps->cCapabilitiesSets > 0)
	{
		const CLIPRDR_CAPABILITY_SET* set = caps->capabilitySets;
		if (set->capabilitySetType == CB_CAPSTYPE_GENERAL)
			g_server_clip_flags = ((const CLIPRDR_GENERAL_CAPABILITY_SET*)set)->generalFlags;
	}
	return CHANNEL_RC_OK;
}

static UINT j_clip_server_format_list(CliprdrClientContext* ctx, const CLIPRDR_FORMAT_LIST* list)
{
	CLIPRDR_FORMAT_LIST_RESPONSE ack = { 0 };
	ack.common.msgType = CB_FORMAT_LIST_RESPONSE;
	ack.common.msgFlags = CB_RESPONSE_OK;
	const UINT rc = ctx->ClientFormatListResponse(ctx, &ack);
	if (rc != CHANNEL_RC_OK || !(g_clip_flags & JRDP_CLIP_REMOTE_TO_LOCAL))
		return rc; /* acknowledged, and otherwise ignored */
	g_remote_has_text = FALSE;
	g_remote_html_id = 0;
	g_remote_files_id = 0;
	BOOL dib = FALSE;
	BOOL dibv5 = FALSE;
	for (UINT32 i = 0; i < list->numFormats; i++)
	{
		const CLIPRDR_FORMAT* f = &list->formats[i];
		if (f->formatId == CF_UNICODETEXT)
			g_remote_has_text = TRUE;
		else if (f->formatId == CF_DIB)
			dib = TRUE;
		else if (f->formatId == CF_DIBV5)
			dibv5 = TRUE;
		/* A registered format is known by its name; its number is whatever the server chose. */
		else if (f->formatId >= 0xC000 && f->formatName &&
		         strcmp(f->formatName, CLIP_HTML_FORMAT_NAME) == 0)
			g_remote_html_id = f->formatId;
		else if (f->formatId >= 0xC000 && f->formatName && clip_files_to_local_enabled() &&
		         strcmp(f->formatName, CLIP_FILES_FORMAT_NAME) == 0)
			g_remote_files_id = f->formatId;
	}
	/* CF_DIB when there is one (the plain header jterm decodes most simply); CF_DIBV5 serves a
	 * server that offers only that. */
	g_remote_image_id = dib ? CF_DIB : (dibv5 ? CF_DIBV5 : 0);
	send_clip_kinds(JRDP_CLIP_REMOTE_OFFER,
	                (UINT8)((g_remote_has_text ? JRDP_CLIPKIND_TEXT : 0) |
	                        (g_remote_image_id ? JRDP_CLIPKIND_IMAGE : 0) |
	                        (g_remote_html_id ? JRDP_CLIPKIND_HTML : 0) |
	                        (g_remote_files_id ? JRDP_CLIPKIND_FILES : 0)));
	return CHANNEL_RC_OK;
}

/* The server pastes. It may only ever get what jterm offered, once at a time. */
static UINT j_clip_server_format_data_request(CliprdrClientContext* ctx,
                                              const CLIPRDR_FORMAT_DATA_REQUEST* req)
{
	WINPR_UNUSED(ctx);
	UINT8 kind = 0;
	if (req->requestedFormatId == CF_UNICODETEXT)
		kind = JRDP_CLIPKIND_TEXT;
	else if (req->requestedFormatId == CF_DIB)
		kind = JRDP_CLIPKIND_IMAGE;
	else if (req->requestedFormatId == JRDP_LOCAL_HTML_FORMAT_ID)
		kind = JRDP_CLIPKIND_HTML;
	else if (req->requestedFormatId == JRDP_LOCAL_FILES_FORMAT_ID && clip_files_enabled())
		kind = JRDP_CLIPKIND_FILES;
	if (!(g_clip_flags & JRDP_CLIP_LOCAL_TO_REMOTE) || !kind || g_serve_kind ||
	    !(g_clip_local_kinds & kind))
		return clip_respond(FALSE, NULL, 0);
	g_serve_kind = kind;
	send_clip_kinds(JRDP_CLIP_REQUEST, kind);
	return CHANNEL_RC_OK;
}

/* The server's answer to a CLIP_FETCH. */
static UINT j_clip_server_format_data_response(CliprdrClientContext* ctx,
                                               const CLIPRDR_FORMAT_DATA_RESPONSE* r)
{
	WINPR_UNUSED(ctx);
	const UINT8 kind = g_fetch_kind;
	g_fetch_kind = 0;
	if (!kind)
		return CHANNEL_RC_OK; /* nothing was asked for */
	const BOOL ok = (r->common.msgFlags & CB_RESPONSE_OK) && r->requestedFormatData &&
	                r->common.dataLen > 0 && r->common.dataLen <= JRDP_CLIP_MAX_BYTES;
	send_clip_data(JRDP_CLIP_REMOTE_DATA, kind, ok, r->requestedFormatData, r->common.dataLen);
	return CHANNEL_RC_OK;
}

static UINT clip_file_respond(UINT32 streamId, BOOL ok, const BYTE* data, UINT32 len)
{
	CLIPRDR_FILE_CONTENTS_RESPONSE r = { 0 };
	r.common.msgType = CB_FILECONTENTS_RESPONSE;
	r.common.msgFlags = ok ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
	r.streamId = streamId;
	r.cbRequested = ok ? len : 0;
	r.requestedData = ok ? data : NULL;
	return g_clip->ClientFileContentsResponse(g_clip, &r);
}

/* The server reads a file jterm described. Forwarded as is; jterm decides whether it may. */
static UINT j_clip_server_file_contents_request(CliprdrClientContext* ctx,
                                                const CLIPRDR_FILE_CONTENTS_REQUEST* req)
{
	WINPR_UNUSED(ctx);
	const UINT32 kind = req->dwFlags & (FILECONTENTS_SIZE | FILECONTENTS_RANGE);
	int slot = -1;
	if (clip_files_enabled() && (kind == FILECONTENTS_SIZE || kind == FILECONTENTS_RANGE))
	{
		for (int i = 0; i < JRDP_FILE_PENDING_MAX; i++)
		{
			if (!g_file_pending[i].used)
			{
				slot = i;
				break;
			}
		}
	}
	if (slot < 0) /* off, malformed, or too many outstanding: refused, never queued without limit */
		return clip_file_respond(req->streamId, FALSE, NULL, 0);
	g_file_pending[slot].used = TRUE;
	g_file_pending[slot].streamId = req->streamId;
	g_file_pending[slot].flags = kind;
	g_file_pending[slot].cbRequested = req->cbRequested;

	Buf b = { 0 };
	buf_u32(&b, req->streamId);
	buf_u32(&b, req->listIndex);
	buf_u32(&b, kind);
	buf_u32(&b, req->nPositionLow);
	buf_u32(&b, req->nPositionHigh);
	buf_u32(&b, req->cbRequested);
	buf_u8(&b, req->haveClipDataId ? 1 : 0);
	buf_u32(&b, req->haveClipDataId ? req->clipDataId : 0);
	send_msg(JRDP_FILE_REQUEST, &b);
	free(b.data);
	return CHANNEL_RC_OK;
}

static void send_clip_lock(UINT8 type, UINT32 clipDataId)
{
	Buf b = { 0 };
	buf_u32(&b, clipDataId);
	send_msg(type, &b);
	free(b.data);
}

static UINT j_clip_server_lock(CliprdrClientContext* ctx, const CLIPRDR_LOCK_CLIPBOARD_DATA* lock)
{
	WINPR_UNUSED(ctx);
	if (clip_files_enabled())
		send_clip_lock(JRDP_CLIP_LOCK, lock->clipDataId);
	return CHANNEL_RC_OK;
}

static UINT j_clip_server_unlock(CliprdrClientContext* ctx, const CLIPRDR_UNLOCK_CLIPBOARD_DATA* unlock)
{
	WINPR_UNUSED(ctx);
	if (clip_files_enabled())
		send_clip_lock(JRDP_CLIP_UNLOCK, unlock->clipDataId);
	return CHANNEL_RC_OK;
}

/* jterm's answer to a FILE_REQUEST: only for one still pending, and never more than it asked. */
static void clip_file_answer(UINT32 streamId, BOOL ok, const BYTE* data, size_t len)
{
	for (int i = 0; i < JRDP_FILE_PENDING_MAX; i++)
	{
		PendingFile* p = &g_file_pending[i];
		if (!p->used || p->streamId != streamId)
			continue;
		p->used = FALSE;
		const BOOL fits = p->flags == FILECONTENTS_SIZE ? len == 8 : len <= p->cbRequested;
		if (g_clip)
			(void)clip_file_respond(streamId, ok && fits, data, (UINT32)len);
		return;
	}
}

static void send_file_remote_data(UINT32 streamId, BOOL ok, const BYTE* data, size_t len)
{
	Buf b = { 0 };
	buf_u32(&b, streamId);
	buf_u8(&b, ok ? 1 : 0);
	if (ok && len)
		buf_bytes(&b, data, len);
	send_msg(JRDP_FILE_REMOTE_DATA, &b);
	free(b.data);
}

/* The server's answer to one of jterm's FILE_FETCH requests: passed on, never more than asked. */
static UINT j_clip_server_file_contents_response(CliprdrClientContext* ctx,
                                                 const CLIPRDR_FILE_CONTENTS_RESPONSE* r)
{
	WINPR_UNUSED(ctx);
	for (int i = 0; i < JRDP_FETCH_PENDING_MAX; i++)
	{
		PendingFetch* p = &g_fetch_pending[i];
		if (!p->used || p->streamId != r->streamId)
			continue;
		p->used = FALSE;
		const BOOL ok = (r->common.msgFlags & CB_RESPONSE_OK) &&
		                (r->cbRequested == 0 || r->requestedData) &&
		                r->cbRequested <= p->cbRequested && r->cbRequested <= JRDP_CLIP_MAX_BYTES;
		send_file_remote_data(r->streamId, ok, r->requestedData, ok ? r->cbRequested : 0);
		return CHANNEL_RC_OK;
	}
	return CHANNEL_RC_OK; /* nothing of ours: dropped */
}

static void clip_start_fetch_file(const CLIPRDR_FILE_CONTENTS_REQUEST* req)
{
	int slot = -1;
	/* Without a lock the read names the server's current list, so it must hold files. With one it
	 * names the locked copy, which the server keeps after the user copies text over it; requiring
	 * files on the current clipboard then failed a running save the moment text was copied. */
	if (g_clip && g_clip_ready && clip_files_to_local_enabled() &&
	    (g_remote_files_id || req->haveClipDataId) && req->cbRequested <= JRDP_CLIP_MAX_BYTES)
	{
		for (int i = 0; i < JRDP_FETCH_PENDING_MAX; i++)
		{
			if (!g_fetch_pending[i].used)
			{
				slot = i;
				break;
			}
		}
	}
	if (slot >= 0)
	{
		g_fetch_pending[slot].used = TRUE;
		g_fetch_pending[slot].streamId = req->streamId;
		g_fetch_pending[slot].cbRequested = req->cbRequested;
		if (g_clip->ClientFileContentsRequest(g_clip, req) == CHANNEL_RC_OK)
			return;
		g_fetch_pending[slot].used = FALSE;
	}
	send_file_remote_data(req->streamId, FALSE, NULL, 0);
}

/* RDP thread: applies what the reader thread queued. */
static void clip_process_jobs(void)
{
	ClipJob* job;
	while ((job = clip_pop()) != NULL)
	{
		switch (job->type)
		{
			case JRDP_CLIP_OFFER:
				g_clip_local_kinds = job->kind;
				clip_send_format_list();
				break;
			case JRDP_CLIP_DATA:
				if (g_clip && g_serve_kind && job->kind == g_serve_kind)
				{
					g_serve_kind = 0;
					(void)clip_respond(job->ok && job->len <= JRDP_CLIP_MAX_BYTES, job->data,
					                   job->len);
				}
				break;
			case JRDP_FILE_DATA:
				clip_file_answer(job->id, job->ok, job->data, job->len);
				break;
			case JRDP_FILE_FETCH:
				clip_start_fetch_file(&job->fetch);
				break;
			case JRDP_CLIP_LOCK_REMOTE:
				if (g_clip && g_clip_ready && clip_files_to_local_enabled())
				{
					if (job->kind)
					{
						CLIPRDR_LOCK_CLIPBOARD_DATA l = { 0 };
						l.common.msgType = CB_LOCK_CLIPDATA;
						l.clipDataId = job->id;
						(void)g_clip->ClientLockClipboardData(g_clip, &l);
					}
					else
					{
						CLIPRDR_UNLOCK_CLIPBOARD_DATA u = { 0 };
						u.common.msgType = CB_UNLOCK_CLIPDATA;
						u.clipDataId = job->id;
						(void)g_clip->ClientUnlockClipboardData(g_clip, &u);
					}
				}
				break;
			case JRDP_CLIP_FETCH:
			{
				const UINT32 format = clip_remote_format_for(job->kind);
				BOOL started = FALSE;
				if (g_clip && g_clip_ready && (g_clip_flags & JRDP_CLIP_REMOTE_TO_LOCAL) && format &&
				    !g_fetch_kind)
				{
					CLIPRDR_FORMAT_DATA_REQUEST req = { 0 };
					req.common.msgType = CB_FORMAT_DATA_REQUEST;
					req.requestedFormatId = format;
					g_fetch_kind = job->kind;
					started = g_clip->ClientFormatDataRequest(g_clip, &req) == CHANNEL_RC_OK;
					if (!started)
						g_fetch_kind = 0;
				}
				if (!started)
					send_clip_data(JRDP_CLIP_REMOTE_DATA, job->kind, FALSE, NULL, 0);
				break;
			}
			default:
				break;
		}
		free(job->data);
		free(job);
	}
}


/* ------------------------------------------------------------------------------------------ */
/* Drive redirection (rdpdr)                                                                   */
/*
 * The helper never touches the local file system for a shared folder and never learns its path.
 * FreeRDP's own drive device (channels/drive) is not loaded: its confinement is lexical only
 * (symbolic links escape the share) and it has had several advisories. Instead an add-in provider,
 * installed over the client's static one, answers "drive" with this forwarding device, and answers
 * every other device service (printer, smart card, serial, parallel) with nothing at all.
 *
 * The device forwards each I/O request to jterm as DRIVE_IRP and keeps the IRP pending; jterm
 * decides everything and answers with DRIVE_IRP_DONE, whose output is appended to the IRP before it
 * is completed. FreeRDP's own drive completes IRPs from its worker thread, so completing from the
 * reader thread is supported. Completion happens under g_drive_lock, which the device's Free also
 * takes, so a late answer can never complete an IRP of a channel that is being torn down.
 */
#define JRDP_DRIVE_PENDING_MAX 256

typedef struct
{
	DEVICE device;
	UINT32 shareId;
} JDrive;

typedef struct
{
	UINT32 completionId;
	IRP* irp;
} PendingIrp;

typedef struct
{
	UINT32 id;
	char name[32]; /* sanitised: [A-Za-z0-9_-], NUL-terminated */
} DriveDecl;

static CRITICAL_SECTION g_drive_lock;
static PendingIrp g_drive_pending[JRDP_DRIVE_PENDING_MAX];
static DriveDecl g_drives[JRDP_MAX_DRIVES];
static UINT32 g_drive_count = 0; /* written once by apply_connect, before the session starts */
static FREERDP_LOAD_CHANNEL_ADDIN_ENTRY_FN g_prev_addin_provider = NULL;

/* The fixed fields a failed reply must still carry for the server to parse it (as jterm writes). */
static size_t drive_failure_body_len(const IRP* irp)
{
	switch (irp->MajorFunction)
	{
		case IRP_MJ_CREATE:
		case IRP_MJ_WRITE:
			return 5;
		case IRP_MJ_DIRECTORY_CONTROL:
			return irp->MinorFunction == IRP_MN_QUERY_DIRECTORY ? 5 : 4;
		case IRP_MJ_CLOSE:
		case IRP_MJ_READ:
		case IRP_MJ_QUERY_INFORMATION:
		case IRP_MJ_SET_INFORMATION:
		case IRP_MJ_QUERY_VOLUME_INFORMATION:
		case IRP_MJ_DEVICE_CONTROL:
		case IRP_MJ_LOCK_CONTROL:
			return 4;
		default:
			return 0;
	}
}

static UINT drive_fail_now(IRP* irp, NTSTATUS status)
{
	const size_t n = drive_failure_body_len(irp);
	if (!Stream_EnsureRemainingCapacity(irp->output, n))
	{
		irp->Discard(irp);
		return CHANNEL_RC_NO_MEMORY;
	}
	Stream_Zero(irp->output, n);
	irp->IoStatus = status;
	return irp->Complete(irp);
}

/* rdpdr's thread. Forwards the request; the IRP stays pending until jterm answers. */
static UINT j_drive_irp_request(DEVICE* device, IRP* irp)
{
	JDrive* drive = (JDrive*)device;
	if (!drive || !irp)
		return ERROR_INVALID_PARAMETER;

	EnterCriticalSection(&g_drive_lock);
	int slot = -1;
	BOOL duplicate = FALSE;
	for (int i = 0; i < JRDP_DRIVE_PENDING_MAX; i++)
	{
		if (!g_drive_pending[i].irp)
		{
			if (slot < 0)
				slot = i;
		}
		else if (g_drive_pending[i].completionId == irp->CompletionId)
			duplicate = TRUE;
	}
	if (slot < 0 || duplicate)
	{
		LeaveCriticalSection(&g_drive_lock);
		/* Never queued without limit; a reused completion id is the server's protocol error. */
		return drive_fail_now(irp, duplicate ? STATUS_INVALID_PARAMETER : STATUS_INSUFFICIENT_RESOURCES);
	}
	g_drive_pending[slot].completionId = irp->CompletionId;
	g_drive_pending[slot].irp = irp;
	LeaveCriticalSection(&g_drive_lock);

	const size_t avail = Stream_GetRemainingLength(irp->input);
	Buf b = { 0 };
	buf_u32(&b, drive->shareId);
	buf_u32(&b, irp->FileId);
	buf_u32(&b, irp->CompletionId);
	buf_u32(&b, irp->MajorFunction);
	buf_u32(&b, irp->MinorFunction);
	if (avail)
		buf_bytes(&b, Stream_ConstPointer(irp->input), avail);
	send_msg(JRDP_DRIVE_IRP, &b);
	free(b.data);
	return CHANNEL_RC_OK;
}

/* Reader thread: jterm's answer. An unknown completion id (late, or after disconnect) is dropped. */
static void drive_complete(UINT32 completionId, UINT32 ioStatus, const BYTE* data, size_t len)
{
	EnterCriticalSection(&g_drive_lock);
	for (int i = 0; i < JRDP_DRIVE_PENDING_MAX; i++)
	{
		IRP* irp = g_drive_pending[i].irp;
		if (!irp || g_drive_pending[i].completionId != completionId)
			continue;
		g_drive_pending[i].irp = NULL;
		if (Stream_EnsureRemainingCapacity(irp->output, len))
		{
			if (len)
				Stream_Write(irp->output, data, len);
			irp->IoStatus = (NTSTATUS)ioStatus;
			(void)irp->Complete(irp);
		}
		else
			irp->Discard(irp);
		break;
	}
	LeaveCriticalSection(&g_drive_lock);
}

static UINT j_drive_free(DEVICE* device)
{
	JDrive* drive = (JDrive*)device;
	if (!drive)
		return ERROR_INVALID_PARAMETER;
	EnterCriticalSection(&g_drive_lock);
	for (int i = 0; i < JRDP_DRIVE_PENDING_MAX; i++)
	{
		IRP* irp = g_drive_pending[i].irp;
		if (irp && irp->device == device)
		{
			g_drive_pending[i].irp = NULL;
			irp->Discard(irp);
		}
	}
	LeaveCriticalSection(&g_drive_lock);
	Stream_Free(drive->device.data, TRUE);
	free(drive);
	return CHANNEL_RC_OK;
}

/* DeviceServiceEntry for "drive": one forwarding device per shared folder in CONNECT. */
static UINT VCAPITYPE j_drive_entry(PDEVICE_SERVICE_ENTRY_POINTS ep)
{
	if (!ep || !ep->device || ep->device->Type != RDPDR_DTYP_FILESYSTEM)
		return ERROR_INVALID_PARAMETER;
	const RDPDR_DRIVE* decl = (const RDPDR_DRIVE*)ep->device;
	/* Path carries the share's index into g_drives, never a path. */
	char* end = NULL;
	const unsigned long index = decl->Path ? strtoul(decl->Path, &end, 10) : ULONG_MAX;
	if (!decl->Path || !end || *end || index >= g_drive_count)
		return ERROR_INVALID_PARAMETER;

	const DriveDecl* d = &g_drives[index];
	const size_t len = strlen(d->name);
	JDrive* drive = calloc(1, sizeof(*drive));
	if (!drive)
		return CHANNEL_RC_NO_MEMORY;
	drive->shareId = d->id;
	drive->device.type = RDPDR_DTYP_FILESYSTEM;
	drive->device.IRPRequest = j_drive_irp_request;
	drive->device.Free = j_drive_free;
	/* The announcement carries the first 8 bytes of name as the DOS name and data (the full name,
	 * NUL-terminated) as what Windows shows: "<name> on <client>", \\tsclient\<name>. */
	drive->device.data = Stream_New(NULL, len + 1);
	if (!drive->device.data)
	{
		free(drive);
		return CHANNEL_RC_NO_MEMORY;
	}
	Stream_Write(drive->device.data, d->name, len);
	Stream_Write_UINT8(drive->device.data, 0);
	drive->device.name = Stream_BufferAs(drive->device.data, char);
	const UINT rc = ep->RegisterDevice(ep->devman, &drive->device);
	if (rc != CHANNEL_RC_OK)
	{
		Stream_Free(drive->device.data, TRUE);
		free(drive);
	}
	return rc;
}

/* ------------------------------------------------------------------------------------------ */
/* Printer (protocol v13)                                                                      */
/*                                                                                             */
/* One printer, announced with the name and driver jterm chose; its print jobs are forwarded    */
/* to jterm exactly like a share's requests (DRIVE_IRP, id JRDP_PRINTER_DEVICE_ID) and never     */
/* reach a local print system from here. FreeRDP's own printer channel is never loaded: it      */
/* writes the server's printer cache data to files and hands jobs to CUPS or WritePrinter as    */
/* they arrive (printer design).                                                                */

static UINT8 g_printer_flags = 0; /* written once by apply_connect, before rdpdr starts */
static char g_printer_name[JRDP_PRINTER_NAME_MAX * 4 + 1];
static char g_printer_driver[JRDP_PRINTER_NAME_MAX + 1];

typedef struct
{
	JDrive base; /* first: j_drive_irp_request and j_drive_free see a JDrive */
	char dosName[8];
} JPrinter;

/* rdpdr's thread. Only what a print job is made of goes to jterm; anything else (the server never
 * sends it to a printer) is refused here. */
static UINT j_printer_irp_request(DEVICE* device, IRP* irp)
{
	if (!device || !irp)
		return ERROR_INVALID_PARAMETER;
	switch (irp->MajorFunction)
	{
		case IRP_MJ_CREATE:
		case IRP_MJ_WRITE:
		case IRP_MJ_CLOSE:
		case IRP_MJ_DEVICE_CONTROL:
			return j_drive_irp_request(device, irp);
		default:
			return drive_fail_now(irp, STATUS_NOT_SUPPORTED);
	}
}

/* The server's printer cache data (PAKID_PRN_CACHE_DATA: the printer's saved configuration, which
 * FreeRDP turns into files) and its XPS notice. Accepted and dropped: nothing is stored. */
static UINT j_printer_component(DEVICE* device, UINT16 component, UINT16 packetId, wStream* s)
{
	WINPR_UNUSED(device);
	WINPR_UNUSED(s);
	static LONG logged = 0;
	if (InterlockedCompareExchange(&logged, 1, 0) == 0)
		(void)fprintf(stderr,
		              "jterm-rdp-helper: printer: ignoring the server's printer data (component 0x%04x, "
		              "packet 0x%04x); nothing is stored\n",
		              component, packetId);
	return CHANNEL_RC_OK;
}

static UINT VCAPITYPE j_printer_entry(PDEVICE_SERVICE_ENTRY_POINTS ep)
{
	if (!ep || !ep->device || ep->device->Type != RDPDR_DTYP_PRINT ||
	    !(g_printer_flags & JRDP_PRINTER_ENABLED))
		return ERROR_INVALID_PARAMETER;

	size_t dlen = 0;
	size_t nlen = 0;
	WCHAR* driver = ConvertUtf8ToWCharAlloc(g_printer_driver, &dlen);
	WCHAR* name = ConvertUtf8ToWCharAlloc(g_printer_name, &nlen);
	JPrinter* p = calloc(1, sizeof(*p));
	const UINT32 driverBytes = (UINT32)((dlen + 1) * sizeof(WCHAR));
	const UINT32 nameBytes = (UINT32)((nlen + 1) * sizeof(WCHAR));
	if (!driver || !name || !p || !(p->base.device.data = Stream_New(NULL, 24 + driverBytes + nameBytes)))
	{
		free(driver);
		free(name);
		free(p);
		return CHANNEL_RC_NO_MEMORY;
	}
	/* MS-RDPEPC 2.2.2.1, as FreeRDP's printer writes it, but with no PnP name and no cached
	 * configuration: the server learns the name and driver jterm chose and nothing else. */
	wStream* d = p->base.device.data;
	Stream_Write_UINT32(d, (g_printer_flags & JRDP_PRINTER_DEFAULT) ? RDPDR_PRINTER_ANNOUNCE_FLAG_DEFAULTPRINTER : 0);
	Stream_Write_UINT32(d, 0);           /* CodePage */
	Stream_Write_UINT32(d, 0);           /* PnPNameLen */
	Stream_Write_UINT32(d, driverBytes); /* DriverNameLen */
	Stream_Write_UINT32(d, nameBytes);   /* PrintNameLen */
	Stream_Write_UINT32(d, 0);           /* CachedFieldsLen */
	Stream_Write(d, driver, driverBytes);
	Stream_Write(d, name, nameBytes);
	free(driver);
	free(name);
	(void)snprintf(p->dosName, sizeof(p->dosName), "PRN1");
	p->base.shareId = JRDP_PRINTER_DEVICE_ID;
	p->base.device.type = RDPDR_DTYP_PRINT;
	p->base.device.name = p->dosName;
	p->base.device.IRPRequest = j_printer_irp_request;
	p->base.device.CustomComponentRequest = j_printer_component;
	p->base.device.Free = j_drive_free;
	const UINT rc = ep->RegisterDevice(ep->devman, &p->base.device);
	if (rc != CHANNEL_RC_OK)
	{
		Stream_Free(p->base.device.data, TRUE);
		free(p);
		return rc;
	}
	(void)fprintf(stderr, "jterm-rdp-helper: printer \"%s\" announced, driver \"%s\"%s\n", g_printer_name,
	              g_printer_driver, (g_printer_flags & JRDP_PRINTER_DEFAULT) ? ", as the default" : "");
	return CHANNEL_RC_OK;
}

/* ------------------------------------------------------------------------------------------ */
/* Smart card (protocol v13)                                                                   */
/*                                                                                             */
/* One smart-card device whose calls (IRP_MJ_DEVICE_CONTROL: an IOCTL code and an NDR-encoded  */
/* MS-RDPESC call) go to jterm unparsed, like a share's requests (DRIVE_IRP, id                */
/* JRDP_SMARTCARD_DEVICE_ID). jterm decodes them, applies the reader filter and the consent,   */
/* and talks to PC/SC. FreeRDP's own smart-card channel is never loaded: it parses the calls   */
/* in C here and would need the pcscd socket or winscard inside the sandbox (S6).              */

static UINT8 g_smartcard_flags = 0; /* written once by apply_connect, before rdpdr starts */

typedef struct
{
	JDrive base; /* first: j_drive_irp_request and j_drive_free see a JDrive */
	char dosName[8];
} JSmartcard;

/* rdpdr's thread. A smart card only ever gets device-control requests; anything else is refused. */
static UINT j_smartcard_irp_request(DEVICE* device, IRP* irp)
{
	if (!device || !irp)
		return ERROR_INVALID_PARAMETER;
	if (irp->MajorFunction == IRP_MJ_DEVICE_CONTROL)
		return j_drive_irp_request(device, irp);
	return drive_fail_now(irp, STATUS_NOT_SUPPORTED);
}

static UINT VCAPITYPE j_smartcard_entry(PDEVICE_SERVICE_ENTRY_POINTS ep)
{
	if (!ep || !ep->device || ep->device->Type != RDPDR_DTYP_SMARTCARD ||
	    !(g_smartcard_flags & JRDP_SMARTCARD_ENABLED))
		return ERROR_INVALID_PARAMETER;
	JSmartcard* c = calloc(1, sizeof(*c));
	if (!c)
		return CHANNEL_RC_NO_MEMORY;
	(void)snprintf(c->dosName, sizeof(c->dosName), "SCARD");
	c->base.shareId = JRDP_SMARTCARD_DEVICE_ID;
	c->base.device.type = RDPDR_DTYP_SMARTCARD;
	c->base.device.name = c->dosName;
	c->base.device.IRPRequest = j_smartcard_irp_request;
	c->base.device.Free = j_drive_free; /* frees device.data (NULL: nothing to announce) and c */
	const UINT rc = ep->RegisterDevice(ep->devman, &c->base.device);
	if (rc != CHANNEL_RC_OK)
	{
		free(c);
		return rc;
	}
	(void)fprintf(stderr, "jterm-rdp-helper: smart-card device announced\n");
	return CHANNEL_RC_OK;
}

/* Declares the smart-card device when CONNECT enabled it. */
static BOOL apply_smartcard(rdpSettings* s, UINT8 flags)
{
	if (flags & ~JRDP_SMARTCARD_ENABLED)
		return FALSE; /* an unknown bit: a newer jterm, refuse rather than guess */
	g_smartcard_flags = flags;
	if (!(flags & JRDP_SMARTCARD_ENABLED))
		return TRUE;
	RDPDR_DEVICE* device = freerdp_device_new(RDPDR_DTYP_SMARTCARD, 0, NULL);
	if (!device)
		return FALSE;
	if (!freerdp_device_collection_add(s, device))
	{
		freerdp_device_free(device);
		return FALSE;
	}
	/* Our device is in the collection, so FreeRDP adds none of its own for this flag. */
	return freerdp_settings_set_bool(s, FreeRDP_RedirectSmartCards, TRUE);
}

/* Accepts a printer or driver name from CONNECT: 1..JRDP_PRINTER_NAME_MAX characters, no control
 * characters; the driver name printable ASCII only. jterm sends fixed values, so anything else is
 * a protocol error. */
static BOOL printer_name_ok(const char* s, BOOL asciiOnly)
{
	if (!s || !*s)
		return FALSE;
	size_t chars = 0;
	for (const unsigned char* c = (const unsigned char*)s; *c; c++)
	{
		if (*c < 0x20 || *c == 0x7F || (asciiOnly && *c > 0x7E))
			return FALSE;
		if ((*c & 0xC0) != 0x80)
			chars++;
	}
	return chars <= JRDP_PRINTER_NAME_MAX;
}

/* Reads CONNECT's printer part (protocol v13) and declares the printer device when enabled. */
static BOOL apply_printer(rdpSettings* s, Msg* m)
{
	const UINT8 flags = get_u8(m);
	char* name = get_str(m);
	char* driver = get_str(m);
	const UINT8 smartcard = get_u8(m);
	BOOL ok = name && driver;
	if (ok && (flags & JRDP_PRINTER_ENABLED))
	{
		ok = printer_name_ok(name, FALSE) && printer_name_ok(driver, TRUE) &&
		     strlen(name) < sizeof(g_printer_name) && strlen(driver) < sizeof(g_printer_driver);
		if (ok)
		{
			/* A backslash would read as a path on the server; FreeRDP replaces it the same way. */
			for (char* c = name; *c; c++)
				if (*c == '\\')
					*c = '_';
			memcpy(g_printer_name, name, strlen(name) + 1);       /* lengths checked above */
			memcpy(g_printer_driver, driver, strlen(driver) + 1);
			g_printer_flags = flags;
		}
	}
	free(name);
	free(driver);
	if (ok && !apply_smartcard(s, smartcard))
		ok = FALSE;
	if (!ok)
		return FALSE;
	if (!(g_printer_flags & JRDP_PRINTER_ENABLED))
		return TRUE;
	const char* const args[] = { g_printer_name };
	RDPDR_DEVICE* device = freerdp_device_new(RDPDR_DTYP_PRINT, ARRAYSIZE(args), args);
	if (!device)
		return FALSE;
	if (!freerdp_device_collection_add(s, device))
	{
		freerdp_device_free(device);
		return FALSE;
	}
	/* Only the device above: never FreeRDP's "all local printers". */
	return freerdp_settings_set_bool(s, FreeRDP_RedirectPrinters, FALSE);
}

/* Installed over the client's static provider: our drive, printer and smart-card devices, no other
 * device service, and for sound our forwarding device instead of any backend (audio.c). */
static PVIRTUALCHANNELENTRY j_addin_provider(LPCSTR pszName, LPCSTR pszSubsystem, LPCSTR pszType,
                                             DWORD dwFlags)
{
	PVIRTUALCHANNELENTRY sound = NULL;
	if (audio_addin_entry(pszName, pszSubsystem, &sound))
		return sound; /* jterm's sound device, or nothing: never one of FreeRDP's backends */
	if (pszType && strcmp(pszType, "DeviceServiceEntry") == 0)
	{
		if (pszName && strcmp(pszName, "drive") == 0 && g_drive_count > 0)
			return WINPR_FUNC_PTR_CAST(&j_drive_entry, PVIRTUALCHANNELENTRY);
		if (pszName && strcmp(pszName, "printer") == 0 && (g_printer_flags & JRDP_PRINTER_ENABLED))
			return WINPR_FUNC_PTR_CAST(&j_printer_entry, PVIRTUALCHANNELENTRY);
		if (pszName && strcmp(pszName, "smartcard") == 0 && (g_smartcard_flags & JRDP_SMARTCARD_ENABLED))
			return WINPR_FUNC_PTR_CAST(&j_smartcard_entry, PVIRTUALCHANNELENTRY);
		return NULL;
	}
	return g_prev_addin_provider ? g_prev_addin_provider(pszName, pszSubsystem, pszType, dwFlags)
	                             : NULL;
}

/* Keeps [A-Za-z0-9_-] of a name jterm already sanitised; anything else becomes '_'. */
static void drive_sanitise_name(const char* in, char out[32])
{
	size_t n = 0;
	for (; in && in[n] && n < 31; n++)
	{
		const char c = in[n];
		const BOOL ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
		                c == '_' || c == '-';
		out[n] = ok ? c : '_';
	}
	if (n == 0)
		out[n++] = '_';
	out[n] = '\0';
}

/* Reads CONNECT's drive list (protocol v7) and declares one device per share. */
static BOOL apply_drives(rdpSettings* s, Msg* m)
{
	const UINT8 count = get_u8(m);
	if (count > JRDP_MAX_DRIVES)
		return FALSE;
	for (UINT8 i = 0; i < count; i++)
	{
		const UINT32 id = get_u32(m);
		char* name = get_str(m);
		if (!name)
			return FALSE;
		g_drives[i].id = id;
		drive_sanitise_name(name, g_drives[i].name);
		free(name);
	}
	g_drive_count = count;
	if (count == 0)
		return TRUE; /* rdpdr stays off: the server sees no device channel at all */

	for (UINT32 i = 0; i < g_drive_count; i++)
	{
		char index[16];
		(void)snprintf(index, sizeof(index), "%" PRIu32, i);
		const char* const args[] = { g_drives[i].name, index };
		RDPDR_DEVICE* device = freerdp_device_new(RDPDR_DTYP_FILESYSTEM, ARRAYSIZE(args), args);
		if (!device)
			return FALSE;
		if (!freerdp_device_collection_add(s, device))
		{
			freerdp_device_free(device);
			return FALSE;
		}
	}
	/* Only the devices above: never FreeRDP's "all drives" or "home drive" shortcuts. */
	return freerdp_settings_set_bool(s, FreeRDP_RedirectDrives, FALSE) &&
	       freerdp_settings_set_bool(s, FreeRDP_RedirectHomeDrive, FALSE) &&
	       freerdp_settings_set_bool(s, FreeRDP_DeviceRedirection, TRUE);
}

/* ------------------------------------------------------------------------------------------ */
/* Connection lifecycle                                                                        */

/* Sends the wanted monitor layout. RDP thread only. */
static void send_monitor_layout(void)
{
	if (!g_disp || !InterlockedCompareExchange(&g_disp_ready, 0, 0))
		return;
	UINT32 w = (UINT32)InterlockedCompareExchange(&g_want_w, 0, 0);
	UINT32 h = (UINT32)InterlockedCompareExchange(&g_want_h, 0, 0);
	UINT32 scale = (UINT32)InterlockedCompareExchange(&g_want_scale, 0, 0);
	if (w < DISPLAY_CONTROL_MIN_MONITOR_WIDTH || h < DISPLAY_CONTROL_MIN_MONITOR_HEIGHT)
		return;
	w = MIN(w, DISPLAY_CONTROL_MAX_MONITOR_WIDTH) & ~1u; /* MS-RDPEDISP: width must be even */
	h = MIN(h, DISPLAY_CONTROL_MAX_MONITOR_HEIGHT);
	if (scale < 100 || scale > 500)
		scale = 100;

	DISPLAY_CONTROL_MONITOR_LAYOUT layout = { 0 };
	layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
	layout.Width = w;
	layout.Height = h;
	/* Physical size consistent with the scale (96 dpi at 100%), so servers that derive scale from
	 * DPI agree with the explicit factor. */
	layout.PhysicalWidth = MAX(DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_WIDTH,
	                           (UINT32)(w * 25.4 * 100.0 / (96.0 * scale)));
	layout.PhysicalHeight = MAX(DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_HEIGHT,
	                            (UINT32)(h * 25.4 * 100.0 / (96.0 * scale)));
	layout.Orientation = ORIENTATION_LANDSCAPE;
	layout.DesktopScaleFactor = scale;
	layout.DeviceScaleFactor = scale >= 180 ? 180 : (scale >= 140 ? 140 : 100);
	const UINT rc = g_disp->SendMonitorLayout(g_disp, 1, &layout);
	fprintf(stderr, "jterm-rdp-helper: monitor layout %ux%u @ %u%% -> %s\n", w, h, scale,
	        rc == CHANNEL_RC_OK ? "sent" : "FAILED");
}

static UINT j_disp_caps(DispClientContext* disp, UINT32 maxMonitors, UINT32 areaA, UINT32 areaB)
{
	WINPR_UNUSED(disp);
	WINPR_UNUSED(maxMonitors);
	WINPR_UNUSED(areaA);
	WINPR_UNUSED(areaB);
	InterlockedExchange(&g_disp_ready, 1);
	send_monitor_layout(); /* the server is ready for a layout: apply the initial size + scale */
	return CHANNEL_RC_OK;
}

/* Counts the server's graphics commands by codec for the statistics, then lets FreeRDP's own
 * handler (gdi) decode them as before. */
static pcRdpgfxSurfaceCommand g_gfx_surface_command = NULL;

static UINT j_gfx_surface_command(RdpgfxClientContext* gfx, const RDPGFX_SURFACE_COMMAND* cmd)
{
	switch (cmd->codecId)
	{
		case RDPGFX_CODECID_AVC420:
			InterlockedIncrement(&g_stat_h264);
			break;
		case RDPGFX_CODECID_AVC444:
		case RDPGFX_CODECID_AVC444v2:
			InterlockedIncrement(&g_stat_avc444);
			break;
		case RDPGFX_CODECID_CAPROGRESSIVE:
		case RDPGFX_CODECID_CAPROGRESSIVE_V2:
			InterlockedIncrement(&g_stat_progressive);
			break;
		default:
			InterlockedIncrement(&g_stat_other);
			break;
	}
	return g_gfx_surface_command ? g_gfx_surface_command(gfx, cmd) : CHANNEL_RC_OK;
}

static void j_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
	if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		g_disp = (DispClientContext*)e->pInterface;
		g_disp->custom = context;
		g_disp->DisplayControlCaps = j_disp_caps;
		return;
	}
	if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		g_clip = (CliprdrClientContext*)e->pInterface;
		g_clip->custom = context;
		g_clip->MonitorReady = j_clip_monitor_ready;
		g_clip->ServerFormatList = j_clip_server_format_list;
		g_clip->ServerFormatDataRequest = j_clip_server_format_data_request;
		g_clip->ServerFormatDataResponse = j_clip_server_format_data_response;
		g_clip->ServerFileContentsRequest = j_clip_server_file_contents_request;
		g_clip->ServerLockClipboardData = j_clip_server_lock;
		g_clip->ServerUnlockClipboardData = j_clip_server_unlock;
		g_clip->ServerFileContentsResponse = j_clip_server_file_contents_response;
		g_clip->ServerCapabilities = j_clip_server_capabilities;
		return;
	}
	freerdp_client_OnChannelConnectedEventHandler(context, e);
	if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0)
	{
		/* After FreeRDP installed its own handler: wrap it. */
		RdpgfxClientContext* gfx = (RdpgfxClientContext*)e->pInterface;
		if (gfx && gfx->SurfaceCommand && gfx->SurfaceCommand != j_gfx_surface_command)
		{
			g_gfx_surface_command = gfx->SurfaceCommand;
			gfx->SurfaceCommand = j_gfx_surface_command;
		}
	}
}

static void j_channel_disconnected(void* context, const ChannelDisconnectedEventArgs* e)
{
	if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		InterlockedExchange(&g_disp_ready, 0);
		g_disp = NULL;
		return;
	}
	if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
	{
		g_clip = NULL;
		g_clip_ready = FALSE;
		g_fetch_kind = 0;
		g_serve_kind = 0;
		memset(g_file_pending, 0, sizeof(g_file_pending)); /* answers still coming are dropped */
		memset(g_fetch_pending, 0, sizeof(g_fetch_pending));
		g_remote_files_id = 0;
		return;
	}
	freerdp_client_OnChannelDisconnectedEventHandler(context, e);
}

static BOOL j_pre_connect(freerdp* instance)
{
	rdpContext* context = instance->context;
	if (PubSub_SubscribeChannelConnected(context->pubSub, j_channel_connected) < 0)
		return FALSE;
	if (PubSub_SubscribeChannelDisconnected(context->pubSub, j_channel_disconnected) < 0)
		return FALSE;
	return TRUE;
}

static BOOL j_post_connect(freerdp* instance)
{
	rdpContext* context = instance->context;
	if (!gdi_init(instance, PIXEL_FORMAT_BGRX32))
		return FALSE;

	rdpPointer pointer = { 0 };
	pointer.size = sizeof(jrdpPointer);
	pointer.New = jp_new;
	pointer.Free = jp_free;
	pointer.Set = jp_set;
	pointer.SetNull = jp_set_null;
	pointer.SetDefault = jp_set_default;
	pointer.SetPosition = jp_set_position;
	graphics_register_pointer(context->graphics, &pointer);

	context->update->BeginPaint = j_begin_paint;
	context->update->EndPaint = j_end_paint;
	context->update->DesktopResize = j_desktop_resize;
	return TRUE;
}

static void j_post_disconnect(freerdp* instance)
{
	rdpContext* context = instance->context;
	PubSub_UnsubscribeChannelConnected(context->pubSub, j_channel_connected);
	PubSub_UnsubscribeChannelDisconnected(context->pubSub, j_channel_disconnected);
	gdi_free(instance);
}

static BOOL j_client_new(freerdp* instance, rdpContext* context)
{
	WINPR_UNUSED(context);
	instance->PreConnect = j_pre_connect;
	instance->PostConnect = j_post_connect;
	instance->PostDisconnect = j_post_disconnect;
	instance->VerifyCertificateEx = j_verify_cert;
	instance->VerifyChangedCertificateEx = j_verify_changed_cert;
	instance->AuthenticateEx = j_authenticate;
	return TRUE;
}

static void j_client_free(freerdp* instance, rdpContext* context)
{
	WINPR_UNUSED(instance);
	WINPR_UNUSED(context);
}

static int j_client_start(rdpContext* context)
{
	WINPR_UNUSED(context);
	return 0;
}

static int j_client_stop(rdpContext* context)
{
	WINPR_UNUSED(context);
	return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* stdin reader thread: input events, certificate replies, shutdown                           */

/* A Windows virtual key to the RDP scancode (KBDEXT set for an extended key). WinPR keeps two
 * tables and searches the extended one only when the code passed in carries KBDEXT, so a plain
 * lookup returns 0 for Delete, Insert, Home/End, Page Up/Down, the arrows and both Windows keys,
 * which were then silently dropped (Ctrl+Alt+Del arrived as Ctrl+Alt). The extended table is
 * tried when the plain one has no entry; FORCE_EXTENDED (numpad Enter) asks for it outright, and
 * FORCE_NOT_EXTENDED (numpad navigation with NumLock off) takes the extended entry's scancode
 * without the flag, which is the numpad key's own. */
static DWORD vk_to_scancode(UINT16 vk, UINT8 flags)
{
	DWORD sc = GetVirtualScanCodeFromVirtualKeyCode(vk, WINPR_KBD_TYPE_IBM_ENHANCED);
	if ((sc & 0xFF) == 0 || (flags & JRDP_KEY_FORCE_EXTENDED))
	{
		const DWORD ext = GetVirtualScanCodeFromVirtualKeyCode(vk | KBDEXT, WINPR_KBD_TYPE_IBM_ENHANCED);
		if ((ext & 0xFF) != 0)
			sc = ext;
	}
	if (flags & JRDP_KEY_FORCE_EXTENDED)
		sc |= KBDEXT;
	if (flags & JRDP_KEY_FORCE_NOT_EXTENDED)
		sc &= ~KBDEXT;
	return sc;
}

static void handle_input(Msg* m)
{
	EnterCriticalSection(&g_input_lock);
	if (g_connected && g_context)
	{
		rdpInput* input = g_context->input;
		switch (m->type)
		{
			case JRDP_KEY:
			{
				const UINT8 down = get_u8(m);
				const UINT16 vk = get_u16(m);
				const UINT8 flags = get_u8(m);
				const DWORD sc = vk_to_scancode(vk, flags);
				if ((sc & 0xFF) != 0)
					(void)freerdp_input_send_keyboard_event_ex(input, down != 0,
					                                           (flags & JRDP_KEY_REPEAT) != 0, sc);
				break;
			}
			case JRDP_UNICODE:
			{
				const UINT8 down = get_u8(m);
				const UINT16 unit = get_u16(m);
				(void)freerdp_input_send_unicode_keyboard_event(input, down ? 0 : KBD_FLAGS_RELEASE,
				                                                unit);
				break;
			}
			case JRDP_MOUSE:
			{
				const UINT16 flags = get_u16(m);
				const UINT16 x = get_u16(m);
				const UINT16 y = get_u16(m);
				(void)freerdp_input_send_mouse_event(input, flags, x, y);
				break;
			}
			default:
				break;
		}
	}
	LeaveCriticalSection(&g_input_lock);
}

static DWORD WINAPI reader_thread(LPVOID arg)
{
	WINPR_UNUSED(arg);
	for (;;)
	{
		Msg m = { 0 };
		if (!read_msg(&m))
			break; /* EOF: jterm is gone or closed us */
		if (m.type == JRDP_BYE)
		{
			msg_free(&m);
			break;
		}
		if (m.type == JRDP_CERT_REPLY)
		{
			InterlockedExchange(&g_cert_decision, get_u8(&m) ? 1 : 0);
			SetEvent(g_cert_event);
		}
		else if (m.type == JRDP_RESOLVE_REPLY)
		{
			EnterCriticalSection(&g_resolve_lock);
			const UINT8 count = get_u8(&m);
			g_resolve_count = 0;
			for (UINT8 i = 0; i < count && g_resolve_count < NETROUTE_MAX_ADDRS; i++)
			{
				char* addr = get_str(&m);
				/* Numeric addresses only; netroute checks again before using one. */
				if (addr && strlen(addr) < NETROUTE_ADDR_LEN && netroute_is_literal(addr))
					memcpy(g_resolve_addrs[g_resolve_count++], addr, strlen(addr) + 1);
				free(addr);
			}
			LeaveCriticalSection(&g_resolve_lock);
			SetEvent(g_resolve_event);
		}
		else if (m.type == JRDP_RESIZE)
		{
			InterlockedExchange(&g_want_w, get_u16(&m));
			InterlockedExchange(&g_want_h, get_u16(&m));
			InterlockedExchange(&g_want_scale, get_u16(&m));
			SetEvent(g_layout_event); /* the RDP thread sends it */
		}
		else if (m.type == JRDP_CLIP_OFFER)
		{
			const UINT8 kinds = get_u8(&m);
			clip_post(JRDP_CLIP_OFFER, kinds, 0, 0, NULL, 0);
		}
		else if (m.type == JRDP_CLIP_FETCH)
		{
			const UINT8 kind = get_u8(&m);
			clip_post(JRDP_CLIP_FETCH, kind, 0, 0, NULL, 0);
		}
		else if (m.type == JRDP_DRIVE_IRP_DONE)
		{
			const UINT32 completionId = get_u32(&m);
			const UINT32 ioStatus = get_u32(&m);
			const size_t len = m.pos <= m.len ? m.len - m.pos : 0;
			drive_complete(completionId, ioStatus, m.data + m.pos, len);
		}
		else if (m.type == JRDP_FILE_FETCH)
		{
			ClipJob* job = calloc(1, sizeof(*job));
			if (job)
			{
				job->type = JRDP_FILE_FETCH;
				job->fetch.common.msgType = CB_FILECONTENTS_REQUEST;
				job->fetch.streamId = get_u32(&m);
				job->fetch.listIndex = get_u32(&m);
				job->fetch.dwFlags = get_u32(&m) & (FILECONTENTS_SIZE | FILECONTENTS_RANGE);
				job->fetch.nPositionLow = get_u32(&m);
				job->fetch.nPositionHigh = get_u32(&m);
				job->fetch.cbRequested = get_u32(&m);
				job->fetch.haveClipDataId = get_u8(&m) != 0;
				job->fetch.clipDataId = get_u32(&m);
				job->id = job->fetch.streamId;
				clip_push(job);
			}
		}
		else if (m.type == JRDP_MIC_DATA)
		{
			const UINT32 streamId = get_u32(&m);
			const size_t len = m.pos <= m.len ? m.len - m.pos : 0;
			audio_mic_data(streamId, m.data + m.pos, len); /* queued for the RDP thread */
		}
		else if (m.type == JRDP_AUDIO_DELAY)
			audio_set_delay(get_u16(&m));
		else if (m.type == JRDP_CLIP_LOCK_REMOTE)
		{
			const UINT8 lock = get_u8(&m);
			const UINT32 id = get_u32(&m);
			clip_post(JRDP_CLIP_LOCK_REMOTE, lock ? 1 : 0, id, 0, NULL, 0);
		}
		else if (m.type == JRDP_FILE_DATA)
		{
			const UINT32 streamId = get_u32(&m);
			const UINT8 ok = get_u8(&m);
			const size_t len = m.pos <= m.len ? m.len - m.pos : 0;
			BYTE* copy = (ok && len) ? malloc(len) : NULL;
			if (copy)
				memcpy(copy, m.data + m.pos, len);
			/* An empty range (the end of a file) is a success; a copy that failed is not. */
			const BOOL good = ok && (len == 0 || copy != NULL);
			clip_post(JRDP_FILE_DATA, 0, streamId, (UINT8)good, copy, copy ? len : 0);
		}
		else if (m.type == JRDP_CLIP_DATA)
		{
			const UINT8 kind = get_u8(&m);
			const UINT8 ok = get_u8(&m);
			const size_t len = m.pos <= m.len ? m.len - m.pos : 0;
			BYTE* copy = (ok && len) ? malloc(len) : NULL;
			if (copy)
				memcpy(copy, m.data + m.pos, len);
			/* An "ok" with nothing to send, or one we could not copy, is a failure. */
			clip_post(JRDP_CLIP_DATA, kind, 0, (UINT8)(copy != NULL), copy, copy ? len : 0);
		}
		else
			handle_input(&m);
		msg_free(&m);
	}
	/* Shut down: refuse any pending certificate prompt or name lookup and abort the session. */
	InterlockedExchange(&g_cert_decision, 0);
	SetEvent(g_cert_event);
	EnterCriticalSection(&g_resolve_lock);
	g_resolve_count = 0;
	LeaveCriticalSection(&g_resolve_lock);
	SetEvent(g_resolve_event);
	EnterCriticalSection(&g_input_lock); /* main() clears g_context under this lock before freeing */
	if (g_context)
		(void)freerdp_abort_connect_context(g_context);
	LeaveCriticalSection(&g_input_lock);
	return 0;
}

/* ------------------------------------------------------------------------------------------ */

#ifndef _WIN32
/* Loads the OpenH264 build jterm downloaded (Linux), from the full path in JTERM_OPENH264.
 *
 * FreeRDP opens the codec by bare name only ("libopenh264.so.8", ...), which would need a library
 * search path, and LD_LIBRARY_PATH is searched before the RUNPATH of FreeRDP's own libraries: every
 * library they load (libwinpr3, libssl, libz) could then come from that directory too, in a process
 * that is handed the password. Instead the codec is loaded here by path first. dlopen by name
 * returns an already-loaded object whose SONAME matches, so FreeRDP's lookup finds this one
 * without searching anything. If the SONAME ever stops matching, FreeRDP finds no codec and the
 * session simply runs without H.264. */
static void preload_openh264(void)
{
	const char* path = getenv("JTERM_OPENH264");
	if (!path || !*path)
		return;
	if (!dlopen(path, RTLD_NOW | RTLD_GLOBAL))
	{
		fprintf(stderr, "jterm-rdp-helper: could not load %s: %s\n", path, dlerror());
		return;
	}
	const char* slash = strrchr(path, '/');
	const char* name = slash ? slash + 1 : path;
	if (!dlopen(name, RTLD_NOW | RTLD_NOLOAD))
		fprintf(stderr, "jterm-rdp-helper: %s does not answer to its file name; H.264 is off\n",
		        path);
}
#endif

#ifdef _WIN32
static void new_h264_probe(void* arg)
{
	*(H264_CONTEXT**)arg = h264_context_new(FALSE);
}
#endif

/* True when this FreeRDP can actually decode H.264 here (Media Foundation on Windows, VideoToolbox
 * on macOS, OpenH264/FFmpeg on Linux). Advertising AVC without a working decoder would let the
 * server send frames we can only show as black. */
static BOOL h264_decoder_available(void)
{
	/* Kept for the life of the process, never freed: freeing it unloads the decoder's library, and
	 * FreeRDP would load it again when the graphics channel opens, after the lockdown, from library
	 * folders the sandbox doesn't let it read (the session then silently runs without H.264). Kept,
	 * the library stays loaded and FreeRDP's open by name finds it without touching a file. */
	static H264_CONTEXT* probe = NULL;
	if (!probe)
	{
#ifdef _WIN32
		/* Media Foundation fails under the restricted child's start-up token (GitHub's Windows
		 * Server runner) and works under the lockdown token, which is what decodes later anyway. */
		sandbox_without_startup_token(new_h264_probe, &probe);
#else
		probe = h264_context_new(FALSE);
#endif
	}
	return probe != NULL;
}

#ifdef __APPLE__
/* After the lockdown, before the server is contacted: decode one frame the way a session would. The
 * Seatbelt profile allows what VideoToolbox was measured to need on the Intel macOS VM (software
 * decoding: IOSurface); a Mac that decodes in hardware may need more, which no machine here can
 * show. A decoder the profile refuses would leave every H.264 frame undecoded, a black desktop, so
 * H.264 is turned off for the session instead and the server falls back to RemoteFX. The DMG jobs'
 * smoke test fails on the refusal line, which is what checks Apple Silicon. */
static void h264_check_after_lockdown(rdpSettings* s)
{
	if (!freerdp_settings_get_bool(s, FreeRDP_GfxH264))
		return;
	const UINT32 w = H264_TEST_FRAME_WIDTH;
	const UINT32 h = H264_TEST_FRAME_HEIGHT;
	H264_CONTEXT* test = h264_context_new(FALSE);
	BYTE* dst = (BYTE*)malloc((size_t)w * h * 4);
	const RECTANGLE_16 rect = { 0, 0, (UINT16)w, (UINT16)h };
	const INT32 rc = test && dst && h264_context_reset(test, w, h)
	                     ? avc420_decompress(test, H264_TEST_FRAME, (UINT32)sizeof(H264_TEST_FRAME), dst,
	                                         PIXEL_FORMAT_BGRX32, w * 4, w, h, &rect, 1)
	                     : -1;
	free(dst);
	h264_context_free(test);
	if (rc >= 0)
	{
		fprintf(stderr, "jterm-rdp-helper: H.264 decoder works under the sandbox\n");
		return;
	}
	fprintf(stderr, "jterm-rdp-helper: H.264 decoder refused under the sandbox (%d): H.264 off for "
	                "this session\n", (int)rc);
	(void)(freerdp_settings_set_bool(s, FreeRDP_GfxH264, FALSE) &&
	       freerdp_settings_set_bool(s, FreeRDP_GfxAVC444, FALSE) &&
	       freerdp_settings_set_bool(s, FreeRDP_GfxAVC444v2, FALSE));
}
#endif

static BOOL apply_connect(rdpSettings* s, Msg* m)
{
	char* host = get_str(m);
	const UINT16 port = get_u16(m);
	char* user = get_str(m);
	char* domain = get_str(m);
	char* password = get_str(m);
	char* pins = get_str(m);
	const UINT16 width = get_u16(m);
	const UINT16 height = get_u16(m);
	const UINT16 scale = get_u16(m);
	/* Absent in a v1 CONNECT: reads as 0, i.e. no clipboard. */
	g_clip_flags = get_u8(m) & (JRDP_CLIP_REMOTE_TO_LOCAL | JRDP_CLIP_LOCAL_TO_REMOTE |
	                            JRDP_CLIP_FILES_TO_REMOTE | JRDP_CLIP_FILES_TO_LOCAL);
	const UINT8 security = get_u8(m);
	char* serverName = get_str(m);
	const UINT8 gwFlags = get_u8(m);
	char* gwHost = get_str(m);
	const UINT16 gwPort = get_u16(m);
	char* gwUser = get_str(m);
	char* gwDomain = get_str(m);
	char* gwPassword = get_str(m);
	const BOOL drivesOk = apply_drives(s, m);
	/* Absent before v10: reads as 0, i.e. no sound. */
	const UINT8 audioFlags = get_u8(m);
	const UINT16 audioLatency = get_u16(m);
	/* Absent before v13: reads as 0, i.e. no printer. */
	const BOOL printerOk = apply_printer(s, m);

	BOOL ok = drivesOk && printerOk && host && *host && user && domain && password && pins &&
	          freerdp_settings_set_string(s, FreeRDP_ServerHostname, host) &&
	          freerdp_settings_set_uint32(s, FreeRDP_ServerPort, port ? port : 3389) &&
	          freerdp_settings_set_string(s, FreeRDP_Username, user) &&
	          (!*domain || freerdp_settings_set_string(s, FreeRDP_Domain, domain)) &&
	          (!*password || freerdp_settings_set_string(s, FreeRDP_Password, password)) &&
	          (!*pins || freerdp_settings_set_string(s, FreeRDP_CertificateAcceptedFingerprints, pins)) &&
	          freerdp_settings_set_uint32(s, FreeRDP_DesktopWidth, width ? width : 1280) &&
	          freerdp_settings_set_uint32(s, FreeRDP_DesktopHeight, height ? height : 800) &&
	          freerdp_settings_set_uint32(s, FreeRDP_ColorDepth, 32) &&
	          /* The graphics pipeline is off by default for clients; GNOME Remote Desktop and
	           * modern Windows servers want it. */
	          freerdp_settings_set_bool(s, FreeRDP_SupportGraphicsPipeline, TRUE) &&
	          /* Redirections default off: the server gets nothing local unless jterm asks. The
	           * clipboard channel is opened only when the session enabled a direction. */
	          freerdp_settings_set_bool(s, FreeRDP_RedirectClipboard, g_clip_flags != 0) &&
	          /* A second fence around file copy, per direction: without the session's opt-in,
	           * FreeRDP's own channel also strips file formats from the format lists and refuses
	           * file-contents requests that way. */
	          freerdp_settings_set_uint32(
	              s, FreeRDP_ClipboardFeatureMask,
	              CLIPRDR_FLAG_DEFAULT_MASK &
	                  ~(UINT32)((clip_files_enabled() ? 0 : CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES) |
	                            (clip_files_to_local_enabled() ? 0 : CLIPRDR_FLAG_REMOTE_TO_LOCAL_FILES))) &&
	          freerdp_settings_set_bool(s, FreeRDP_DeviceRedirection,
	                                    g_drive_count > 0 || (g_printer_flags & JRDP_PRINTER_ENABLED) ||
	                                        (g_smartcard_flags & JRDP_SMARTCARD_ENABLED)) &&
	          /* Never the legacy RDP security layer. It has no TLS certificate, so the pinned
	           * fingerprints above are never consulted: a man-in-the-middle that selects it (or
	           * answers without negotiation data, as pre-2003 servers do) would get past the pin
	           * and be handed the password. FreeRDP's library default allows it; with it off,
	           * negotiation fails instead (FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED). */
	          freerdp_settings_set_bool(s, FreeRDP_RdpSecurity, FALSE) &&
	          /* NLA (CredSSP) by default: the server must prove it knows the password's hash
	           * before the password itself is sent, which protects it even when a wrong
	           * certificate is accepted on first use. TLS without NLA (needed for xrdp, or a
	           * Windows host with NLA turned off) only when the session allows it. Both check the
	           * certificate. RDSTLS, used by GNOME Remote Desktop's login handoff, is switched on
	           * by FreeRDP itself when a server redirect asks for it. */
	          freerdp_settings_set_bool(s, FreeRDP_NlaSecurity, TRUE) &&
	          freerdp_settings_set_bool(s, FreeRDP_TlsSecurity, (security & JRDP_SEC_ALLOW_TLS) != 0) &&
	          /* Through an SSH forward the helper dials 127.0.0.1:<port>, but the certificate must
	           * still be checked against the real host (and the real name sent as TLS SNI):
	           * FreeRDP uses UserSpecifiedServerName for both. Pins work either way; this keeps the
	           * name check and the user's prompt about the host they actually chose. */
	          (!serverName || !*serverName ||
	           freerdp_settings_set_string(s, FreeRDP_UserSpecifiedServerName, serverName));

	/* After DeviceRedirection above, which sound turns on as well (rdpsnd needs rdpdr). */
	if (ok)
		ok = audio_apply_connect(s, audioFlags, audioLatency);

	/* RD Gateway. Always used when configured (TSC_PROXY_MODE_DIRECT, no bypass for local
	 * addresses), so the route never depends on what the client happens to think is local. The
	 * gateway's certificate is checked against the same pinned fingerprints as the desktop's (jterm
	 * sends both); a prompt for it carries VERIFY_CERT_FLAG_GATEWAY. "Same credentials" is copied
	 * here explicitly: FreeRDP does that only in its command-line parser, which the helper skips. */
	if (ok && (gwFlags & JRDP_GW_ENABLED))
	{
		const BOOL same = (gwFlags & JRDP_GW_SAME_CREDENTIALS) != 0;
		ok = gwHost && *gwHost && gwUser && gwDomain && gwPassword &&
		     freerdp_settings_set_bool(s, FreeRDP_GatewayEnabled, TRUE) &&
		     freerdp_settings_set_string(s, FreeRDP_GatewayHostname, gwHost) &&
		     freerdp_settings_set_uint32(s, FreeRDP_GatewayPort, gwPort ? gwPort : 443) &&
		     freerdp_settings_set_uint32(s, FreeRDP_GatewayUsageMethod, TSC_PROXY_MODE_DIRECT) &&
		     freerdp_settings_set_bool(s, FreeRDP_GatewayBypassLocal, FALSE) &&
		     freerdp_settings_set_bool(s, FreeRDP_GatewayUseSameCredentials, same) &&
		     freerdp_settings_set_string(s, FreeRDP_GatewayUsername, same ? user : gwUser) &&
		     (!*(same ? domain : gwDomain) ||
		      freerdp_settings_set_string(s, FreeRDP_GatewayDomain, same ? domain : gwDomain)) &&
		     (!*(same ? password : gwPassword) ||
		      freerdp_settings_set_string(s, FreeRDP_GatewayPassword, same ? password : gwPassword));
		if (!ok)
			fprintf(stderr, "jterm-rdp-helper: invalid RD Gateway settings\n");
	}

	/* H.264 is off by default for library clients (only the command-line parser enables it).
	 * Without it, servers fall back to RemoteFX/planar, which is far too slow for video. */
	if (ok)
	{
		const BOOL h264 = h264_decoder_available();
		fprintf(stderr, "jterm-rdp-helper: H.264 decoder %s\n", h264 ? "available" : "NOT available");
		ok = freerdp_settings_set_bool(s, FreeRDP_GfxH264, h264) &&
		     freerdp_settings_set_bool(s, FreeRDP_GfxAVC444, h264) &&
		     freerdp_settings_set_bool(s, FreeRDP_GfxAVC444v2, h264);
	}

	InterlockedExchange(&g_want_w, width ? width : 1280);
	InterlockedExchange(&g_want_h, height ? height : 800);
	InterlockedExchange(&g_want_scale, (scale >= 100 && scale <= 500) ? scale : 100);

	if (ok && scale > 100 && scale <= 500)
		ok = freerdp_settings_set_uint32(s, FreeRDP_DesktopScaleFactor, scale) &&
		     freerdp_settings_set_uint32(s, FreeRDP_DeviceScaleFactor,
		                                 scale >= 180 ? 180 : (scale >= 140 ? 140 : 100));

	free(host);
	free(user);
	free(domain);
	free_secret(password);
	free(pins);
	free(serverName);
	free(gwHost);
	free(gwUser);
	free(gwDomain);
	free_secret(gwPassword);
	return ok;
}

#ifdef _WIN32
static void seed_random(void* arg)
{
	WINPR_UNUSED(arg);
	BYTE bytes[32];
	if (winpr_RAND(bytes, sizeof(bytes)) < 0)
		fprintf(stderr, "jterm-rdp-helper: cannot seed the random number generator\n");
	SecureZeroMemory(bytes, sizeof(bytes));
}
#endif

int main(int argc, char* argv[])
{
	WINPR_UNUSED(argc);
	WINPR_UNUSED(argv);
	int rc = 1;

	/* Before anything else opens a descriptor (it closes every inherited one above 2) and before
	 * the password can arrive (no core dumps, not dumpable). */
	sandbox_early();
#ifdef _WIN32
	/* Step W2: become a launcher for a restricted copy of this helper, unless this is that copy. */
	{
		const int launched = sandbox_relaunch_restricted();
		if (launched >= 0)
			return launched;
	}
#endif

	/* Take the real stdout for the protocol and point fd 1 at stderr. */
	const int proto_fd = dup(fileno(stdout));
	if (proto_fd < 0)
		return 2;
	/* stderr can be unusable: a GUI-subsystem process handed its parent's *console* handle (e.g.
	 * java run from cmd with stderr inherited) gets no valid fd 2. Logs are then discarded rather
	 * than failing the session — but fd 1 must still be moved off the protocol pipe. */
	if (fileno(stderr) < 0 || dup2(fileno(stderr), fileno(stdout)) < 0)
	{
#ifdef _WIN32
		const int sink = _open("NUL", _O_WRONLY);
#else
		const int sink = open("/dev/null", O_WRONLY);
#endif
		if (sink < 0 || dup2(sink, fileno(stdout)) < 0)
			return 2;
	}
	g_out = fdopen(proto_fd, "wb");
	if (!g_out)
		return 2;
#ifndef _WIN32
	preload_openh264();
#endif
#ifdef _WIN32
	_setmode(proto_fd, _O_BINARY);
	_setmode(fileno(stdin), _O_BINARY);
	/* Seed OpenSSL's random generator now, under the lockdown token: under the start-up token
	 * its CryptoAPI entropy source can fail, and FreeRDP's context creation then fails with it
	 * (license_new draws random bytes). Reseeds happen later, after the lockdown. */
	sandbox_without_startup_token(seed_random, NULL);
#endif
	InitializeCriticalSection(&g_write_lock);
	InitializeCriticalSection(&g_input_lock);
	g_cert_event = CreateEvent(NULL, TRUE, FALSE, NULL);
	g_resolve_event = CreateEvent(NULL, TRUE, FALSE, NULL);
	InitializeCriticalSection(&g_resolve_lock);
	g_layout_event = CreateEvent(NULL, TRUE, FALSE, NULL); /* manual reset: cleared by the loop */
	InitializeCriticalSection(&g_clip_lock);
	InitializeCriticalSection(&g_drive_lock);
	audio_init();
	g_clip_event = CreateEvent(NULL, TRUE, FALSE, NULL); /* manual reset: cleared by the loop */

	/* Sockets need Winsock initialised on Windows (a no-op in WinPR elsewhere). */
	WSADATA wsa;
	if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0)
		return 2;

	/* Namespace any FreeRDP config/state files away from a system FreeRDP's. */
	(void)freerdp_setApplicationDetails("jterm", "jterm-rdp-helper", -1);

	/* HELLO handshake. */
	Msg m = { 0 };
	if (!read_msg(&m) || m.type != JRDP_HELLO || get_u16(&m) != JRDP_PROTOCOL_VERSION)
	{
		Buf b = { 0 };
		buf_u16(&b, JRDP_PROTOCOL_VERSION);
		buf_str(&b, freerdp_get_version_string());
		send_msg(JRDP_HELLO_REPLY, &b);
		free(b.data);
		send_disconnected(0xFFFFFFFF, "protocol version mismatch");
		msg_free(&m);
		return 3;
	}
	msg_free(&m);
	{
		Buf b = { 0 };
		buf_u16(&b, JRDP_PROTOCOL_VERSION);
		buf_str(&b, freerdp_get_version_string());
		send_msg(JRDP_HELLO_REPLY, &b);
		free(b.data);
	}

	RDP_CLIENT_ENTRY_POINTS ep = { 0 };
	ep.Version = RDP_CLIENT_INTERFACE_VERSION;
	ep.Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
	ep.ContextSize = sizeof(jrdpContext);
	ep.ClientNew = j_client_new;
	ep.ClientFree = j_client_free;
	ep.ClientStart = j_client_start;
	ep.ClientStop = j_client_stop;
	rdpContext* context = freerdp_client_context_new(&ep);
	if (!context)
	{
		/* FreeRDP doesn't say why. */
		fprintf(stderr, "jterm-rdp-helper: cannot create the FreeRDP client context\n");
		return 4;
	}
	/* Wrap the static add-in provider freerdp_client_context_new just registered (see the drive
	 * section): "drive" becomes the forwarding device, other device services nothing. */
	g_prev_addin_provider = freerdp_get_current_addin_provider();
	if (freerdp_register_addin_provider(j_addin_provider, 0) != CHANNEL_RC_OK)
	{
		freerdp_client_context_free(context);
		return 4;
	}

	/* CONNECT. */
	if (!read_msg(&m) || m.type != JRDP_CONNECT || !apply_connect(context->settings, &m))
	{
		msg_free(&m);
		send_disconnected(0xFFFFFFFE, "invalid CONNECT message");
		freerdp_client_context_free(context);
		return 5;
	}
	msg_free(&m);

	/* The lockdown point (sandbox design): CONNECT is applied, every library is loaded (OpenSSL,
	 * the H.264 and AAC decoders), and no byte from the server has been read. Names are resolved
	 * first, since nothing can resolve them afterwards. Before the reader thread exists, so it is
	 * restricted too (Landlock applies to the calling thread and the threads it starts later). */
	if (sandbox_needs_early_resolution() && !netroute_prepare(context))
	{
		send_disconnected(0xFFFFFFFD, "could not install the connection hook");
		freerdp_client_context_free(context);
		return 5;
	}
	{
		uint16_t ports[2];
		const size_t nports = netroute_ports(context, ports, ARRAYSIZE(ports));
		sandbox_lockdown(ports, nports);
		sandbox_selftest();
	}
#ifdef __APPLE__
	h264_check_after_lockdown(context->settings);
#endif

	g_context = context;
	HANDLE reader = CreateThread(NULL, 0, reader_thread, NULL, 0, NULL);
	if (!reader || freerdp_client_start(context) != 0)
		goto out;

	freerdp* instance = context->instance;
	if (!freerdp_connect(instance))
	{
		const UINT32 err = freerdp_get_last_error(context);
		send_disconnected(err, freerdp_get_last_error_string(err));
		goto out;
	}
	{
		rdpGdi* gdi = context->gdi;
		Buf b = { 0 };
		buf_u16(&b, (UINT16)gdi->width);
		buf_u16(&b, (UINT16)gdi->height);
		buf_u8(&b, freerdp_settings_get_bool(context->settings, FreeRDP_UnicodeInput) ? 1 : 0);
		send_msg(JRDP_CONNECTED, &b);
		free(b.data);
		send_frame(gdi, 0, 0, gdi->width, gdi->height);
	}
	InterlockedExchange(&g_connected, 1);

	HANDLE handles[MAXIMUM_WAIT_OBJECTS] = { 0 };
	while (!freerdp_shall_disconnect_context(context))
	{
		const DWORD n = freerdp_get_event_handles(context, handles, ARRAYSIZE(handles) - 3);
		if (n == 0)
			break;
		handles[n] = g_layout_event;
		handles[n + 1] = g_clip_event;
		handles[n + 2] = audio_mic_event();
		const DWORD status = WaitForMultipleObjects(n + 3, handles, FALSE, INFINITE);
		if (status == WAIT_FAILED)
			break;
		if (WaitForSingleObject(g_layout_event, 0) == WAIT_OBJECT_0)
		{
			ResetEvent(g_layout_event); /* before reading the wanted layout, so none is lost */
			send_monitor_layout();
		}
		if (WaitForSingleObject(g_clip_event, 0) == WAIT_OBJECT_0)
		{
			ResetEvent(g_clip_event); /* before draining, so a job posted meanwhile re-signals */
			clip_process_jobs();
		}
		if (WaitForSingleObject(audio_mic_event(), 0) == WAIT_OBJECT_0)
		{
			ResetEvent(audio_mic_event()); /* before draining, as for the clipboard */
			audio_mic_process();
		}
		if (!freerdp_check_event_handles(context))
			break;
	}

	EnterCriticalSection(&g_input_lock);
	InterlockedExchange(&g_connected, 0);
	LeaveCriticalSection(&g_input_lock);

	{
		const UINT32 err = freerdp_get_last_error(context);
		send_disconnected(err, err ? freerdp_get_last_error_string(err) : "disconnected");
	}
	freerdp_disconnect(instance);
	rc = 0;

out:
	(void)freerdp_client_stop(context);
	EnterCriticalSection(&g_input_lock);
	g_context = NULL;
	LeaveCriticalSection(&g_input_lock);
	freerdp_client_context_free(context);
	fflush(g_out);
	/* The reader thread may still be blocked on stdin; exiting the process ends it. */
	return rc;
}
