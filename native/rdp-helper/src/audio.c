/*
 * jterm-rdp-helper: sound forwarding (rdpsnd playback, audin microphone).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The helper never touches a sound device (design: docs/superpowers/specs/2026-11-11-rdp-audio-
 * design.md, property A5). FreeRDP's own backends (PulseAudio, ALSA, WinMM, CoreAudio) are not
 * used: rdpsnd is loaded with "sys:jterm", the add-in provider answers that subsystem with the
 * forwarding device below, and every sample goes to jterm as AUDIO_DATA, which plays it through
 * Java Sound.
 *
 * Sharp edges:
 *  - Open must never fail. A FALSE from it ends rdpsnd's play thread, and with it the session's
 *    sound for good. So Open only forgets the announced format.
 *  - PlayEx decides, not FormatSupported/Open. rdpsnd offers the server every PCM variant (its DSP
 *    "supports" PCM whatever the device says), and a variant the device refused reaches PlayEx
 *    unconverted with its own format: 8-bit or 6-channel bytes. PlayEx alone sees the real format
 *    of what it is handed, so it drops anything outside the list.
 *  - AAC (protocol flag JRDP_AUDIO_AAC, and a decoder found by aac_probe) is decoded here, in the
 *    helper, by the system's decoder (aac.h), and goes to jterm as PCM like everything else; the
 *    JVM never parses compressed audio. Claimed only with cbSize 0, so the configuration given to
 *    the decoder is built from the format, never read from the server. The decoder outlives Close
 *    (a bit-rate switch is Close + Open for rdpsnd).
 *  - FreeRDP makes one device for the static channel and one for the dynamic one. The first to play
 *    owns the stream until its Close; the other's waves are dropped, so two channels can never
 *    interleave samples in jterm's queue.
 *  - The microphone device never captures anything: jterm decides whether the local microphone may
 *    be heard (consent, mute) and sends either its samples or silence as MIC_DATA. Open only
 *    announces the stream and returns at once; it must not wait for the user.
 *  - audin's receive callback writes to the channel, so it is never called from the stdin reader:
 *    the reader checks and queues MIC_DATA, and the RDP thread (main loop) hands it to audin, the
 *    same rule as the clipboard. receive's user data is audin's channel callback, which audin frees
 *    right after our Close returns; receive is therefore only called under g_mic_lock with the
 *    callback still set, and Close clears it under the same lock.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <winpr/synch.h>
#include <winpr/interlocked.h>

#include <freerdp/addin.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/rdpsnd.h>
#include <freerdp/client/audin.h>
#include <freerdp/codec/audio.h>
#include <freerdp/channels/rdpsnd.h>
#include <freerdp/channels/audin.h>

#include "protocol.h"
#include "helper.h"
#include "audio.h"
#include "aac.h"

static const UINT32 k_rates[] = JRDP_AUDIO_RATES;

static UINT8 g_audio_flags = 0;    /* written once by apply_connect, before the session starts */
static BOOL g_aac = FALSE;         /* likewise: AAC asked for and a decoder found */
/* Reported to the server as the render delay: CONNECT's value until jterm measures the real one and
 * sends AUDIO_DELAY (stdin reader thread; read by the play threads, hence the interlocked access). */
static volatile LONG g_latency_ms = 0;

typedef struct
{
	rdpsndDevicePlugin device; /* must be first */
	BOOL dynamic;
	BOOL formatsLogged;
} JSound;

/* Everything below is guarded by g_audio_lock: the static and the dynamic channel each call their
 * device from their own play thread. Lock order: g_audio_lock, then send_msg's write lock. */
static CRITICAL_SECTION g_audio_lock;
static JSound* g_owner = NULL;      /* the device whose stream jterm is playing, or NULL */
static BOOL g_announced = FALSE;    /* an AUDIO_OPEN is in force */
static UINT32 g_open_rate = 0;
static UINT16 g_open_channels = 0;
static UINT8 g_open_codec = 0;
static UINT32 g_volume = 0xFFFFFFFF; /* low word left, high word right */
static UINT64 g_dropped_waves = 0;
/* The AAC decoder. It outlives a Close: Windows offers its AAC formats at eight bit rates, and a
 * switch between them is a new format number for rdpsnd, i.e. Close + Open. Restarting the decoder
 * there would click, so it is replaced only when the rate or channel count changes. */
