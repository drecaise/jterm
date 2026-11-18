/*
 * jterm-rdp-helper: AAC decoding with macOS's own decoder (AudioToolbox's AudioConverter).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Part of the OS, so --expect-aac holds for every macOS build. The converter takes one raw AAC-LC
 * frame per call (what a Windows server sends, no ADTS header) and is configured through its
 * decompression magic cookie: an MPEG-4 elementary stream descriptor (ES_Descriptor, ISO 14496-1)
 * wrapping the AudioSpecificConfig built in aac.h from the format's rate and channels. As on the
 * other systems, no byte of the configuration comes from the server, and jterm parses none of the
 * server's bytes itself.
 */
#include <AudioToolbox/AudioToolbox.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "aac.h"

static int g_probed = 0;
static int g_available = 0;

/* Returned by the input callback once the frame has been handed over: "no more input for now". */
#define J_NO_MORE_INPUT ((OSStatus)0x6A6E6D69) /* 'jnmi' */

typedef struct
{
	AudioConverterRef conv;
	uint32_t rate;
	uint16_t channels;
	/* The frame being decoded, for the input callback. */
	const uint8_t* in;
	UInt32 inLen;
	int handedOver;
	AudioStreamPacketDescription packet;
} Dec;

/* An expandable-size descriptor header: tag, then the size in four 7-bit groups. */
static size_t put_descriptor(uint8_t* p, uint8_t tag, uint32_t size)
{
	p[0] = tag;
	p[1] = (uint8_t)(((size >> 21) & 0x7F) | 0x80);
	p[2] = (uint8_t)(((size >> 14) & 0x7F) | 0x80);
	p[3] = (uint8_t)(((size >> 7) & 0x7F) | 0x80);
	p[4] = (uint8_t)(size & 0x7F);
	return 5;
}

/* ES_Descriptor { ES_ID 0, no flags, DecoderConfigDescriptor { MPEG-4 audio (0x40), audio stream,
 * no buffer size or bit rates, DecoderSpecificInfo { AudioSpecificConfig } } }. 36 bytes. */
static UInt32 build_cookie(const uint8_t asc[2], uint8_t cookie[64])
{
	size_t n = 0;
	const uint32_t dsi = 2;
	const uint32_t dcd = 13 + 5 + dsi;
	const uint32_t esd = 3 + 5 + dcd;
	n += put_descriptor(cookie + n, 0x03, esd);
	cookie[n++] = 0x00; /* ES_ID */
	cookie[n++] = 0x00;
	cookie[n++] = 0x00; /* flags */
	n += put_descriptor(cookie + n, 0x04, dcd);
	cookie[n++] = 0x40; /* objectTypeIndication: MPEG-4 audio */
	cookie[n++] = 0x15; /* streamType audio (5) << 2 | reserved 1 */
	memset(cookie + n, 0, 3 + 4 + 4); /* bufferSizeDB, maxBitrate, avgBitrate */
	n += 11;
	n += put_descriptor(cookie + n, 0x05, dsi);
	cookie[n++] = asc[0];
	cookie[n++] = asc[1];
	return (UInt32)n;
}

static AudioConverterRef create_converter(uint32_t rate, uint16_t channels)
{
	uint8_t asc[2];
	if (!aac_audio_specific_config(rate, channels, asc))
		return NULL;
	AudioStreamBasicDescription in = { 0 };
	in.mSampleRate = rate;
	in.mFormatID = kAudioFormatMPEG4AAC;
	in.mFormatFlags = kMPEG4Object_AAC_LC;
	in.mFramesPerPacket = 1024;
	in.mChannelsPerFrame = channels;
	AudioStreamBasicDescription out = { 0 };
	out.mSampleRate = rate;
	out.mFormatID = kAudioFormatLinearPCM;
	out.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
	out.mBitsPerChannel = 16;
	out.mChannelsPerFrame = channels;
	out.mBytesPerFrame = channels * 2u;
	out.mFramesPerPacket = 1;
	out.mBytesPerPacket = channels * 2u;
	AudioConverterRef conv = NULL;
	if (AudioConverterNew(&in, &out, &conv) != noErr || !conv)
		return NULL;
	uint8_t cookie[64];
	const UInt32 cookieLen = build_cookie(asc, cookie);
	if (AudioConverterSetProperty(conv, kAudioConverterDecompressionMagicCookie, cookieLen, cookie) !=
	    noErr)
	{
		AudioConverterDispose(conv);
		return NULL;
	}
	return conv;
}

int aac_probe(void)
{
	if (g_probed)
		return g_available;
	g_probed = 1;
	AudioConverterRef conv = create_converter(44100, 2); /* the format Windows sends */
	if (!conv)
	{
		fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (AudioToolbox refused AAC)\n");
		return 0;
	}
	AudioConverterDispose(conv);
	g_available = 1;
	fprintf(stderr, "jterm-rdp-helper: AAC decoder available (AudioToolbox)\n");
	return 1;
}

void* aac_open(uint32_t rate, uint16_t channels)
{
	if (!g_available)
		return NULL;
	Dec* d = calloc(1, sizeof(*d));
	if (!d)
		return NULL;
	d->conv = create_converter(rate, channels);
	if (!d->conv)
	{
		free(d);
		return NULL;
	}
	d->rate = rate;
	d->channels = channels;
	return d;
}

/* Hands the converter the one frame of this call, then says there is no more for now. */
static OSStatus supply(AudioConverterRef conv, UInt32* ioPackets, AudioBufferList* ioData,
                       AudioStreamPacketDescription** outDesc, void* user)
{
	(void)conv;
	Dec* d = user;
	if (d->handedOver)
	{
		*ioPackets = 0;
		ioData->mBuffers[0].mData = NULL;
		ioData->mBuffers[0].mDataByteSize = 0;
		return J_NO_MORE_INPUT;
	}
	d->handedOver = 1;
	*ioPackets = 1;
	ioData->mNumberBuffers = 1;
	ioData->mBuffers[0].mNumberChannels = d->channels;
	ioData->mBuffers[0].mData = (void*)d->in; /* not written by the converter */
	ioData->mBuffers[0].mDataByteSize = d->inLen;
	if (outDesc)
	{
		d->packet.mStartOffset = 0;
		d->packet.mVariableFramesInPacket = 0;
		d->packet.mDataByteSize = d->inLen;
		*outDesc = &d->packet;
	}
	return noErr;
}

int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written)
{
	Dec* d = dec;
	*written = 0;
	if (!d || !in || len == 0 || len > 65536)
		return 0;
	const UInt32 frameBytes = d->channels * 2u;
	UInt32 frames = (UInt32)(cap / frameBytes);
	if (frames == 0)
		return 0;
	d->in = in;
	d->inLen = (UInt32)len;
	d->handedOver = 0;
	AudioBufferList list;
	list.mNumberBuffers = 1;
	list.mBuffers[0].mNumberChannels = d->channels;
	list.mBuffers[0].mDataByteSize = frames * frameBytes;
	list.mBuffers[0].mData = out;
	const OSStatus st = AudioConverterFillComplexBuffer(d->conv, supply, d, &frames, &list, NULL);
	if (st != noErr && st != J_NO_MORE_INPUT)
	{
		AudioConverterReset(d->conv);
		return 0;
	}
	const size_t bytes = (size_t)frames * frameBytes;
	if (bytes > cap)
		return 0;
	*written = bytes;
	return 1;
}

void aac_close(void* dec)
{
	Dec* d = dec;
	if (!d)
		return;
	AudioConverterDispose(d->conv);
	free(d);
}
