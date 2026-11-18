/*
 * jterm-rdp-helper: AAC decoding with the Flatpak runtime's FFmpeg (libavcodec's own "aac" decoder).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Built only for the Flatpak (CMake option JTERM_AAC_FFMPEG, set by the manifest). The runtime has
 * no libfaad, but its base libavcodec carries FFmpeg's native AAC decoder, and the runtime is the
 * Flatpak's system: Flathub builds it and ships its security updates, so nothing is bundled.
 *
 * Loaded with dlopen by ABSOLUTE PATH, at CONNECT and only for a session that plays sound with AAC
 * allowed: libavcodec pulls in some 80 libraries (X11, glib, librsvg among them), which a session
 * without AAC has no reason to map. The soname major comes from the SDK headers the helper is built
 * against, and the struct layouts used here are stable within a major, so a runtime update that
 * changes the major leaves AAC off (logged) until the next build against it.
 *
 * The libavutil functions are looked up through libavcodec's handle, i.e. in the copy libavcodec
 * itself was linked to: with the runtime's codecs-extra extension installed, the loader resolves
 * libavcodec's dependencies from there (the same FFmpeg build), and a separately opened base
 * libavutil was a second copy whose log level the decoder never read.
 *
 * Trust: the file must be in the runtime's library directory and on a read-only mount. Root
 * ownership, the rule for libfaad on a distribution, can't apply here: a per-user Flatpak
 * installation's files belong to the user, like the helper itself. The runtime's /usr is mounted
 * read-only inside the sandbox, and only there do the files keep the runtime's contents.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>

#include "aac.h"

#define STR_(x) #x
#define STR(x) STR_(x)

static struct
{
	const AVCodec* (*find_decoder)(enum AVCodecID);
	AVCodecContext* (*alloc_context3)(const AVCodec*);
	int (*open2)(AVCodecContext*, const AVCodec*, AVDictionary**);
	int (*send_packet)(AVCodecContext*, const AVPacket*);
	int (*receive_frame)(AVCodecContext*, AVFrame*);
	void (*free_context)(AVCodecContext**);
	AVPacket* (*packet_alloc)(void);
	void (*packet_free)(AVPacket**);
	AVFrame* (*frame_alloc)(void);
	void (*frame_free)(AVFrame**);
	void (*frame_unref)(AVFrame*);
	void* (*mallocz)(size_t);
	void (*channel_layout_default)(AVChannelLayout*, int);
	void (*log_set_level)(int);
} F;

static int g_probed = 0;
static int g_available = 0;
static const AVCodec* g_codec = NULL;

#if defined(__x86_64__)
#define LIBDIR "/usr/lib/x86_64-linux-gnu"
#elif defined(__aarch64__)
#define LIBDIR "/usr/lib/aarch64-linux-gnu"
#else
#define LIBDIR "/usr/lib"
#endif

/* Set when a library was found but refused or failed to load. */
static char g_refused[PATH_MAX + 64];

/* A regular file inside LIBDIR (after resolving links) on a read-only mount. */
static void* open_trusted(const char* name)
{
	char candidate[PATH_MAX];
	char real[PATH_MAX];
	if (snprintf(candidate, sizeof(candidate), "%s/%s", LIBDIR, name) >= (int)sizeof(candidate))
		return NULL;
	if (!realpath(candidate, real))
		return NULL; /* not there */
	struct stat st;
	struct statvfs vfs;
	if (strncmp(real, LIBDIR "/", strlen(LIBDIR) + 1) != 0 || stat(real, &st) != 0 || !S_ISREG(st.st_mode) ||
	    statvfs(real, &vfs) != 0 || !(vfs.f_flag & ST_RDONLY))
	{
		snprintf(g_refused, sizeof(g_refused), "%s (outside the runtime, or not on a read-only mount)", real);
		return NULL;
	}
	void* h = dlopen(real, RTLD_NOW | RTLD_LOCAL);
	if (!h)
		snprintf(g_refused, sizeof(g_refused), "%s (could not be loaded)", real);
	return h;
}