static void* g_dec = NULL;
static JSound* g_dec_device = NULL;
static UINT32 g_dec_rate = 0;
static UINT16 g_dec_channels = 0;
static BOOL g_dec_failed = FALSE;  /* aac_open failed for g_dec_rate/g_dec_channels */
static BYTE* g_pcm = NULL;         /* decoder output, one second of audio at most */
static size_t g_pcm_cap = 0;
static UINT64 g_aac_errors = 0;
static UINT32 g_aac_rate_bytes = 0; /* the server's AAC bit rate (nAvgBytesPerSec) last seen */
static UINT64 g_aac_switches = 0;

/* Microphone state, guarded by g_mic_lock (taken by audin's channel thread in Open/Close, by the
 * stdin reader when it queues MIC_DATA and by the RDP thread when it hands a packet to audin). Lock
 * order: g_mic_lock, then the channel's own lock (inside receive), then send_msg's write lock. */
#define MIC_QUEUE_MAX 16

typedef struct
{
	UINT32 streamId;
	size_t len;
	BYTE* data;
} MicPacket;

static CRITICAL_SECTION g_mic_lock;
static HANDLE g_mic_event = NULL;        /* manual reset: set when a packet is queued */
static AudinReceive g_mic_receive = NULL; /* set between Open and Close */
static void* g_mic_user = NULL;
static AUDIO_FORMAT g_mic_format;        /* from SetFormat; PCM, so no codec data */
static UINT32 g_mic_frames = 0;
static UINT32 g_mic_stream = 0;          /* id of the open stream, 0 = none */
static UINT32 g_mic_next_id = 0;
static MicPacket g_mic_queue[MIC_QUEUE_MAX];
static size_t g_mic_head = 0;            /* oldest packet */
static size_t g_mic_count = 0;
static UINT64 g_mic_dropped = 0;

void audio_init(void)
{
	InitializeCriticalSection(&g_audio_lock);
	InitializeCriticalSection(&g_mic_lock);
	g_mic_event = CreateEvent(NULL, TRUE, FALSE, NULL);
}

static BOOL rate_listed(UINT32 rate)
{
	for (size_t i = 0; i < ARRAYSIZE(k_rates); i++)
	{
		if (k_rates[i] == rate)
			return TRUE;
	}
	return FALSE;
}

/* Exactly what jterm accepts: 16-bit PCM, 1 or 2 channels, a listed rate, a consistent block. */
static BOOL pcm_supported(const AUDIO_FORMAT* f)
{
	return f && f->wFormatTag == WAVE_FORMAT_PCM && f->wBitsPerSample == 16 &&
	       (f->nChannels == 1 || f->nChannels == 2) && rate_listed(f->nSamplesPerSec) &&
	       f->nBlockAlign == f->nChannels * 2;
}

/* AAC as Windows sends it (see aac.h): the codec configuration is built from rate and channels, so
 * a format carrying its own (cbSize != 0) is not claimed and the server falls back to PCM. */
static BOOL aac_supported(const AUDIO_FORMAT* f)
{
	return g_aac && f && f->wFormatTag == WAVE_FORMAT_AAC_MS && (f->nChannels == 1 || f->nChannels == 2) &&
	       rate_listed(f->nSamplesPerSec) && f->cbSize == 0;
}

static BOOL js_format_supported(rdpsndDevicePlugin* device, const AUDIO_FORMAT* format)
{
	WINPR_UNUSED(device);
	return pcm_supported(format) || aac_supported(format);
}

static UINT latency_ms(void)
{
	return (UINT)InterlockedCompareExchange(&g_latency_ms, 0, 0);
}

/* Never fails (see the top of the file). The stream is announced by the first wave instead. */
static BOOL js_open(rdpsndDevicePlugin* device, const AUDIO_FORMAT* format, UINT32 latency)
{
	WINPR_UNUSED(device);
	WINPR_UNUSED(format);
	WINPR_UNUSED(latency);
	return TRUE;
}

/* Lock held. Ends jterm's stream if this device owns it. */
static void release_stream(JSound* js)
{
	if (g_owner != js)
		return;
	if (g_announced)
		send_msg(JRDP_AUDIO_CLOSE, NULL);
	g_owner = NULL;
	g_announced = FALSE;
}

static void js_close(rdpsndDevicePlugin* device)
{
	EnterCriticalSection(&g_audio_lock);
	release_stream((JSound*)device);
	LeaveCriticalSection(&g_audio_lock);
}

