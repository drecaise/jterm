/*
 * jterm-rdp-helper: no AAC decoder on this platform; sound stays PCM. Not built on
 * Windows, macOS or Linux, which have their own (aac.h); kept for other systems.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <stdio.h>

#include "aac.h"

int aac_probe(void)
{
	fprintf(stderr, "jterm-rdp-helper: AAC decoder not available (none built for this system)\n");
	return 0;
}

void* aac_open(uint32_t rate, uint16_t channels)
{
	(void)rate;
	(void)channels;
	return NULL;
}

int aac_decode(void* dec, const uint8_t* in, size_t len, uint8_t* out, size_t cap, size_t* written)
{
	(void)dec;
	(void)in;
	(void)len;
	(void)out;
	(void)cap;
	if (written)
		*written = 0;
	return 0;
}

void aac_close(void* dec)
{
	(void)dec;
}