#define LOAD(lib, field, name)                                    \
	do                                                            \
	{                                                             \
		*(void**)(&F.field) = dlsym(lib, name);                   \
		if (!F.field)                                             \
			goto fail;                                            \
	} while (0)

int aac_probe(void)
{
	if (g_probed)
		return g_available;
	g_probed = 1;
	if (access("/.flatpak-info", F_OK) != 0)
	{
		fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (not running in the Flatpak)\n");
		return 0;
	}
	void* codec = open_trusted("libavcodec.so." STR(LIBAVCODEC_VERSION_MAJOR));
	if (!codec)
	{
		if (g_refused[0])
			fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (refused %s)\n", g_refused);
		else
			fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (no libavcodec.so.%d in the runtime)\n",
			        LIBAVCODEC_VERSION_MAJOR);
		return 0;
	}
	LOAD(codec, find_decoder, "avcodec_find_decoder");
	LOAD(codec, alloc_context3, "avcodec_alloc_context3");
	LOAD(codec, open2, "avcodec_open2");
	LOAD(codec, send_packet, "avcodec_send_packet");
	LOAD(codec, receive_frame, "avcodec_receive_frame");
	LOAD(codec, free_context, "avcodec_free_context");
	LOAD(codec, packet_alloc, "av_packet_alloc");
	LOAD(codec, packet_free, "av_packet_free");
	LOAD(codec, frame_alloc, "av_frame_alloc");
	LOAD(codec, frame_free, "av_frame_free");
	LOAD(codec, frame_unref, "av_frame_unref");
	LOAD(codec, mallocz, "av_mallocz");
	LOAD(codec, channel_layout_default, "av_channel_layout_default");
	LOAD(codec, log_set_level, "av_log_set_level");
	/* Its messages would go to the helper's stderr, i.e. jterm's log, once per bad frame. */
	F.log_set_level(AV_LOG_FATAL);
	g_codec = F.find_decoder(AV_CODEC_ID_AAC);
	if (!g_codec)
	{
		fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (the runtime's libavcodec has no AAC "
		                "decoder)\n");
		goto fail_quiet;
	}
	g_available = 1;
	fprintf(stderr, "jterm-rdp-helper: AAC decoder available (FFmpeg %s, " LIBDIR "/libavcodec.so.%d)\n",
	        g_codec->name, LIBAVCODEC_VERSION_MAJOR);
	return 1;
fail:
	fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (the runtime's FFmpeg lacks a function)\n");
fail_quiet:
	memset(&F, 0, sizeof(F));
	g_codec = NULL;
	dlclose(codec);
	return 0;
}

typedef struct
{
	AVCodecContext* ctx;
	AVPacket* pkt;
	AVFrame* frame;
	uint32_t rate;
	uint16_t channels;
} Dec;

void aac_close(void* dec)
{
	Dec* d = dec;
	if (!d)
		return;
	if (d->frame)
		F.frame_free(&d->frame);
	if (d->pkt)
		F.packet_free(&d->pkt);
	if (d->ctx)
		F.free_context(&d->ctx); /* frees extradata too */
	free(d);
}