static void js_free(rdpsndDevicePlugin* device)
{
	if (!device)
		return;
	js_close(device);
	EnterCriticalSection(&g_audio_lock);
	if (g_dec_device == (JSound*)device)
	{
		aac_close(g_dec);
		g_dec = NULL;
		g_dec_device = NULL;
	}
	LeaveCriticalSection(&g_audio_lock);
	free(device);
}

static UINT32 js_get_volume(rdpsndDevicePlugin* device)
{
	WINPR_UNUSED(device);
	EnterCriticalSection(&g_audio_lock);
	const UINT32 v = g_volume;
	LeaveCriticalSection(&g_audio_lock);
	return v;
}

static BOOL js_set_volume(rdpsndDevicePlugin* device, UINT32 value)
{
	JSound* js = (JSound*)device;
	EnterCriticalSection(&g_audio_lock);
	/* The idle channel's volume is not the one being heard. */
	if (!g_owner || g_owner == js)
	{
		g_volume = value;
		Buf b = { 0 };
		buf_u16(&b, (UINT16)(value & 0xFFFF));
		buf_u16(&b, (UINT16)(value >> 16));
		send_msg(JRDP_AUDIO_VOLUME, &b);
		free(b.data);
	}
	LeaveCriticalSection(&g_audio_lock);
	return TRUE;
}

/* Logs the server's format list once per channel: what a server offers is otherwise invisible,
 * and it decides what can be played. */
static UINT js_server_format_announce(rdpsndDevicePlugin* device, const AUDIO_FORMAT* formats,
                                      size_t count)
{
	JSound* js = (JSound*)device;
	if (js->formatsLogged)
		return CHANNEL_RC_OK;
	js->formatsLogged = TRUE;
	char line[4096]; /* Windows' 30 formats take about 1000 */
	size_t n = (size_t)snprintf(line, sizeof(line),
	                            "jterm-rdp-helper: sound (%s): server offers %" PRIuz " format(s):",
	                            js->dynamic ? "dynamic" : "static", count);
	for (size_t i = 0; i < count && n < sizeof(line); i++)
	{
		const AUDIO_FORMAT* f = &formats[i];
		n += (size_t)snprintf(line + n, sizeof(line) - n, " %s/%" PRIu16 "ch/%" PRIu32 "/%" PRIu16 "b%s",
		                      audio_format_get_tag_string(f->wFormatTag), f->nChannels,
		                      f->nSamplesPerSec, f->wBitsPerSample,
		                      js_format_supported(device, f) ? "*" : "");
	}
	/* "*" marks what jterm claims. A list too long for the line says so instead of just ending. */
	fprintf(stderr, "%s%s\n", line, n >= sizeof(line) ? " [list cut]" : "");
	return CHANNEL_RC_OK;
}

/* Lock held. Splits a wave into AUDIO_DATA messages of whole frames, at most the protocol's cap. */
static void send_samples(const BYTE* data, size_t size, UINT16 channels)
{
	const size_t frame = (size_t)channels * 2;
	const size_t maxChunk = (JRDP_AUDIO_MAX_CHUNK / frame) * frame;
	size -= size % frame; /* a partial frame at the end is dropped */
	while (size)
	{
		const size_t n = size < maxChunk ? size : maxChunk;
		const Buf b = { (BYTE*)data, n, n };
		send_msg(JRDP_AUDIO_DATA, &b);
		data += n;
		size -= n;
	}
}

