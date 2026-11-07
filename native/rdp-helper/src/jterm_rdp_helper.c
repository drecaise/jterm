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
#include <winpr/winsock.h>

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
#include <fcntl.h>
#include <unistd.h>
#endif

#include "protocol.h"

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

/* ------------------------------------------------------------------------------------------ */
/* Protocol output                                                                            */

typedef struct
{
	BYTE* data;
	size_t len;
	size_t cap;
} Buf;

static BOOL buf_reserve(Buf* b, size_t extra)
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

static void buf_u8(Buf* b, UINT8 v)
{
	if (buf_reserve(b, 1))
		b->data[b->len++] = v;
}

static void buf_u16(Buf* b, UINT16 v)
{
	if (buf_reserve(b, 2))
	{
		b->data[b->len++] = (BYTE)(v & 0xFF);
		b->data[b->len++] = (BYTE)(v >> 8);
	}
}

static void buf_u32(Buf* b, UINT32 v)
{
	if (buf_reserve(b, 4))
	{
		for (int i = 0; i < 4; i++)
			b->data[b->len++] = (BYTE)((v >> (8 * i)) & 0xFF);
	}
}

static void buf_str(Buf* b, const char* s)
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

static void write_u32_raw(UINT32 v)
{
	BYTE le[4] = { (BYTE)v, (BYTE)(v >> 8), (BYTE)(v >> 16), (BYTE)(v >> 24) };
	fwrite(le, 1, 4, g_out);
}

/* Send a complete message: u32 length (type + payload), u8 type, payload. */
static void send_msg(UINT8 type, const Buf* payload)
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

/* FRAME: header then the dirty rectangle's rows straight out of the framebuffer (BGRX32). */
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
	EnterCriticalSection(&g_write_lock);
	write_u32_raw((UINT32)(1 + 8 + rowBytes * (size_t)h));
	fputc(JRDP_FRAME, g_out);
	BYTE hdr[8] = { (BYTE)x, (BYTE)(x >> 8), (BYTE)y, (BYTE)(y >> 8),
		            (BYTE)w, (BYTE)(w >> 8), (BYTE)h, (BYTE)(h >> 8) };
	fwrite(hdr, 1, 8, g_out);
	for (INT32 row = 0; row < h; row++)
	{
		const BYTE* src = gdi->primary_buffer + (size_t)(y + row) * gdi->stride + (size_t)x * 4;
		fwrite(src, 1, rowBytes, g_out);
	}
	fflush(g_out);
	LeaveCriticalSection(&g_write_lock);
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

	if (hwnd->ninvalid <= 0)
	{
		send_frame(gdi, hwnd->invalid->x, hwnd->invalid->y, hwnd->invalid->w, hwnd->invalid->h);
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
		return TRUE;
	}
	for (INT32 i = 0; i < hwnd->ninvalid; i++)
		send_frame(gdi, hwnd->cinvalid[i].x, hwnd->cinvalid[i].y, hwnd->cinvalid[i].w,
		           hwnd->cinvalid[i].h);
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

static void j_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
	if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		g_disp = (DispClientContext*)e->pInterface;
		g_disp->custom = context;
		g_disp->DisplayControlCaps = j_disp_caps;
		return;
	}
	freerdp_client_OnChannelConnectedEventHandler(context, e);
}

static void j_channel_disconnected(void* context, const ChannelDisconnectedEventArgs* e)
{
	if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		InterlockedExchange(&g_disp_ready, 0);
		g_disp = NULL;
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
				DWORD sc = GetVirtualScanCodeFromVirtualKeyCode(vk, WINPR_KBD_TYPE_IBM_ENHANCED);
				if (flags & JRDP_KEY_FORCE_EXTENDED)
					sc |= KBDEXT;
				if (flags & JRDP_KEY_FORCE_NOT_EXTENDED)
					sc &= ~KBDEXT;
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
		else if (m.type == JRDP_RESIZE)
		{
			InterlockedExchange(&g_want_w, get_u16(&m));
			InterlockedExchange(&g_want_h, get_u16(&m));
			InterlockedExchange(&g_want_scale, get_u16(&m));
			SetEvent(g_layout_event); /* the RDP thread sends it */
		}
		else
			handle_input(&m);
		msg_free(&m);
	}
	/* Shut down: refuse any pending certificate prompt and abort the session. */
	InterlockedExchange(&g_cert_decision, 0);
	SetEvent(g_cert_event);
	EnterCriticalSection(&g_input_lock); /* main() clears g_context under this lock before freeing */
	if (g_context)
		(void)freerdp_abort_connect_context(g_context);
	LeaveCriticalSection(&g_input_lock);
	return 0;
}

/* ------------------------------------------------------------------------------------------ */

/* True when this FreeRDP can actually decode H.264 here (Media Foundation on Windows, VideoToolbox
 * on macOS, OpenH264/FFmpeg on Linux). Advertising AVC without a working decoder would let the
 * server send frames we can only show as black. */
static BOOL h264_decoder_available(void)
{
	H264_CONTEXT* h264 = h264_context_new(FALSE);
	if (!h264)
		return FALSE;
	h264_context_free(h264);
	return TRUE;
}

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

	BOOL ok = host && *host && user && domain && password && pins &&
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
	          /* Redirections default off: the server gets nothing local unless jterm asks. */
	          freerdp_settings_set_bool(s, FreeRDP_RedirectClipboard, FALSE) &&
	          freerdp_settings_set_bool(s, FreeRDP_DeviceRedirection, FALSE);

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
	return ok;
}

int main(int argc, char* argv[])
{
	WINPR_UNUSED(argc);
	WINPR_UNUSED(argv);
	int rc = 1;

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
#ifdef _WIN32
	_setmode(proto_fd, _O_BINARY);
	_setmode(fileno(stdin), _O_BINARY);
#endif
	InitializeCriticalSection(&g_write_lock);
	InitializeCriticalSection(&g_input_lock);
	g_cert_event = CreateEvent(NULL, TRUE, FALSE, NULL);
	g_layout_event = CreateEvent(NULL, TRUE, FALSE, NULL); /* manual reset: cleared by the loop */

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
		return 4;

	/* CONNECT. */
	if (!read_msg(&m) || m.type != JRDP_CONNECT || !apply_connect(context->settings, &m))
	{
		msg_free(&m);
		send_disconnected(0xFFFFFFFE, "invalid CONNECT message");
		freerdp_client_context_free(context);
		return 5;
	}
	msg_free(&m);

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
		const DWORD n = freerdp_get_event_handles(context, handles, ARRAYSIZE(handles) - 1);
		if (n == 0)
			break;
		handles[n] = g_layout_event;
		const DWORD status = WaitForMultipleObjects(n + 1, handles, FALSE, INFINITE);
		if (status == WAIT_FAILED)
			break;
		if (WaitForSingleObject(g_layout_event, 0) == WAIT_OBJECT_0)
		{
			ResetEvent(g_layout_event); /* before reading the wanted layout, so none is lost */
			send_monitor_layout();
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
