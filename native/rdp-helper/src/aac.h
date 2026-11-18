/*
 * jterm-rdp-helper: AAC decoding through the operating system's (or distribution's) decoder.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * One interface, one implementation per platform, chosen at compile time: aac_mf.c (Windows, Media
 * Foundation), aac_audiotoolbox.c (macOS), aac_faad.c (Linux, the distribution's libfaad),
 * aac_ffmpeg.c (the Flatpak build: the runtime's FFmpeg, which has no libfaad); aac_none.c is the
 * fallback for anything else. No codec is bundled anywhere: AAC is still patent-licensed.
 *
 * What a Windows 11 server sends (measured, see the audio design): WAVE_FORMAT_AAC_MS with cbSize 0,
 * i.e. no codec configuration at all, and one raw AAC-LC frame (1024 samples, no ADTS header) per
 * wave. The decoders are therefore configured with an AudioSpecificConfig built here from the
 * format's rate and channels, never with bytes from the server.
 *
 * Plain C types only, so the implementations can be tested without FreeRDP.
 */
#ifndef JTERM_RDP_AAC_H
#define JTERM_RDP_AAC_H

#include <stddef.h>
#include <stdint.h>

/* Probes once (at CONNECT, before the server is contacted) and logs "AAC decoder available
 * (<which>)" or "AAC decoder not available". Returns 1 when AAC can be decoded. */
int aac_probe(void);

/* A decoder for AAC-LC at this rate and channel count; NULL on failure (the stream stays silent). */
void* aac_open(uint32_t rate, uint16_t channels);

/* Decodes one raw AAC frame into signed 16-bit little-endian interleaved PCM at the rate and channel
 * count given to aac_open. Writes at most cap bytes; more than that is an error, as is any decoder
 * error or a frame that decodes to another rate or channel count. Returns 1 on success (written may
 * be 0: a decoder's first frame can produce nothing), 0 on error (drop the frame, keep the decoder). */
int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written);

void aac_close(void* dec);

/* MPEG-4 sampling frequency index of a rate, or -1. */
static inline int aac_frequency_index(uint32_t rate)
{
	static const uint32_t rates[] = { 96000, 88200, 64000, 48000, 44100, 32000,
		                              24000, 22050, 16000, 12000, 11025, 8000 };
	for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); i++)
		if (rates[i] == rate)
			return i;
	return -1;
}

/* The two-byte AudioSpecificConfig of AAC-LC (object type 2) at this rate and channel count:
 * 5 bits object type, 4 bits frequency index, 4 bits channel configuration, 3 zero bits
 * (1024-sample frames, no core coder, no extension). 0x12 0x10 for 44.1 kHz stereo. Returns 0 for a
 * rate without an index. */
static inline int aac_audio_specific_config(uint32_t rate, uint16_t channels, uint8_t asc[2])
{
	const int idx = aac_frequency_index(rate);
	if (idx < 0 || channels < 1 || channels > 2)
		return 0;
	asc[0] = (uint8_t)((2u << 3) | ((unsigned)idx >> 1));
	asc[1] = (uint8_t)((((unsigned)idx & 1u) << 7) | ((unsigned)channels << 3));
	return 1;
}

#endif