/* Lock held. Decodes one AAC wave into g_pcm. FALSE drops the wave (no decoder, or an error). */
static BOOL decode_wave(JSound* js, const AUDIO_FORMAT* f, const BYTE* data, size_t size, size_t* pcmLen)
{
	*pcmLen = 0;
	if (g_dec && (g_dec_rate != f->nSamplesPerSec || g_dec_channels != f->nChannels))
	{
		aac_close(g_dec);
		g_dec = NULL;
	}
	if (!g_dec)
	{
		if (g_dec_failed && g_dec_rate == f->nSamplesPerSec && g_dec_channels == f->nChannels)
			return FALSE;
		g_dec_rate = f->nSamplesPerSec;
		g_dec_channels = f->nChannels;
		const size_t cap = (size_t)f->nSamplesPerSec * f->nChannels * 2; /* one second */
		if (cap > g_pcm_cap)
		{
			BYTE* p = realloc(g_pcm, cap);
			if (!p)
				return FALSE;
			g_pcm = p;
			g_pcm_cap = cap;
		}
		g_dec = aac_open(f->nSamplesPerSec, f->nChannels);
		g_dec_failed = g_dec == NULL;
		g_dec_device = js;
		if (!g_dec)
		{
			fprintf(stderr, "jterm-rdp-helper: sound: the AAC decoder did not start for %" PRIu32
			                " Hz, %" PRIu16 " channel(s); this stream stays silent\n",
			        f->nSamplesPerSec, f->nChannels);
			return FALSE;
		}
	}
	/* Output beyond one second of audio for one wave is an error, as is any decoder error: the wave
	 * is dropped and the session goes on. */
	if (!aac_decode(g_dec, data, size, g_pcm, (size_t)f->nSamplesPerSec * f->nChannels * 2, pcmLen))
	{
		g_aac_errors++;
		if (g_aac_errors == 1 || g_aac_errors % 1000 == 0)
			fprintf(stderr, "jterm-rdp-helper: sound: %" PRIu64 " AAC wave(s) could not be decoded\n",
			        g_aac_errors);
		return FALSE;
	}
	if (f->nAvgBytesPerSec != g_aac_rate_bytes)
	{
		if (g_aac_rate_bytes != 0)
		{
			g_aac_switches++;
			if (g_aac_switches <= 10 || g_aac_switches % 100 == 0)
				fprintf(stderr, "jterm-rdp-helper: sound: AAC bit rate %" PRIu32 " -> %" PRIu32
				                " bytes/s (switch %" PRIu64 ")\n",
				        g_aac_rate_bytes, f->nAvgBytesPerSec, g_aac_switches);
		}
		g_aac_rate_bytes = f->nAvgBytesPerSec;
	}
	return TRUE;
}

static UINT js_play_ex(rdpsndDevicePlugin* device, const AUDIO_FORMAT* format, const BYTE* data,
                       size_t size)
{
	JSound* js = (JSound*)device;
	EnterCriticalSection(&g_audio_lock);
	const BOOL aac = aac_supported(format);
	if (!aac && !pcm_supported(format))
	{
		/* Bytes FreeRDP did not convert (see the top of the file): never forwarded. */
		g_dropped_waves++;
		if (g_dropped_waves == 1)
			fprintf(stderr, "jterm-rdp-helper: sound: dropping %s/%" PRIu16 "ch/%" PRIu32 "/%" PRIu16
			                "b waves, a format jterm does not play\n",
			        format ? audio_format_get_tag_string(format->wFormatTag) : "?",
			        format ? format->nChannels : 0, format ? format->nSamplesPerSec : 0,
			        format ? format->wBitsPerSample : 0);
		LeaveCriticalSection(&g_audio_lock);
		return latency_ms();
	}
	if (!g_owner)
		g_owner = js;
	if (g_owner != js)
	{
		LeaveCriticalSection(&g_audio_lock);
		return latency_ms();
	}
	const BYTE* pcm = data;
	size_t pcmLen = size;
	if (aac)
	{
		if (!decode_wave(js, format, data, size, &pcmLen))
		{
			LeaveCriticalSection(&g_audio_lock);
			return latency_ms();
		}
		pcm = g_pcm;
	}
	const UINT8 codec = aac ? JRDP_AUDIO_CODEC_AAC : JRDP_AUDIO_CODEC_PCM;
	if (!g_announced || g_open_rate != format->nSamplesPerSec ||
	    g_open_channels != format->nChannels || g_open_codec != codec)
	{
		g_open_rate = format->nSamplesPerSec;
		g_open_channels = format->nChannels;
		g_open_codec = codec;
		g_announced = TRUE;
		Buf b = { 0 };
		buf_u32(&b, g_open_rate);
		buf_u8(&b, (UINT8)g_open_channels);
		buf_u8(&b, g_open_codec);
		send_msg(JRDP_AUDIO_OPEN, &b);
		free(b.data);
		fprintf(stderr, "jterm-rdp-helper: sound (%s): playing %s %" PRIu32 " Hz, %" PRIu16
		                " channel(s), waves of %" PRIuz " bytes%s\n",
		        js->dynamic ? "dynamic" : "static", aac ? "AAC" : "PCM", g_open_rate, g_open_channels,
		        size, aac ? " (decoded by the system's AAC decoder)" : "");
	}
	if (pcmLen)
		send_samples(pcm, pcmLen, format->nChannels);
	LeaveCriticalSection(&g_audio_lock);
	return latency_ms();
}

