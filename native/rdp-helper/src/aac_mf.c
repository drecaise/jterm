/*
 * jterm-rdp-helper: AAC decoding with Windows' own decoder (Media Foundation's Microsoft AAC decoder
 * MFT). Part of Windows; absent on "N" editions without the Media Feature Pack and on Server without
 * the Media Foundation feature, which then means PCM.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Synchronous use of the MFT (ProcessInput / ProcessOutput); the caller (the sound device's PlayEx,
 * on rdpsnd's play thread) hands it one raw AAC-LC frame at a time. The input type describes raw AAC
 * (payload type 0) with the AudioSpecificConfig built in aac.h from the format's rate and channels:
 * MF_MT_USER_DATA is the part of HEAACWAVEINFO after its WAVEFORMATEX, followed by that config. No
 * byte of it comes from the server.
 *
 * MFStartup uses MFSTARTUP_LITE: no sockets (the sandbox design wants none). COM is joined as MTA on
 * each thread that uses the decoder, and left joined: those threads live as long as the process.
 */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <initguid.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "aac.h"
#include "sandbox.h"

/* CLSID_CMSAACDecMFT (wmcodecdsp.h), declared here so no extra SDK header is needed. */
DEFINE_GUID(J_CLSID_CMSAACDecMFT, 0x32d186a7, 0x218f, 0x4c75, 0x88, 0x76, 0xdd, 0x77, 0x27, 0x3a, 0x89,
            0x99);

static int g_probed = 0;
static int g_available = 0;

typedef struct
{
	IMFTransform* mft;
	uint32_t rate;
	uint16_t channels;
	DWORD outBufferSize;
	LONGLONG time; /* 100 ns units, for the input samples' timestamps */
} Dec;

static void join_com(void)
{
	/* S_FALSE (already joined) and RPC_E_CHANGED_MODE (an STA thread) both leave COM usable here. */
	const HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
	(void)hr;
}

