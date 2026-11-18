/*
 * jterm-rdp-helper: AAC decoding with the distribution's libfaad (FAAD2), if installed.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Never bundled, never downloaded: AAC is still patent-licensed, and FAAD2's long advisory history
 * stays with the distribution, which updates it. Not installed means PCM.
 *
 * Loaded with dlopen by ABSOLUTE PATH from a fixed list of system library directories, and only when
 * the file (after resolving its links) and the directories involved belong to root and can't be
 * written by anyone else. Never by bare name (the search path could find a planted copy), never
 * through an environment variable, never from the install directory. The helper holds the password;
 * a library loaded from a user-writable place would run inside it.
 *
 * The FAAD2 types are declared here (no build dependency on its headers). Their layout has been
 * stable across the 2.x series (soname libfaad.so.2); the frame-info buffer handed to the library is
 * oversized as a margin anyway.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "aac.h"

typedef void* NeAACDecHandle;

typedef struct
{
	unsigned char defObjectType;
	unsigned long defSampleRate;
	unsigned char outputFormat;
	unsigned char downMatrix;
	unsigned char useOldADTSFormat;
	unsigned char dontUpSampleImplicitSBR;
} JFaadConfig;

typedef struct
{
	unsigned long bytesconsumed;
	unsigned long samples; /* all channels together */
	unsigned char channels;
	unsigned char error;
	unsigned long samplerate;
	unsigned char rest[256]; /* sbr, object type, channel positions, ps, and margin */
} JFaadFrameInfo;

#define FAAD_FMT_16BIT 1

static struct
{
	NeAACDecHandle (*open)(void);
	JFaadConfig* (*getConfig)(NeAACDecHandle);
	unsigned char (*setConfig)(NeAACDecHandle, JFaadConfig*);
	char (*init2)(NeAACDecHandle, unsigned char*, unsigned long, unsigned long*, unsigned char*);
	void* (*decode)(NeAACDecHandle, JFaadFrameInfo*, unsigned char*, unsigned long);
	void (*close)(NeAACDecHandle);
	char* (*errorMessage)(unsigned char);
} F;

static int g_probed = 0;
static int g_available = 0;

static const char* const k_dirs[] = { "/usr/lib64", "/usr/lib", "/usr/lib/x86_64-linux-gnu",
	                                  "/usr/lib/aarch64-linux-gnu", "/lib64" };

/* Owned by root and writable by nobody else. */
static int root_owned(const char* path, int wantDir)
{
	struct stat st;
	if (stat(path, &st) != 0)
		return 0;
	if (wantDir ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode))
		return 0;
	return st.st_uid == 0 && (st.st_mode & (S_IWGRP | S_IWOTH)) == 0;
}

static int parent_root_owned(const char* file)
{
	char dir[PATH_MAX];
	const size_t n = strlen(file);
	if (n >= sizeof(dir))
		return 0;
	memcpy(dir, file, n + 1);
	char* slash = strrchr(dir, '/');
	if (!slash || slash == dir)
		return 0;
	*slash = '\0';
	return root_owned(dir, 1);
}

/* Set when a libfaad.so.2 was found but refused (ownership, permissions, or dlopen failed). */
static char g_refused[PATH_MAX + 64];

static void* open_trusted(char* chosen, size_t cap)
{
	for (size_t i = 0; i < sizeof(k_dirs) / sizeof(k_dirs[0]); i++)
	{
		char candidate[PATH_MAX];
		char real[PATH_MAX];
		if (snprintf(candidate, sizeof(candidate), "%s/libfaad.so.2", k_dirs[i]) >= (int)sizeof(candidate))
			continue;
		/* The directory the name is in, and the file it finally resolves to, with its directory. */
		if (!realpath(candidate, real))
			continue; /* not there */
		if (!root_owned(k_dirs[i], 1) || !root_owned(real, 0) || !parent_root_owned(real))
		{
			snprintf(g_refused, sizeof(g_refused), "%s (not owned by root, or writable by others)", real);
			continue;
		}
		void* h = dlopen(real, RTLD_NOW | RTLD_LOCAL);
		if (h)
		{
			snprintf(chosen, cap, "%s", real);
			return h;
		}
		snprintf(g_refused, sizeof(g_refused), "%s (could not be loaded)", real);
	}
	return NULL;
}

#define LOAD(field, name)                                         \
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
	char path[PATH_MAX] = { 0 };
	void* lib = open_trusted(path, sizeof(path));
	if (!lib)
	{
		if (g_refused[0])
			fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (refused %s)\n", g_refused);
		else
			fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (libfaad.so.2 not installed)\n");
		return 0;
	}
	LOAD(open, "NeAACDecOpen");
	LOAD(getConfig, "NeAACDecGetCurrentConfiguration");
	LOAD(setConfig, "NeAACDecSetConfiguration");
	LOAD(init2, "NeAACDecInit2");
	LOAD(decode, "NeAACDecDecode");
	LOAD(close, "NeAACDecClose");
	LOAD(errorMessage, "NeAACDecGetErrorMessage");
	g_available = 1;
	fprintf(stderr, "jterm-rdp-helper: AAC decoder available (libfaad, %s)\n", path);
	return 1;
fail:
	memset(&F, 0, sizeof(F));
	dlclose(lib);
	fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (%s lacks a function)\n", path);
	return 0;
}

typedef struct
{
	NeAACDecHandle h;
	uint32_t rate;
	uint16_t channels;
} Dec;

void* aac_open(uint32_t rate, uint16_t channels)
{
	uint8_t asc[2];
	if (!g_available || !aac_audio_specific_config(rate, channels, asc))
		return NULL;
	Dec* d = calloc(1, sizeof(*d));
	if (!d)
		return NULL;
	d->h = F.open();
	if (!d->h)
		goto fail;
	JFaadConfig* cfg = F.getConfig(d->h);
	if (!cfg)
		goto fail;
	cfg->outputFormat = FAAD_FMT_16BIT;
	cfg->downMatrix = 0;
	cfg->dontUpSampleImplicitSBR = 1;
	if (!F.setConfig(d->h, cfg))
		goto fail;
	unsigned long gotRate = 0;
	unsigned char gotChannels = 0;
	if (F.init2(d->h, asc, sizeof(asc), &gotRate, &gotChannels) != 0 || gotRate != rate ||
	    gotChannels != channels)
		goto fail;
	d->rate = rate;
	d->channels = channels;
	return d;
fail:
	if (d->h)
		F.close(d->h);
	free(d);
	return NULL;
}

int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written)
{
	Dec* d = dec;
	*written = 0;
	if (!d || !in || len == 0 || len > 65536)
		return 0;
	JFaadFrameInfo info;
	memset(&info, 0, sizeof(info));
	/* FAAD2 doesn't write to the input; its prototype just isn't const. */
	void* pcm = F.decode(d->h, &info, (unsigned char*)in, (unsigned long)len);
	if (info.error != 0)
		return 0;
	if (!pcm || info.samples == 0)
		return 1; /* no output yet (the decoder's first frame) is not an error */
	if (info.samplerate != d->rate || info.channels != d->channels)
		return 0;
	const size_t bytes = (size_t)info.samples * 2;
	if (bytes > cap)
		return 0;
	memcpy(out, pcm, bytes); /* FAAD2 writes native-endian 16-bit: little-endian on every target */
	*written = bytes;
	return 1;
}

void aac_close(void* dec)
{
	Dec* d = dec;
	if (!d)
		return;
	F.close(d->h);
	free(d);
}