/* rdpsnd's subsystem entry for "sys:jterm". */
static UINT VCAPITYPE js_entry(PFREERDP_RDPSND_DEVICE_ENTRY_POINTS ep)
{
	if (!ep || !ep->rdpsnd || !ep->pRegisterRdpsndDevice)
		return ERROR_INVALID_PARAMETER;
	JSound* js = calloc(1, sizeof(*js));
	if (!js)
		return CHANNEL_RC_NO_MEMORY;
	js->device.FormatSupported = js_format_supported;
	js->device.Open = js_open;
	js->device.GetVolume = js_get_volume;
	js->device.SetVolume = js_set_volume;
	js->device.PlayEx = js_play_ex;
	js->device.Close = js_close;
	js->device.Free = js_free;
	js->device.ServerFormatAnnounce = js_server_format_announce;
	/* DefaultFormat stays unset: a format we don't support is dropped in PlayEx, not converted. */
	ep->pRegisterRdpsndDevice(ep->rdpsnd, &js->device);
	return CHANNEL_RC_OK;
}


/* ---- Microphone (audin, subsystem "jterm") ---------------------------------------------------- */

/* Lock held. Drops every queued packet. */
static void mic_queue_clear(void)
{
	while (g_mic_count)
	{
		MicPacket* p = &g_mic_queue[g_mic_head];
		free(p->data);
		p->data = NULL;
		g_mic_head = (g_mic_head + 1) % MIC_QUEUE_MAX;
		g_mic_count--;
	}
	g_mic_head = 0;
}

static BOOL mic_format_supported(IAudinDevice* device, const AUDIO_FORMAT* format)
{
	WINPR_UNUSED(device);
	return pcm_supported(format);
}

static UINT mic_set_format(IAudinDevice* device, const AUDIO_FORMAT* format, UINT32 framesPerPacket)
{
	WINPR_UNUSED(device);
	if (!pcm_supported(format))
		return ERROR_INVALID_PARAMETER; /* audin only asks for a format FormatSupported accepted */
	EnterCriticalSection(&g_mic_lock);
	memset(&g_mic_format, 0, sizeof(g_mic_format));
	g_mic_format.wFormatTag = WAVE_FORMAT_PCM;
	g_mic_format.nChannels = format->nChannels;
	g_mic_format.nSamplesPerSec = format->nSamplesPerSec;
	g_mic_format.wBitsPerSample = 16;
	g_mic_format.nBlockAlign = (UINT16)(format->nChannels * 2);
	g_mic_format.nAvgBytesPerSec = format->nSamplesPerSec * g_mic_format.nBlockAlign;
	g_mic_frames = framesPerPacket;
	LeaveCriticalSection(&g_mic_lock);
	return CHANNEL_RC_OK;
}

/* Announces the stream and returns: consent is jterm's business and must not block audin. */
static UINT mic_open(IAudinDevice* device, AudinReceive receive, void* userData)
{
	WINPR_UNUSED(device);
	if (!receive)
		return ERROR_INVALID_PARAMETER;
	EnterCriticalSection(&g_mic_lock);
	if (g_mic_receive && g_mic_stream)
	{
		/* audin closes before it opens again; never leave jterm with two streams. */
		Buf c = { 0 };
		buf_u32(&c, g_mic_stream);
		send_msg(JRDP_MIC_CLOSE, &c);
		free(c.data);
	}
	mic_queue_clear();
	g_mic_receive = receive;
	g_mic_user = userData;
	if (++g_mic_next_id == 0)
		g_mic_next_id = 1;
	g_mic_stream = g_mic_next_id;
	Buf b = { 0 };
	buf_u32(&b, g_mic_stream);
	buf_u32(&b, g_mic_format.nSamplesPerSec);
	buf_u8(&b, (UINT8)g_mic_format.nChannels);
	buf_u32(&b, g_mic_frames);
	send_msg(JRDP_MIC_OPEN, &b);
	free(b.data);
	fprintf(stderr, "jterm-rdp-helper: microphone: server opened PCM %" PRIu32 " Hz, %" PRIu16
	                " channel(s), %" PRIu32 " frames per packet\n",
	        g_mic_format.nSamplesPerSec, g_mic_format.nChannels, g_mic_frames);
	LeaveCriticalSection(&g_mic_lock);
	return CHANNEL_RC_OK;
}