static HRESULT set_types(IMFTransform* mft, uint32_t rate, uint16_t channels)
{
	uint8_t asc[2];
	if (!aac_audio_specific_config(rate, channels, asc))
		return E_INVALIDARG;
	/* HEAACWAVEINFO after the WAVEFORMATEX: wPayloadType 0 (raw), wAudioProfileLevelIndication 0xFE
	 * (unspecified), wStructType 0, wReserved1, dwReserved2; then the AudioSpecificConfig. */
	BYTE user[12 + 2] = { 0 };
	user[2] = 0xFE;
	user[12] = asc[0];
	user[13] = asc[1];

	IMFMediaType* in = NULL;
	IMFMediaType* out = NULL;
	HRESULT hr = MFCreateMediaType(&in);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetGUID(in, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetGUID(in, &MF_MT_SUBTYPE, &MFAudioFormat_AAC);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(in, &MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(in, &MF_MT_AUDIO_NUM_CHANNELS, channels);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(in, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(in, &MF_MT_AAC_PAYLOAD_TYPE, 0);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(in, &MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0xFE);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetBlob(in, &MF_MT_USER_DATA, user, sizeof(user));
	if (SUCCEEDED(hr))
		hr = IMFTransform_SetInputType(mft, 0, in, 0);

	if (SUCCEEDED(hr))
		hr = MFCreateMediaType(&out);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetGUID(out, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetGUID(out, &MF_MT_SUBTYPE, &MFAudioFormat_PCM);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(out, &MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(out, &MF_MT_AUDIO_NUM_CHANNELS, channels);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(out, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(out, &MF_MT_AUDIO_BLOCK_ALIGNMENT, channels * 2u);
	if (SUCCEEDED(hr))
		hr = IMFMediaType_SetUINT32(out, &MF_MT_AUDIO_AVG_BYTES_PER_SECOND, rate * channels * 2u);
	if (SUCCEEDED(hr))
		hr = IMFTransform_SetOutputType(mft, 0, out, 0);

	if (in)
		IMFMediaType_Release(in);
	if (out)
		IMFMediaType_Release(out);
	return hr;
}

static IMFTransform* create_decoder(uint32_t rate, uint16_t channels)
{
	IMFTransform* mft = NULL;
	HRESULT hr = CoCreateInstance(&J_CLSID_CMSAACDecMFT, NULL, CLSCTX_INPROC_SERVER, &IID_IMFTransform,
	                              (void**)&mft);
	if (FAILED(hr) || !mft)
		return NULL;
	hr = set_types(mft, rate, channels);
	if (SUCCEEDED(hr))
		hr = IMFTransform_ProcessMessage(mft, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
	if (SUCCEEDED(hr))
		hr = IMFTransform_ProcessMessage(mft, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
	if (FAILED(hr))
	{
		IMFTransform_Release(mft);
		return NULL;
	}
	return mft;
}

static void probe(void* arg)
{
	(void)arg;
	join_com();
	if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE)))
	{
		fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (Media Foundation did not start)\n");
		return;
	}
	/* The format Windows sends; a decoder that accepts it will accept the others. */
	IMFTransform* mft = create_decoder(44100, 2);
	if (!mft)
	{
		fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (no Media Foundation AAC decoder: "
		                "a Windows N edition needs the Media Feature Pack)\n");
		return;
	}
	IMFTransform_Release(mft);
	g_available = 1;
	fprintf(stderr, "jterm-rdp-helper: AAC decoder available (Media Foundation)\n");
}

int aac_probe(void)
{
	if (g_probed)
		return g_available;
	g_probed = 1;
	/* Under the lockdown token, which is what the decoders run under once the session starts: in
	 * the restricted child, Media Foundation finds no decoder while the start-up token is
	 * impersonated (GitHub's Windows Server runner), though it works under the lockdown token. */
	sandbox_without_startup_token(probe, NULL);
	return g_available;
}

void* aac_open(uint32_t rate, uint16_t channels)
{
	if (!g_available)
		return NULL;
	join_com(); /* rdpsnd's play thread, not the thread that probed */
	Dec* d = calloc(1, sizeof(*d));
	if (!d)
		return NULL;
	d->mft = create_decoder(rate, channels);
	if (!d->mft)
	{
		free(d);
		return NULL;
	}
	MFT_OUTPUT_STREAM_INFO info = { 0 };
	if (FAILED(IMFTransform_GetOutputStreamInfo(d->mft, 0, &info)) ||
	    (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)))
	{
		/* The Microsoft decoder wants the caller's samples; anything else is not the decoder we
		 * know. */
		IMFTransform_Release(d->mft);
		free(d);
		return NULL;
	}
	/* One AAC frame is 1024 frames of PCM; leave room for more. */
	d->outBufferSize = info.cbSize > 0 ? info.cbSize : 1024u * channels * 2u;
	if (d->outBufferSize < 4096u * channels * 2u)
		d->outBufferSize = 4096u * channels * 2u;
	d->rate = rate;
	d->channels = channels;
	return d;
}

/* Recovers after an error so the next frame decodes from a clean state. */
static void flush(Dec* d)
{
	(void)IMFTransform_ProcessMessage(d->mft, MFT_MESSAGE_COMMAND_FLUSH, 0);
	(void)IMFTransform_ProcessMessage(d->mft, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
}

/* Pulls everything the decoder has ready into out. Returns 1, or 0 on an error or over the cap. */
static int drain(Dec* d, uint8_t* out, size_t cap, size_t* written)
{
	for (;;)
	{
		IMFSample* sample = NULL;
		IMFMediaBuffer* buffer = NULL;
		HRESULT hr = MFCreateSample(&sample);
		if (SUCCEEDED(hr))
			hr = MFCreateMemoryBuffer(d->outBufferSize, &buffer);
		if (SUCCEEDED(hr))
			hr = IMFSample_AddBuffer(sample, buffer);
		if (FAILED(hr))
		{
			if (buffer)
				IMFMediaBuffer_Release(buffer);
			if (sample)
				IMFSample_Release(sample);
			return 0;
		}
		MFT_OUTPUT_DATA_BUFFER od = { 0 };
		od.pSample = sample;
		DWORD status = 0;
		hr = IMFTransform_ProcessOutput(d->mft, 0, 1, &od, &status);
		if (od.pEvents)
			IMFCollection_Release(od.pEvents);
		int ok = 1;
		int more = 0;
		if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT)
			ok = 1;
		else if (FAILED(hr))
			ok = 0;
		else
		{
			BYTE* p = NULL;
			DWORD len = 0;
			if (SUCCEEDED(IMFMediaBuffer_Lock(buffer, &p, NULL, &len)))
			{
				if (*written + len > cap)
					ok = 0; /* more than the caller allows for one wave */
				else
				{
					memcpy(out + *written, p, len);
					*written += len;
				}
				(void)IMFMediaBuffer_Unlock(buffer);
			}
			else
				ok = 0;
			more = ok;
		}
		IMFMediaBuffer_Release(buffer);
		IMFSample_Release(sample);
		if (!ok || !more)
			return ok;
	}
}

int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written)
{
	Dec* d = dec;
	*written = 0;
	if (!d || !in || len == 0 || len > 65536)
		return 0;
	IMFSample* sample = NULL;
	IMFMediaBuffer* buffer = NULL;
	HRESULT hr = MFCreateMemoryBuffer((DWORD)len, &buffer);
	if (SUCCEEDED(hr))
	{
		BYTE* p = NULL;
		hr = IMFMediaBuffer_Lock(buffer, &p, NULL, NULL);
		if (SUCCEEDED(hr))
		{
			memcpy(p, in, len);
			(void)IMFMediaBuffer_Unlock(buffer);
			hr = IMFMediaBuffer_SetCurrentLength(buffer, (DWORD)len);
		}
	}
	if (SUCCEEDED(hr))
		hr = MFCreateSample(&sample);
	if (SUCCEEDED(hr))
		hr = IMFSample_AddBuffer(sample, buffer);
	const LONGLONG duration = (LONGLONG)1024 * 10000000 / d->rate;
	if (SUCCEEDED(hr))
		hr = IMFSample_SetSampleTime(sample, d->time);
	if (SUCCEEDED(hr))
		hr = IMFSample_SetSampleDuration(sample, duration);
	if (SUCCEEDED(hr))
	{
		hr = IMFTransform_ProcessInput(d->mft, 0, sample, 0);
		if (hr == MF_E_NOTACCEPTING)
		{
			/* Output still pending from before: take it, then offer the frame again. */
			if (drain(d, out, cap, written))
				hr = IMFTransform_ProcessInput(d->mft, 0, sample, 0);
		}
	}
	if (buffer)
		IMFMediaBuffer_Release(buffer);
	if (sample)
		IMFSample_Release(sample);
	d->time += duration;
	if (FAILED(hr) || !drain(d, out, cap, written))
	{
		flush(d);
		*written = 0;
		return 0;
	}
	return 1;
}

void aac_close(void* dec)
{
	Dec* d = dec;
	if (!d)
		return;
	(void)IMFTransform_ProcessMessage(d->mft, MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
	IMFTransform_Release(d->mft);
	free(d);
}