void* aac_open(uint32_t rate, uint16_t channels)
{
	uint8_t asc[2];
	if (!g_available || !aac_audio_specific_config(rate, channels, asc))
		return NULL;
	Dec* d = calloc(1, sizeof(*d));
	if (!d)
		return NULL;
	d->ctx = F.alloc_context3(g_codec);
	d->pkt = F.packet_alloc();
	d->frame = F.frame_alloc();
	if (!d->ctx || !d->pkt || !d->frame)
		goto fail;
	/* The AudioSpecificConfig built from rate and channels, never bytes from the server (aac.h). */
	d->ctx->extradata = F.mallocz(sizeof(asc) + AV_INPUT_BUFFER_PADDING_SIZE);
	if (!d->ctx->extradata)
		goto fail;
	memcpy(d->ctx->extradata, asc, sizeof(asc));
	d->ctx->extradata_size = (int)sizeof(asc);
	d->ctx->sample_rate = (int)rate;
	F.channel_layout_default(&d->ctx->ch_layout, channels);
	d->ctx->thread_count = 1;
	if (F.open2(d->ctx, g_codec, NULL) < 0)
		goto fail;
	d->rate = rate;
	d->channels = channels;
	return d;
fail:
	aac_close(d);
	return NULL;
}

static int16_t to_s16(float v)
{
	if (!(v > -1.0f)) /* also NaN */
		return v != v ? 0 : INT16_MIN;
	if (v >= 1.0f)
		return INT16_MAX;
	return (int16_t)(v * 32767.0f);
}

/* One sample of channel c at index i, whatever layout the decoder chose. */
static int16_t sample_at(const AVFrame* f, int fmt, int ch, int c, int i)
{
	switch (fmt)
	{
		case AV_SAMPLE_FMT_FLTP:
			return to_s16(((const float*)f->extended_data[c])[i]);
		case AV_SAMPLE_FMT_FLT:
			return to_s16(((const float*)f->extended_data[0])[i * ch + c]);
		case AV_SAMPLE_FMT_S16P:
			return ((const int16_t*)f->extended_data[c])[i];
		case AV_SAMPLE_FMT_S16:
			return ((const int16_t*)f->extended_data[0])[i * ch + c];
		default:
			return 0; /* not reached: checked by the caller */
	}
}

int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written)
{
	Dec* d = dec;
	*written = 0;
	if (!d || !in || len == 0 || len > 65536)
		return 0;
	/* libavcodec may read up to AV_INPUT_BUFFER_PADDING_SIZE past the end: copy into a padded buffer. */
	uint8_t buf[65536 + AV_INPUT_BUFFER_PADDING_SIZE];
	memcpy(buf, in, len);
	memset(buf + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
	d->pkt->data = buf;
	d->pkt->size = (int)len;
	const int sent = F.send_packet(d->ctx, d->pkt);
	d->pkt->data = NULL;
	d->pkt->size = 0;
	if (sent < 0)
		return 0;
	int ok = 1;
	size_t total = 0;
	for (;;)
	{
		const int r = F.receive_frame(d->ctx, d->frame);
		if (r == AVERROR(EAGAIN) || r == AVERROR_EOF)
			break; /* no (more) output for this packet: not an error */
		if (r < 0)
		{
			ok = 0;
			break;
		}
		const AVFrame* f = d->frame;
		const int fmt = f->format;
		const int ch = f->ch_layout.nb_channels;
		if (f->sample_rate != (int)d->rate || ch != d->channels || f->nb_samples < 0 ||
		    (fmt != AV_SAMPLE_FMT_FLTP && fmt != AV_SAMPLE_FMT_FLT && fmt != AV_SAMPLE_FMT_S16P &&
		     fmt != AV_SAMPLE_FMT_S16))
		{
			ok = 0;
			F.frame_unref(d->frame);
			break;
		}
		const size_t bytes = (size_t)f->nb_samples * (size_t)ch * 2;
		if (bytes > cap - total)
		{
			ok = 0;
			F.frame_unref(d->frame);
			break;
		}
		uint8_t* o = out + total; /* not necessarily aligned; little-endian on every target */
		for (int i = 0; i < f->nb_samples; i++)
			for (int c = 0; c < ch; c++, o += 2)
			{
				const int16_t v = sample_at(f, fmt, ch, c, i);
				memcpy(o, &v, 2);
			}
		total += bytes;
		F.frame_unref(d->frame);
	}
	if (!ok)
		return 0;
	*written = total;
	return 1;
}