static UINT mic_close(IAudinDevice* device)
{
	WINPR_UNUSED(device);
	EnterCriticalSection(&g_mic_lock);
	if (g_mic_receive)
	{
		Buf b = { 0 };
		buf_u32(&b, g_mic_stream);
		send_msg(JRDP_MIC_CLOSE, &b);
		free(b.data);
	}
	g_mic_receive = NULL;
	g_mic_user = NULL;
	g_mic_stream = 0;
	mic_queue_clear();
	LeaveCriticalSection(&g_mic_lock);
	return CHANNEL_RC_OK;
}

static UINT mic_free(IAudinDevice* device)
{
	if (!device)
		return CHANNEL_RC_OK;
	(void)mic_close(device);
	free(device);
	return CHANNEL_RC_OK;
}

/* audin's subsystem entry for "sys:jterm". */
static UINT VCAPITYPE mic_entry(PFREERDP_AUDIN_DEVICE_ENTRY_POINTS ep)
{
	if (!ep || !ep->plugin || !ep->pRegisterAudinDevice)
		return ERROR_INVALID_PARAMETER;
	IAudinDevice* dev = calloc(1, sizeof(*dev));
	if (!dev)
		return CHANNEL_RC_NO_MEMORY;
	dev->Open = mic_open;
	dev->FormatSupported = mic_format_supported;
	dev->SetFormat = mic_set_format;
	dev->Close = mic_close;
	dev->Free = mic_free;
	const UINT rc = ep->pRegisterAudinDevice(ep->plugin, dev);
	if (rc != CHANNEL_RC_OK)
		free(dev);
	return rc;
}

void audio_set_delay(UINT16 ms)
{
	if (!(g_audio_flags & JRDP_AUDIO_PLAYBACK))
		return;
	InterlockedExchange(&g_latency_ms, ms > JRDP_AUDIO_DELAY_MAX_MS ? (LONG)JRDP_AUDIO_DELAY_MAX_MS : (LONG)ms);
}

void audio_mic_data(UINT32 streamId, const BYTE* data, size_t len)
{
	if (!(g_audio_flags & JRDP_AUDIO_MIC) || !data || len == 0 || len > JRDP_AUDIO_MAX_CHUNK)
		return;
	EnterCriticalSection(&g_mic_lock);
	const size_t frame = (size_t)g_mic_format.nChannels * 2;
	if (g_mic_receive && g_mic_stream && streamId == g_mic_stream && frame &&
	    len % frame == 0)
	{
		BYTE* copy = malloc(len);
		if (copy)
		{
			memcpy(copy, data, len);
			if (g_mic_count == MIC_QUEUE_MAX)
			{
				/* The oldest goes: late microphone audio is worth less than current audio. */
				MicPacket* old = &g_mic_queue[g_mic_head];
				free(old->data);
				old->data = NULL;
				g_mic_head = (g_mic_head + 1) % MIC_QUEUE_MAX;
				g_mic_count--;
				g_mic_dropped++;
				if (g_mic_dropped == 1)
					fprintf(stderr, "jterm-rdp-helper: microphone: falling behind, dropping packets\n");
			}
			MicPacket* p = &g_mic_queue[(g_mic_head + g_mic_count) % MIC_QUEUE_MAX];
			p->streamId = streamId;
			p->len = len;
			p->data = copy;
			g_mic_count++;
			SetEvent(g_mic_event);
		}
	}
	LeaveCriticalSection(&g_mic_lock);
}

HANDLE audio_mic_event(void)
{
	return g_mic_event;
}

void audio_mic_process(void)
{
	EnterCriticalSection(&g_mic_lock);
	while (g_mic_count)
	{
		MicPacket p = g_mic_queue[g_mic_head];
		g_mic_queue[g_mic_head].data = NULL;
		g_mic_head = (g_mic_head + 1) % MIC_QUEUE_MAX;
		g_mic_count--;
		if (g_mic_receive && p.streamId == g_mic_stream)
		{
			/* Under the lock, so Close (and audin freeing g_mic_user after it) waits. The write
			 * only queues the data for the RDP thread; it does not wait for the network. */
			const UINT rc = g_mic_receive(&g_mic_format, p.data, p.len, g_mic_user);
			if (rc != CHANNEL_RC_OK)
				fprintf(stderr, "jterm-rdp-helper: microphone: sending failed (%" PRIu32 ")\n", rc);
		}
		free(p.data);
	}
	g_mic_head = 0;
	LeaveCriticalSection(&g_mic_lock);
}

