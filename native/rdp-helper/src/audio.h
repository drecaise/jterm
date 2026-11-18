/*
 * jterm-rdp-helper: sound forwarding (rdpsnd playback, audin microphone).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef JTERM_RDP_AUDIO_H
#define JTERM_RDP_AUDIO_H

#include <winpr/wtypes.h>
#include <freerdp/settings.h>
#include <freerdp/svc.h>

/* Process start, before anything else here is used. */
void audio_init(void);

/* Reads CONNECT's audio part (protocol v10: u8 audioFlags, u16 audioLatencyMs; absent = 0, no
 * sound) and loads the channels it asks for. FALSE on a settings failure. */
BOOL audio_apply_connect(rdpSettings* s, UINT8 flags, UINT16 latencyMs);

/* The add-in provider's answer for the audio channels' device subsystems. Returns TRUE when the
 * request was about a sound device, with *entry set to jterm's device or NULL (refused); FALSE
 * when it is for someone else (the channel itself, or any other add-in). */
BOOL audio_addin_entry(LPCSTR pszName, LPCSTR pszSubsystem, PVIRTUALCHANNELENTRY* entry);

/* Stdin reader: AUDIO_DELAY. The render delay reported to the server from the next wave on. */
void audio_set_delay(UINT16 ms);

/* Stdin reader: MIC_DATA. Checks it (flag, open stream, id, size, whole frames) and queues a copy
 * (16 packets, the oldest dropped); never calls the channel. */
void audio_mic_data(UINT32 streamId, const BYTE* data, size_t len);

/* Signalled while microphone packets are queued; the RDP thread waits on it. Manual reset: the
 * caller resets it before audio_mic_process. */
HANDLE audio_mic_event(void);

/* RDP thread: hands every queued packet to audin. */
void audio_mic_process(void);

#endif