BOOL audio_addin_entry(LPCSTR pszName, LPCSTR pszSubsystem, PVIRTUALCHANNELENTRY* entry)
{
	/* Only the device subsystems: the channels themselves are loaded with no subsystem. */
	if (!pszName || !pszSubsystem || !*pszSubsystem)
		return FALSE;
	const BOOL rdpsnd = strcmp(pszName, RDPSND_CHANNEL_NAME) == 0;
	const BOOL audin = strcmp(pszName, AUDIN_CHANNEL_NAME) == 0;
	if (!rdpsnd && !audin)
		return FALSE;
	/* FreeRDP's do-nothing output, which it loads by itself whenever device redirection is on and
	 * no sound was asked for. It opens nothing. */
	if (rdpsnd && strcmp(pszSubsystem, "fake") == 0)
		return FALSE;
	*entry = NULL;
	if (rdpsnd && strcmp(pszSubsystem, "jterm") == 0 && (g_audio_flags & JRDP_AUDIO_PLAYBACK))
		*entry = WINPR_FUNC_PTR_CAST(&js_entry, PVIRTUALCHANNELENTRY);
	if (audin && strcmp(pszSubsystem, "jterm") == 0 && (g_audio_flags & JRDP_AUDIO_MIC))
		*entry = WINPR_FUNC_PTR_CAST(&mic_entry, PVIRTUALCHANNELENTRY);
	/* Every other backend (pulse, alsa, winmm, mac, ...) and every audin backend: nothing. */
	return TRUE;
}

BOOL audio_apply_connect(rdpSettings* s, UINT8 flags, UINT16 latencyMs)
{
	g_audio_flags = flags & (JRDP_AUDIO_PLAYBACK | JRDP_AUDIO_MIC | JRDP_AUDIO_AAC);
	if (!(g_audio_flags & JRDP_AUDIO_PLAYBACK))
		g_audio_flags &= (UINT8)~JRDP_AUDIO_AAC;
	InterlockedExchange(&g_latency_ms, latencyMs > JRDP_AUDIO_DELAY_MAX_MS ? (LONG)JRDP_AUDIO_DELAY_MAX_MS
	                                                                       : (LONG)latencyMs);
	/* Before the server is contacted (the sandbox design's lockdown point comes after this). */
	g_aac = (g_audio_flags & JRDP_AUDIO_AAC) ? aac_probe() != 0 : FALSE;

	BOOL ok = TRUE;
	if (g_audio_flags & JRDP_AUDIO_PLAYBACK)
	{
		/* Both channels, each with our subsystem named, so nothing depends on the order of
		 * FreeRDP's compiled-in backends. freerdp_client_load_addins adds the same names later only
		 * if missing. Without the flag AudioPlayback stays FALSE: no channel from us. */
		const char* const args[] = { RDPSND_CHANNEL_NAME, "sys:jterm" };
		ok = freerdp_settings_set_bool(s, FreeRDP_AudioPlayback, TRUE) &&
		     freerdp_settings_set_bool(s, FreeRDP_RemoteConsoleAudio, FALSE) &&
		     freerdp_settings_set_bool(s, FreeRDP_DeviceRedirection, TRUE) &&
		     freerdp_client_add_static_channel(s, ARRAYSIZE(args), args) &&
		     freerdp_client_add_dynamic_channel(s, ARRAYSIZE(args), args);
	}
	if (ok && (g_audio_flags & JRDP_AUDIO_MIC))
	{
		/* "format:1" is audin's own fixed-format argument (WAVE_FORMAT_PCM): only PCM is offered to
		 * the server, so no encoder ever runs on the microphone's samples. */
		const char* const args[] = { AUDIN_CHANNEL_NAME, "sys:jterm", "format:1" };
		ok = freerdp_settings_set_bool(s, FreeRDP_AudioCapture, TRUE) &&
		     freerdp_client_add_dynamic_channel(s, ARRAYSIZE(args), args);
	}
	return ok;
}
