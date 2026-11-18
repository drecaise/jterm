# RDP sound: playback and microphone

**Status:** designed 2026-11-11; **all four steps built and live-tested** by 2026-11-12 (PCM
playback, microphone, AAC, documentation), on Windows 11 and macOS, against a Windows 11 server and
GNOME Remote Desktop. The design sections below describe what was built; the history of how it got
there, with the measurements, is "Current state" at the end. Decisions D1–D6 were taken by the user; D7–D14 are this
document's own and can still be overruled.
**Builds on:** `2026-11-07-rdp-session-design.md` (the helper and its protocol),
`2026-11-09-rdp-drives-and-file-copy-design.md` (the forwarding pattern and property P6, "the
helper opens nothing local"), `2026-11-11-rdp-helper-sandbox-design.md` (which this design must not
make harder).

## Summary

- **Playback:** sound from the remote desktop plays on this computer. Per session, off by default.
- **Microphone:** this computer's microphone is sent to the remote desktop. Per session, off by
  default, and the user is asked once per connection when the server first opens it.
- **Formats:** uncompressed PCM always; AAC when the operating system can decode it. No codec is
  bundled in any package.

One rule, the same as for drives and the clipboard: **the helper never touches a sound device.**
FreeRDP's own audio backends (PulseAudio, ALSA, WinMM, CoreAudio) are not used and not built. The
helper registers two small forwarding devices; jterm plays and records through Java Sound and
makes every decision about the local microphone in Java.

## What was measured, and what was not

Read in the FreeRDP 3.33.0 source (`/home/mark/frdp/src`, the pinned version):

- `rdpsnd` (playback) loads its output device through `freerdp_load_channel_addin_entry("rdpsnd",
  <subsystem>, NULL, flags)`, which goes through the add-in provider the helper already installs
  (`j_addin_provider`). The device is a struct of callbacks (`include/freerdp/client/rdpsnd.h`).
  `audin` (microphone) works the same way with `IAudinDevice` (`include/freerdp/client/audin.h`).
- The bundled FreeRDP is built with **no audio codec** (`WITH_FAAD2`, `WITH_FDK_AAC`, `WITH_GSM`,
  `WITH_LAME`, `WITH_OPUS`, `WITH_SOXR`, `WITH_DSP_FFMPEG` all off), so
  `freerdp_dsp_supports_format` answers true for PCM only.
- `rdpsnd` offers the server every server format for which
  `freerdp_dsp_supports_format(format) || device->FormatSupported(format)` holds. So **every PCM
  variant the server lists is offered**, whatever the device says, and a compressed format is
  offered exactly when the device claims it. A wave in a device-supported format reaches the
  device raw through `PlayEx(device, format, data, size)`.
- **A failing `Open` ends sound for the session**: the error leaves `rdpsnd`'s play thread
  (`play_thread` returns). The device must never fail `Open`.
- `SNDC_CLOSE` does not call the device's `Close` (it only logs). `Close` comes on a format change
  and at disconnect.
- `freerdp_client_load_addins` turns `FreeRDP_DeviceRedirection` on whenever `rdpsnd` is loaded
  ("rdpsnd requires rdpdr to be registered"), and adds the static *and* the dynamic `rdpsnd`
  channel when `FreeRDP_AudioPlayback` is set. Without a `sys:` argument `rdpsnd` tries its
  compiled-in backends in order, ending with `fake`.
- **FreeRDP's `WITH_WINMM` defaults to on**, so before this work a WinMM playback and capture
  backend was compiled into the Windows libraries (unused, because no audio channel was loaded).
  Step 1 turned it off in `build-freerdp-windows.cmd`.
- `audin`'s device gets `SetFormat(format, framesPerPacket)`, then `Open(receive, userData)`, and
  calls `receive(format, data, size, userData)` from its own thread; FreeRDP's backends all do
  this from a capture thread. When the device supports the negotiated format, the bytes are sent
  as they are (no encoder).

Measured on this machine (Rocky 10, PipeWire, OpenJDK 21.0.12) with a throwaway Java Sound probe:
the default mixer plays and captures 16-bit PCM, mono and stereo, at 8 000, 11 025, 16 000,
22 050, 32 000, 44 100 and 48 000 Hz; a `SourceDataLine` asked for an 80 ms buffer got exactly
that; 500 ms of silence was accepted in 442 ms. The stream shows up in PipeWire as
`alsa_playback.java`.

Measured against the Windows 11 VM (`172.16.1.122`) with the step 1 build, 2026-11-11:

- Windows offers 30 formats: eight `WAVE_FORMAT_AAC_MS` variants first (all 2 ch, 44.1 kHz), then
  **one** PCM, 2 ch 44.1 kHz 16-bit, then ADPCM, IMA ADPCM, A-law and GSM 6.10 at lower rates.
  With only PCM claimed it picks that PCM.
- Sound came on the **static** channel.
- Waves are **32 768 bytes**, i.e. ~186 ms each. That broke the first buffer policy: a byte
  threshold of 60 ms was passed by the first wave alone, so playback started at once and the next
  wave was due exactly as the output ran dry (no margin), and the queue emptying between waves was
  counted as ~650 "underruns" in two minutes although nothing was audible. Now playback also waits
  60 ms after the first byte (`JitterBuffer.START_DELAY_MS`), and an underrun is counted only when
  the output itself has run dry (`AudioSink.queuedMillis`). Retested after that change: plays
  without audible problems.
- **With "Play sound" off, no `rdpsnd` appears in the helper's log**, even with `WLOG_LEVEL=INFO`
  (which shows FreeRDP's "Loaded … backend for rdpsnd"). Reading `freerdp_client_load_addins` had
  suggested otherwise: network auto-detect turns device redirection on, and device redirection
  adds `rdpsnd` with `sys:fake`. The log says that path is not taken here, so A4 holds as far as
  can be seen. Not yet explained from the source; check again if a FreeRDP bump changes it.

Measured with a throwaway Java Sound probe, 2026-11-12:

- **Windows 11 laptop** (OpenJDK 21.0.4, Realtek speakers, a G435 USB/Bluetooth headset, a
  webcam microphone): Java Sound uses DirectSound. "Primary Sound Driver" and "Primary Sound
  Capture Driver" (the Windows default devices) open every listed rate, mono and stereo, for
  playback and capture. A 100 ms playback buffer was granted exactly, and 500 ms of silence was
  written in 422 ms. A 500 ms capture buffer was granted exactly. 3 s of 20 ms reads came as 146
  reads, the slowest **130 ms** (DirectSound delivers in bursts), still under the 200 ms "late"
  limit. The headset microphone at 75 % peaked at 4149/32767.
- **macOS 26.7 VM on Proxmox 9** (Intel, OpenJDK 21.0.13): **no mixer at all**, because macOS has no
  driver for QEMU's sound hardware. `AudioSystem.getSourceDataLine`/`getTargetDataLine` throw
  `IllegalArgumentException` ("No line matching …"), a `RuntimeException` and not
  `LineUnavailableException`. Both `openSink` and `openSource` catch `Exception`, so this is the
  "No sound device" path (tests now cover it). Testing on that VM needs a USB audio device passed
  through, or a virtual CoreAudio device such as BlackHole.
- **The same VM with the G435's USB receiver passed through** (OpenJDK 21.0.13): "Default Audio
  Device" plays every listed rate, mono and stereo, with the 100 ms buffer granted exactly; it
  captures every rate **in mono only**, and a stereo capture line throws `IllegalArgumentException`
  (the headset's microphone is mono). Hence `JavaSoundSource` opens the other channel count when
  the asked one is refused and converts (`ChannelMix`), since the server picks the stream's format.
  Capture: 133 reads of 20 ms in 3 s, slowest 253 ms. Windows (146 reads, slowest 130 ms) shows the
  same thing: the reads add up to less than the wall time (2.66 s here), so the slow read is the
  line's start-up delay, not recurring bursts. In steady state the 200 ms "late" flush doesn't fire.

**AAC from Windows 11**, measured 2026-11-12 with a throwaway spike (the device claimed
`WAVE_FORMAT_AAC_MS`, logged the waves and dropped them):

- The eight AAC formats differ **only in bit rate**: `nAvgBytesPerSec` 24 000, 20 000, 16 000,
  12 000, 8 000, 6 000, 4 000, 2 000 (192 down to 16 kbit/s), all 2 ch, 44.1 kHz,
  `nBlockAlign` 4, and **`cbSize` 0**: no `HEAACWAVEINFO`, no AudioSpecificConfig.
- With AAC claimed, Windows **picks AAC at 12 000 B/s (96 kbit/s)**, against 176 400 B/s for PCM:
  about 1/15 of the bandwidth.
- **Each wave is exactly one raw AAC frame**: 200 waves in 4 626 ms is 23.1 ms each, i.e. 1 024
  samples at 44.1 kHz. Sizes 216–431 bytes while sound plays, 6 bytes for silence.
- **No ADTS header**: every wave starts with `0x21`, which as a raw `raw_data_block()` reads as a
  channel pair element (`001`), tag 0, common window: stereo AAC-LC payload. (Microsoft's AAC
  encoder produces AAC-LC only.) Waves arrive with jitter (two 5 ms apart, then 38 ms).

What that changes in the design:

- **The decoders get their configuration from jterm, not from the format**: an AudioSpecificConfig
  is built from the format, AAC-LC (object type 2), the sampling-frequency index of the rate, and the
  channel count: two bytes, `0x12 0x10` for 44.1 kHz stereo. The device claims AAC only with
  `cbSize == 0` (a server that sends its own configuration gets PCM), so the decoders never parse
  server-supplied codec data.
- **No ADTS parsing anywhere**: macOS's AudioToolbox takes the raw frames with the
  AudioSpecificConfig as its magic cookie. A9's one exception (the ADTS header check) goes; jterm
  adds no C parsing of server bytes at all.
- **A bit-rate switch is a format change for rdpsnd** (`rdpsnd_ensure_device_is_open` compares the
  format number: `Close`, then `Open`). The decoder therefore lives across `Close` and is replaced
  only when the channels or the rate change, so a switch can't click; the helper logs switches.
  Whether Windows switches at all was not seen in this run.

**Measured later** (details under "Current state"): GNOME Remote Desktop's formats and what it
picks; the microphone's format and packet size on both servers; the Media Foundation, AudioToolbox
and libfaad decoders, each run against real frames. **Never measured:** xrdp (skipped by decision);
the dynamic channel (both servers used the static one); a mid-session AAC bit-rate switch.

**AAC and patents.** The sources found estimate the last baseline AAC patent to expire in 2028,
and Via LA still licenses AAC-LC decoders. That is the position H.264 is in, where jterm bundles
no codec, hence D3. FAAD2, the decoder FreeRDP could link, has about 35 CVEs on record.

## Threat model

**The server is untrusted**, or impersonated after a certificate was accepted by mistake. It
controls every format list, every wave's size and content, the volume, when it opens the
microphone and how often. **The helper is untrusted by jterm** as well (sandbox design: "the
message protocol is the only way left to attack jterm").

**What is protected:** what the microphone hears; the JVM's memory and threads; the user's ears
and attention (sound they did not ask for); the helper's own integrity while it parses
compressed audio.

**Properties the design must keep:**

| | Property |
|---|---|
| A1 | The local microphone is opened only when all four hold: the session has the microphone option on, a microphone stream is open, the user allowed it for this connection, and it is not muted. Otherwise the capture line is **closed**, so the operating system's own "microphone in use" indicator is off, and the server gets digital silence generated in Java. |
| A2 | The server cannot cause the microphone prompt more than once per connection, and the prompt defaults to "Don't allow". Only the user can change the answer afterwards (the pane's microphone button). |
| A3 | Recording is always visible: in the pane's bar, in the full-screen bar and on the tab. |
| A4 | With both options off, no audio channel exists: the server cannot tell jterm has sound. |
| A5 | The helper opens no sound device, no sound socket and no sound service. Playback and capture happen in the JVM. The sandbox design's "no Unix sockets" rule stays as it is. |
| A6 | Everything the server or the helper can make jterm hold is capped: one playback line and one capture line per pane, a 64 KiB message, a 500 ms playback queue, formats from a fixed list. |
| A7 | Server-controlled volume can only attenuate. Gain never exceeds unity, and the system volume is never touched. |
| A8 | Compressed audio is parsed only by a decoder the operating system or the distribution supplies and updates, inside the helper process, never in the JVM. A decoder error drops the wave and never ends the session. The decoder can be turned off (Preferences), which leaves PCM. |
| A9 | jterm adds no C parsing of server-controlled bytes (the AAC configuration is built from the format's channels and rate, never read from the server); sizes and formats are re-validated in Java. |

**Not in scope:** a local process recording the speakers; the remote side recording what it plays.

## Architecture

```
                     helper (C, FreeRDP)                               jterm (Java)
server ─ rdpsnd ─▶ rdpsnd core ─▶ jterm sound device ══ AUDIO_* ══▶ RdpAudioPlayer ─▶ SourceDataLine
                                  (PCM as is; AAC through the
                                   OS decoder, then PCM)
server ◀─ audin ── audin core ◀── jterm microphone device ◀═ MIC_DATA ═ RdpMicrophone ◀─ TargetDataLine
                                  (PCM only)                           MicGate (consent, mute)
```

### Protocol v10 (v11 adds `AUDIO_DELAY`)

`protocol.h` and `RdpProtocol` change together; `JRDP_PROTOCOL_VERSION` became 10 with sound and
11 with `AUDIO_DELAY`.

**CONNECT** gains, after the drive list: `u8 audioFlags, u16 audioLatencyMs`.

| Flag | Value | Meaning |
|---|---|---|
| `JRDP_AUDIO_PLAYBACK` | 0x01 | load `rdpsnd`; without it `FreeRDP_AudioPlayback` stays false |
| `JRDP_AUDIO_MIC` | 0x02 | load `audin`; without it `FreeRDP_AudioCapture` stays false |
| `JRDP_AUDIO_AAC` | 0x04 | the sound device may claim AAC if a decoder is found (needs PLAYBACK) |

`audioLatencyMs` is what the sound device reports to FreeRDP as its render delay (rdpsnd adds
`PlayEx`'s return value to the second wave confirmation's timestamp, from which the server works
out the render latency). jterm sends its playback buffer target, 100, as the starting value; from
v11 on `AUDIO_DELAY` replaces it with the measured delay.

**helper → jterm**

| Message | Id | Payload |
|---|---|---|
| `AUDIO_OPEN` | 0x95 | `u32 sampleRate, u8 channels, u8 codec` (0 = PCM from the server, 1 = AAC decoded by the helper; for display only). Samples are always signed 16-bit little-endian, interleaved. |
| `AUDIO_DATA` | 0x96 | bytes: whole frames, at most `JRDP_AUDIO_MAX_CHUNK` (65 536); the helper splits larger waves |
| `AUDIO_CLOSE` | 0x97 | none |
| `AUDIO_VOLUME` | 0x98 | `u16 left, u16 right` (0xFFFF = full) |
| `MIC_OPEN` | 0x99 | `u32 streamId, u32 sampleRate, u8 channels, u32 framesPerPacket` |
| `MIC_CLOSE` | 0x9A | `u32 streamId` |

**jterm → helper**

| Message | Id | Payload |
|---|---|---|
| `MIC_DATA` | 0x10 | `u32 streamId`, bytes: whole frames in the stream's format, at most `JRDP_AUDIO_MAX_CHUNK`. Dropped unless `JRDP_AUDIO_MIC` was set, a stream is open and the id is its id. |
| `AUDIO_DELAY` | 0x11 | `u16 milliseconds` (v11): how long sound now waits in jterm before it is heard. The helper returns it from `PlayEx` from the next wave on; capped at 1 000, ignored without `JRDP_AUDIO_PLAYBACK`. |

`AUDIO_DELAY` carries steering's low point (queue + output just before a wave arrives, i.e. how
long that wave waits before it starts to play). `RdpAudioPlayer` sends it from the play thread
when it has moved by 10 ms or more, so about once a second at most and usually far less. Whether
a server does anything audible with the figure was **not measured**: Windows and GNOME were only
ever run with the constant.

Shared constants: sample rates 8 000, 11 025, 16 000, 22 050, 32 000, 44 100, 48 000; channels 1
or 2; 16 bits. Both sides check them: the helper before announcing a stream, Java again on
receipt. `RdpProtocol.MAX_INBOUND` already covers a 64 KiB message.

### Helper: the sound device (`rdpsnd`, subsystem `jterm`)

New file `native/rdp-helper/src/audio.c` (with `audio.h`), so the 2 200-line main file doesn't
grow; it needs `send_msg` and the `Buf` helpers, which move to a small internal header.

- **Loading.** With `JRDP_AUDIO_PLAYBACK`, `apply_connect` sets `FreeRDP_AudioPlayback` and
  `FreeRDP_DeviceRedirection`, and adds the static and the dynamic channel explicitly with
  `{"rdpsnd", "sys:jterm"}` (`freerdp_client_add_static_channel`,
  `freerdp_client_add_dynamic_channel`), so nothing depends on the order of compiled-in backends.
  `j_addin_provider` answers name `rdpsnd` + subsystem `jterm` with the device entry, passes
  `fake` through (still needed when drives are on and sound is off), and returns `NULL` for every
  other `rdpsnd` or `audin` subsystem. `rdpdr` is then loaded with no devices when no folder is
  shared; the provider already refuses every device service but `drive`.
- **`FormatSupported`**: PCM with 16 bits, 1–2 channels, a listed rate and
  `nBlockAlign == channels * 2`; or, with `JRDP_AUDIO_AAC` and a decoder present,
  `WAVE_FORMAT_AAC_MS` with 1–2 channels, a listed rate and **`cbSize == 0`**. A format that
  carries its own codec configuration is not claimed (the server falls back to PCM): that is what
  keeps A9, since the decoder's configuration is then always built from rate and channels.
- **`Open` always returns TRUE** and only forgets the announced format (see "A failing Open"
  above). `DefaultFormat` and `ServerFormatAnnounce` stay unset, apart from logging the server's
  list once at debug level.
- **`PlayEx` is the one place that decides**, because it alone sees the real format: FreeRDP
  offers PCM variants the device doesn't support (8-bit, more channels) and would then hand over
  bytes it did not convert. `PlayEx` drops a wave whose format fails `FormatSupported`; for a
  supported one it announces `AUDIO_OPEN` when the format differs from the announced one, then
  sends `AUDIO_DATA` (PCM truncated to whole frames; AAC through the decoder first). It returns
  `audioLatencyMs`. `Play` is set too and forwards with the last format.
- **`SetVolume`** sends `AUDIO_VOLUME` (low word left, high word right); `GetVolume` returns the
  last value, initially full.
- **`Close`** sends `AUDIO_CLOSE`. The AAC decoder **outlives it** (a bit-rate switch is `Close` +
  `Open` for rdpsnd, see "AAC from Windows 11") and is replaced only when the rate or the channel
  count changes. **`Free`** closes the decoder its device created, and frees.
- **Two instances.** FreeRDP creates one device for the static channel and one for the dynamic.
  The first to play owns the stream until its `Close`; waves from the other are dropped, so two
  channels can never interleave samples.
- **Threads.** The callbacks run on `rdpsnd`'s play thread. `send_msg` is already serialised by
  `g_write_lock`. A sound message waits behind a frame being written (8 ms for a 2560×1440 frame
  on Windows, measured for the frame work); the Java queue absorbs that.

### Helper: the microphone device (`audin`, subsystem `jterm`)

- **Loading.** With `JRDP_AUDIO_MIC`, `apply_connect` sets `FreeRDP_AudioCapture` and adds the
  dynamic channel `{"audin", "sys:jterm", "format:1"}`. `format:1` is `audin`'s own fixed-format
  argument: only PCM is negotiated, so no encoder is ever needed.
- **`FormatSupported`**: PCM, 16 bits, 1–2 channels, a listed rate.
- **`SetFormat`** stores format and packet size. **`Open`** stores the `receive` callback under
  `g_mic_lock`, takes the next stream id and sends `MIC_OPEN`. It returns at once: consent is
  jterm's business and must not block a channel thread. **`Close`** clears the callback under the
  lock and sends `MIC_CLOSE`.
- **`MIC_DATA`** arrives on the stdin reader thread, which only checks it (flag, open stream, id,
  size, whole frames), puts it on a bounded queue (16 packets, the oldest dropped) and signals an
  event. The **RDP thread** (main loop) takes packets and calls `receive` under `g_mic_lock`, the
  way the clipboard's jobs run. Same rule as the clipboard: don't call a channel from the reader.
  No thread of its own is needed: `receive` ends in `dvcman_write_channel`, which only *queues*
  the PDU and never waits for the network (read in drdynvc; see "Current state", step 2).

### Helper: AAC decoders

One interface in `aac.h` (plain C types, so each implementation can be tested without FreeRDP),
one implementation per platform, chosen at compile time (`aac_none.c` anywhere else):

```c
int   aac_probe(void);                              /* once, at CONNECT, when JRDP_AUDIO_AAC is set */
void* aac_open(uint32_t rate, uint16_t channels);   /* NULL: this stream stays silent */
int   aac_decode(void* dec, const uint8_t* in, size_t len,
                 uint8_t* out, size_t cap, size_t* written);   /* one raw frame in, S16LE out */
void  aac_close(void* dec);
```

Rules for all three: the probe runs before the server is contacted (the sandbox design's
lockdown point comes after it); **the decoder's configuration is the two-byte AudioSpecificConfig
built by `aac_audio_specific_config` from rate and channels, never bytes from the server** (A9);
output is capped at one second of audio per wave, more is an error; an error drops the wave and
counts it (logged for the first and every thousandth); the helper logs `AAC decoder available
(<which>)` or `AAC decoder not available (<why>)` at CONNECT, as it does for H.264.

- **Windows, `aac_mf.c`:** the Microsoft AAC decoder MFT (`CLSID_CMSAACDecMFT`), synchronous
  `ProcessInput`/`ProcessOutput`, `MFSTARTUP_LITE` (no sockets). Input type `MFAudioFormat_AAC`
  with payload type 0 (raw); `MF_MT_USER_DATA` is a zeroed `HEAACWAVEINFO` tail (profile level
  0xFE, unspecified) followed by the built AudioSpecificConfig. Output `MFAudioFormat_PCM`, 16
  bits. Links `mfplat`, `mfuuid`, `ole32`. Absent on Windows N without the Media Feature Pack and
  on Server without Media Foundation: then PCM. It conceals a corrupt frame (23 ms out) rather
  than failing it.
- **Linux, `aac_faad.c`:** the distribution's `libfaad.so.2`, loaded with `dlopen` **by absolute
  path** from a fixed list of system library directories (`/usr/lib64`, `/usr/lib`,
  `/usr/lib/x86_64-linux-gnu`, `/usr/lib/aarch64-linux-gnu`, `/lib64`), and only when the file
  (after resolving links) and the directories involved belong to root and are not writable by
  anyone else; a copy refused for that is logged as refused, not as "not installed". Never by bare
  name, never through an environment variable, never from the install directory (the
  `LD_LIBRARY_PATH` rule of the H.264 work). No version gate: like the system OpenSSL the helper
  already links, it gets the distribution's fixes. Not installed means PCM; jterm never downloads
  or bundles it.
- **Flatpak, `aac_ffmpeg.c`:** the runtime has no `libfaad`, but its base `libavcodec` (FFmpeg,
  LGPL-2.1-or-later) carries FFmpeg's own AAC decoder, and the runtime is the Flatpak's system:
  Flathub builds and updates it. Built only with `-DJTERM_AAC_FFMPEG=ON` (set by the manifest),
  against the SDK's headers, so the soname major and struct layouts match the runtime; a runtime
  with another major leaves AAC off until the next build. Opened at CONNECT, only for a session
  with AAC sound (libavcodec maps some 80 libraries), by absolute path from the runtime's library
  folder, and only from a read-only mount and inside the Flatpak (`/.flatpak-info`). Root ownership
  can't be the rule there: a per-user installation's files belong to the user, like the helper. The
  libavutil functions are taken through libavcodec's handle, because with the `codecs-extra`
  extension installed the loader resolves libavcodec's dependencies from that extension, and a
  separately opened base libavutil was a second copy (its log level never reached the decoder).
  FFmpeg's output is planar float, converted to 16-bit here; its log is set to fatal-only, or each
  bad frame would print to jterm's log.
- **macOS, `aac_audiotoolbox.c`:** `AudioConverter` from `kAudioFormatMPEG4AAC` to linear PCM,
  one raw frame per call. Its magic cookie is an ES_Descriptor wrapping the built
  AudioSpecificConfig. No ADTS parsing: the servers send raw frames.

### Java: playback (`terminal.rdp.audio`, Swing-free)

| Class | Role |
|---|---|
| `PcmFormat` | record (sampleRate, channels); `of(rate, channels)` returns empty outside the allowed list; frame and millisecond arithmetic |
| `JitterBuffer` | bounded byte queue with the policy below; pure, unit-tested |
| `Gain` | scales S16LE samples per channel by 0..0xFFFF; pure, unit-tested |
| `AudioSink` | interface: `open(PcmFormat, bufferMs)`, `write`, `flush`, `close`; `JavaSoundSink` wraps a `SourceDataLine`; tests use a fake |
| `RdpAudioPlayer` | one per connection: takes `AudioOpen`/`AudioData`/`AudioClose`/`AudioVolume`, owns the thread `rdp-audio-out`, mute, counters |

- **The reader thread only enqueues.** `RdpPane`'s message handler hands the bytes to
  `RdpAudioPlayer`, which copies them into the `JitterBuffer` and returns. `SourceDataLine.write`
  blocks and runs only on `rdp-audio-out`.
- **Buffer policy** (constants in one place, in `JitterBuffer`): the line is opened with a
  100 ms buffer; playback starts once 60 ms are queued *and* 60 ms have passed since the first
  byte (the margin against late waves, whatever their size; see "What was measured"); an underrun
  is the output running dry with nothing queued, after which both conditions apply again; when more than 500 ms are queued, the oldest audio is dropped down to 60 ms (a server
  that sends faster than real time, or the slow drift between the server's clock and the sound
  card's). `audioLatencyMs` in CONNECT is the line buffer, 100.
- **Delay steering** (added after the lip-sync report; `LatencySteering`, used by `JitterBuffer`):
  the player reports the output's unheard amount every round, and the low point of queue + output
  over each second is compared with a margin (40 ms at the start). A surplus above it by more than
  15 ms is cut from the front of the queue: in a quiet stretch (peak below -42 dBFS) at once, else
  after 1 s in cuts of at most 20 ms, 250 ms apart, with a 5 ms crossfade. A dry output followed by
  sound within 250 ms (a late wave, not a pause) raises the margin by 20 ms, up to 200; two minutes
  without one lowers it by 10, never below 40. Cuts are counted as "trimmed", apart from drops.
  Without it a single late wave left a whole wave of surplus queued for good: simulated with
  Windows' 186 ms waves, the queue then crept up to the 500 ms cap and dropped 440 ms at once (the
  user saw 477 ms dropped in a minute); steered, 269 ms were cut in small steps and nothing dropped.
- **Validation.** `AudioOpen` with a format outside the list, or `AudioData` that is not whole
  frames or arrives with no stream open, is dropped and counted. Nothing here throws to the
  reader.
- **Volume** is applied in software by `Gain` (per channel, never above unity). No
  `FloatControl`: its presence and range differ per platform, and software scaling can be tested.
- **Mute** discards data instead of queueing it and closes the line (data keeps arriving while
  muted, so the idle rule alone would leave a muted pane in the system mixer); the channel stays
  open.
- **A failure in the play loop never ends its thread** (`stepOrRecover`; the same in
  `RdpMicrophone`): nothing would start another. The line is closed and handled as a failed
  device, below. Closing the player ends the loop through its `closed` flag as before.
- **No device, or a device that fails:** the status says "No sound device", data is dropped, and
  opening is retried at most every 2 s. Never a dialog.
- **Idle:** after 3 s without data the line is closed (the stream disappears from the system
  mixer) and reopened on the next data.
- **Several panes** each have their own line; the operating system mixes. A pane in a background
  tab keeps playing, as mstsc does; the speaker button mutes it.

### Java: microphone

| Class | Role |
|---|---|
| `MicGate` | pure state machine, unit-tested |
| `AudioSource` | interface: `open(PcmFormat, bufferMs)`, `read`, `flush`, `close`; `JavaSoundSource` wraps a `TargetDataLine` |
| `RdpMicrophone` | one per connection: takes `MicOpen`/`MicClose`, owns the thread `rdp-audio-in`, sends `MIC_DATA` |

`MicGate` states, per connect attempt (a reconnect starts again at `IDLE`):

| State | Entered | Sends | Capture line |
|---|---|---|---|
| `IDLE` | no stream open | nothing | closed |
| `ASKING` | first `MicOpen` of the connection | silence | closed |
| `ALLOWED` | user allowed | microphone, or silence while muted or no stream | open only while a stream is open and not muted |
| `DENIED` | user refused or closed the prompt | silence | closed |

- **Silence, not nothing.** A remote application that opened a capture stream may stall without
  data, so `rdp-audio-in` sends zero-filled packets paced by the clock whenever a stream is open
  and the microphone may not be heard. The packet is `framesPerPacket` frames (a value outside
  1..rate/5 is replaced by 20 ms).
- **The prompt** (`RdpPane.micConsent`, on the EDT, parent `RdpPane.dialogParent()` so it leaves
  full screen first, through `DialogFocus`-style focus on **Don't allow**): "The remote desktop
  *name* wants to use this computer's microphone." Buttons: Allow / Don't allow. A later
  `MicOpen` in the same connection never asks again (A2).
- **The user can change the answer** with the microphone button: `DENIED` → `ALLOWED` and back,
  and mute/unmute while allowed. These are user actions, so no prompt.
- **A `MicOpen` with a format outside the list** is ignored (nothing is sent, not even silence).
- **Falling behind.** If sending blocks (a stalled helper), captured audio older than 200 ms is
  flushed rather than sent late.
- **No microphone, or the OS refuses it** (Windows privacy setting, macOS permission): silence is
  sent, and the button's tooltip says the microphone could not be opened.

## UI

- **Session dialog, "Local resources" tab** (`SessionSidebar`, with the clipboard rows):
  "Play sound on this computer" and "Use this computer's microphone", both off, independent of
  each other. Under the microphone tick: "jterm asks before the remote computer can listen, once
  per connection."
- **Pane bar** (`RdpPane`, and the same two in `FullScreenBar`):
  - a **speaker toggle**, present when the session plays sound: on / muted; the tooltip shows the
    stream ("44.1 kHz stereo, AAC") or "No sound device";
  - a **microphone button**, present when the session has the microphone on: idle, **recording**
    (a red dot and "Microphone on"), muted, blocked. It opens a menu: Allow / Block for this
    connection, Mute / Unmute. Allowing or blocking there before the server asks answers the
    connection's question in advance.
- **Tab:** while the microphone is actually being sent, the tab title carries a recording mark
  (through `onTitleChanged`), so it is visible from another tab.
- **"Show performance"** gains a sound part: format, queued milliseconds, the low point and
  steering's aim, milliseconds cut, underruns, dropped milliseconds, refused messages.
  Undecodable AAC waves are counted in the helper and appear only in its log (no protocol message
  carries them). The readout is redrawn once a second by a timer while it is shown, because the
  helper's statistics come only while frames flow: on a still desktop the frame part reads "no new
  frames" and the sound part stays live.
- **Preferences → General:** "Use compressed sound (AAC) when this system can decode it", on by
  default (`AppSettings.rdpAacAudio`), read at connect.
- **Flatpak, a session with sound:** nothing special; the permission is there (D4).

## Persistence

`RdpSessionConfig` gains `playSound` and `useMicrophone`, both false; `copyWithNewId` copies them.
The class ignores unknown properties, so no schema change. `playSound` is exported and imported
like the clipboard options. **`useMicrophone` is left out of exports and ignored in imports** (the
mix-in that already drops `driveShares`): an imported file must not arrive with the microphone
enabled.

## Packaging

| Target | Change |
|---|---|
| Windows FreeRDP | `-DWITH_WINMM=OFF` in `build-freerdp-windows.cmd`; the WinMM backends stop being built |
| Helper `CMakeLists.txt` | the new sources; `mfplat mfuuid ole32` on Windows; `AudioToolbox` and `CoreFoundation` on macOS; `dl` is already there on Linux |
| Flatpak | `--socket=pulseaudio` in `finish-args` (playback and capture; Flatpak has no playback-only permission) |
| RPM, DEB | Java Sound needs `libasound.so.2`. Check that the built packages require it (`rpm -qpR`, `dpkg-deb -I`); add it to the package dependencies if jpackage did not |
| macOS DMG | `NSMicrophoneUsageDescription` in `Info.plist`, by the PlistBuddy step that adds `NSLocalNetworkUsageDescription` (before the ad-hoc re-sign) |
| Snap (classic) | nothing |
| Licenses | no new bundled code. `third-party-licenses.txt` and `THIRD-PARTY-LICENSES.md` say that AAC uses the system's decoder and that none is shipped |
| `smoke-test.py` | sends a v10 CONNECT; `--expect-aac` fails unless the helper logs a decoder. Used by the DMG jobs; the MSI job runs the smoke test without it, since a Windows Server runner may lack Media Foundation |

## The sandbox design

Nothing in it has to be loosened. To add there when this is built: `libfaad` is loaded before the
lockdown point like every other library; Media Foundation's AAC decoder joins the H.264 decoder
in the list to try under the Windows mitigation policies; AudioToolbox may need a Mach service in
the Seatbelt profile, to be found by running it. The helper still needs no sound socket (A5).

## Testing

- **Java, headless** (`src/test/java/.../terminal/rdp/audio`):
  `JitterBuffer` (start threshold, underrun, overflow trim, whole frames kept); `Gain` (unity,
  zero, per channel, no overflow at full scale); `PcmFormat` (every listed combination, every
  neighbour refused); `MicGate` (each transition, one prompt per connection, reset on reconnect,
  no capture in any state but allowed + open + unmuted); `RdpAudioPlayer` and `RdpMicrophone`
  against fake sink and source (bad formats dropped, data without a stream dropped, mute, silence
  pacing, the 200 ms flush); `RdpProtocolTest` (v10 CONNECT bytes, each new message, truncated
  and oversized ones); `SessionExportService` (the microphone option neither exported nor
  imported).
- **Helper:** `smoke-test.py` with a v10 CONNECT (sound flags set, closed port) on every target;
  `--expect-aac` in the DMG jobs. The decoders themselves with a small C harness over real AAC-LC
  frames (libfaad in a Fedora container, Media Foundation cross-compiled and run on the VM).
- **Live, Windows 11 VM (`172.16.1.122`) and GNOME Remote Desktop:** a video with sound for ten
  minutes (no drift into silence, lip sync acceptable); remote volume slider and mute; two
  sessions at once; reconnect; sound with drives on and with drives off; with the option off, no
  `rdpsnd` in the helper's log. Microphone: the remote sound recorder asks once, "Don't allow"
  records silence and the local OS shows no microphone use, "Allow" records, mute and block from
  the button work mid-recording, a second recording in the same connection does not ask, a
  reconnect asks again, full screen leaves full screen for the prompt.
- **macOS:** on the Proxmox VM with a USB headset passed through (it has no sound hardware of its
  own): Java Sound capture permission, the usage string, the AudioToolbox decoder.

## Build order

One feature, shipped together (D1); each step leaves a working tree.

0. **Spike, no product code kept.** A temporary logging device in the helper against the Windows
   11 VM, GNOME Remote Desktop and xrdp: the server's format lists; the format chosen with only
   PCM claimed and with AAC claimed (waves dropped); FreeRDP's `quality:` argument tried if
   Windows keeps choosing PCM; wave sizes; static or dynamic channel; `framesPerPacket`; whether
   the volume message arrives. Java Sound on the Windows VM over SSH: mixers, the buffer size a
   100 ms request gets. Write the answers into this document's "measured" section and adjust the
   buffer constants.
1. **Protocol v10 and PCM playback:** `protocol.h` + `RdpProtocol` + tests; `audio.c` sound
   device, provider and `apply_connect` changes, `WITH_WINMM=OFF`; `terminal.rdp.audio` playback
   classes + tests; `RdpSessionConfig.playSound`, the dialog tick, the speaker button, the
   performance line; Flatpak permission, package dependency check.
2. **Microphone:** the `audin` device and microphone thread; `MicGate`, `RdpMicrophone` + tests;
   `useMicrophone`, the export mix-in, the dialog tick, the prompt, the button, the tab mark;
   the macOS usage string.
3. **AAC:** the decoder interface; Windows; Linux; the Preferences option; `--expect-aac`;
   macOS when a Mac is available.
4. **Documentation:** `docs/rdp-sessions.md` (replace "Sound is not carried over yet"; how to get
   AAC on Linux; microphone permissions per OS), `docs/troubleshooting.md`, `CLAUDE.md` (a "Sound"
   bullet under the RDP section with the sharp edges below), the status header of this document.

**Sharp edges to carry into `CLAUDE.md` and code comments:** never fail the sound device's
`Open`; decide in `PlayEx`, not `Open`; don't call `receive` from the stdin reader; the capture
line is closed, not merely ignored, whenever the microphone may not be heard; `useMicrophone` is
never imported; no codec in any installer.

## Notes for the session that builds this

- Read `CLAUDE.md`'s RDP section first; the conventions there (protocol changed on both sides
  together, dialogs through `RdpPane.dialogParent()`, Swing-free logic with tests, no codec
  bundled) apply unchanged.
- **Work that suits a cheaper model in a sub-agent,** given this document and the named files:
  the `RdpProtocol` mirror and `RdpProtocolTest` cases from the tables above; `PcmFormat`,
  `Gain`, `JitterBuffer` and `MicGate` with their tests (pure functions with the behaviour
  specified here); the `RdpSessionConfig` fields and export mix-in with tests; the documentation
  step; the packaging one-liners.
- **Work to keep in the main session:** everything in the helper (thread and lifetime rules
  around FreeRDP's callbacks, the add-in provider, the decoders); `RdpAudioPlayer` and
  `RdpMicrophone` threading; the consent wiring in `RdpPane`; the spike's conclusions; the final
  review of properties A1–A9 against the code, one by one.
- Verify as the repository does: `mvn -q test`; the helper with `build-linux.sh` and
  `smoke-test.py`; a live session launched with a throwaway `user.home` (the user's own jterm is
  running); Windows on the VM over SSH. GUI checks on Windows and anything on macOS need the user.
- Commit messages are public release-note text: no AI attribution. Push only when asked.

## Decisions

| | Question | Decision | Why |
|---|---|---|---|
| D1 | Scope | Playback and microphone, built together. *(user)* | |
| D2 | Default | Both off, per session. Existing sessions stay silent. *(user)* | Every other redirection is opt-in; with both off no channel exists. |
| D3 | AAC decoder | The operating system's (Media Foundation, AudioToolbox), on Linux the distribution's `libfaad` if installed, in the Flatpak the runtime's FFmpeg; never bundled or downloaded. *(user)* | AAC is still patent-licensed, as H.264 is; FAAD2's advisory history stays with the distribution. |
| D4 | Flatpak | Add `--socket=pulseaudio`. *(user)* | |
| D5 | Microphone consent | The session option, plus a prompt once per connection, default "Don't allow". *(user)* | A hostile or impersonated server must not be able to listen without a conscious moment. |
| D6 | Formats | PCM and AAC for playback. *(user)* | |
| D7 | Where sound is played and recorded | In the JVM, through Java Sound; the helper forwards. | Keeps A5 and the sandbox; one implementation for three platforms; consent logic in memory-safe, tested code. |
| D8 | Microphone format | PCM only. | An AAC encoder would be a second codec with the same patent question, for a stream of at most 1.5 Mbit/s. |
| D9 | Refused or muted microphone | Silence is sent, the capture line is closed. | The remote application keeps working; the OS indicator tells the truth. |
| D10 | Volume | Server volume applied in software, attenuation only; a mute button per pane; no local volume slider. | The system mixer already has a per-application slider. |
| D11 | Background panes | Keep playing and recording, with the tab mark. | A call continues while the user reads something else; mstsc behaves the same. |
| D12 | `useMicrophone` in exports and imports | Left out, ignored. | Same rule as shared folders: an import never enables something that reaches local hardware or files. |
| D13 | "Play on the remote computer" (mstsc's third choice) | Not offered. | Only meaningful for console sessions; off already means the server plays nothing here. |
| D14 | Turning AAC off | One global Preferences option, on by default. | A way out if a decoder misbehaves, without a setting per session. |

## Sources

- [Via LA, AAC licensing](https://www.via-la.com/licensing-2/aac/)
- [Hydrogenaudio thread quoting the patent expiry estimates](https://hydrogenaud.io/index.php/topic,121109.0.html)
- [Debian security tracker, FAAD2 (CVE-2021-32274)](https://security-tracker.debian.org/tracker/CVE-2021-32274)
- [cvefeed.io, FAAD2 CVE list](https://cvefeed.io/vuln/vendor/13440/a-faad2_project)

## Current state (a log, 2026-11-11 to 2026-11-12)

Kept in the order things happened; where an entry says "not yet", read on.

**Step 0** was folded into step 1: the helper logs what the server offers and picks, and the live
session against Windows 11 answered the Windows questions (see "What was measured"). GNOME Remote
Desktop was tried later (see the end of this section); xrdp was skipped by decision (the user doesn't
run it). Java Sound was probed on Windows and on the macOS VM with a USB headset
(see "What was measured"); jterm itself played sound on both later (steps 2 and 3 below).

**Step 1 is done**, in one commit on `feature/rdp-session`:

| Part | Where |
|---|---|
| Protocol v10 (all of it, microphone messages included) | `protocol.h`, `RdpProtocol` (+ `RdpProtocolTest`) |
| Sound device, provider hook, CONNECT's audio part | `native/rdp-helper/src/audio.c`, `audio.h`; message helpers moved to `helper.h` |
| Playback | `terminal.rdp.audio`: `PcmFormat`, `Gain`, `JitterBuffer`, `AudioSink`, `JavaSoundSink`, `RdpAudioPlayer` (+ tests) |
| Session option, dialog | `RdpSessionConfig.playSound`, `SessionSidebar` ("Play sound on this computer") |
| Pane | `RdpPane`: Sound/Muted button (pane bar and `FullScreenBar`), sound part of "Show performance"; mute is per pane, kept across reconnects, not saved |
| Packaging | `WITH_WINMM=OFF` (Windows FreeRDP), `--socket=pulseaudio` (Flatpak), `--linux-package-deps` for ALSA in the RPM and DEB jobs |
| Smoke test | `smoke-test.py` sends a v10 CONNECT with playback on |

Where the build differs from the text above:

- **Start delay and underrun counting** (`JitterBuffer.START_DELAY_MS`, `markUnderrun`,
  `AudioSink.queuedMillis`), from the Windows measurement.
- `RdpProtocol.decode` does not size-check `AUDIO_DATA`: the player drops and counts an oversized
  chunk, because a throw in decode ends the whole connection.
- The server's format list and the chosen format are written to stderr at every connect (not at
  debug level): they are what the next server's spike needs. jterm logs helper lines at debug.
- The helper masks `JRDP_AUDIO_MIC` until step 2 builds the `audin` device; Java never sets it yet.
- Volume (`AUDIO_VOLUME`) is wired and unit-tested, but whether Windows sends it was not checked.

**Not verified yet:**

- The dynamic channel and the "first device to play owns the stream" rule: Windows used static,
  and so did GNOME Remote Desktop (2026-11-12: `sound (static): playing AAC ...`).

**Step 2 is done** (`ce73c69`, DMG fix `8c3b8a7`), live-tested on Windows and macOS:

| Part | Where |
|---|---|
| `audin` device, `MIC_DATA` checks and queue | `audio.c` (`mic_*`, `audio_mic_data`, `audio_mic_process`); reader and main loop in `jterm_rdp_helper.c` |
| Gate, capture loop | `terminal.rdp.audio`: `MicGate`, `AudioSource`, `JavaSoundSource`, `RdpMicrophone` (+ tests) |
| Session option, export | `RdpSessionConfig.useMicrophone`; `SessionExportService`'s mix-in drops it both ways (+ tests) |
| Dialog | `SessionSidebar`: "Use this computer's microphone" and the hint under it |
| Pane | `RdpPane`: Mic button and menu (pane bar and `FullScreenBar`), the prompt (`askMicrophone`), the tab mark (`displayTitle`); wording in `MicButtonLook` (+ tests) |
| Packaging | `NSMicrophoneUsageDescription` in the DMG (`release.yml`, printed again by the DMG check) |
| Smoke test | CONNECT with playback and microphone on |

Where the build differs from the text above:

- **No dedicated microphone thread in the helper.** The reader queues `MIC_DATA` (16 packets, oldest
  dropped) and signals an event; the **RDP thread** (main loop) calls `receive`, the same way the
  clipboard's jobs run. Reading drdynvc showed why that is safe: `receive` ends in
  `dvcman_write_channel`, which holds only the channel's own lock while it *queues* the PDU for the
  RDP thread (it never waits for the network), and audin's `Close` comes from the drdynvc thread
  without that lock held. `receive` runs under `g_mic_lock` with the callback still set; `Close`
  clears it under the same lock, so audin can't free its channel callback under a running `receive`.
- **The microphone button opens a menu** (Allow/Block for this connection, Mute/Unmute) instead of
  cycling through states on click. Allowing or blocking from it before the server asks also
  answers the connection's question in advance (no prompt then).
- **Mute is kept across reconnects** (like the speaker's), consent is not: each connect attempt
  gets a new `RdpMicrophone` and `MicGate`, so a reconnect asks again.
- `JavaSoundSource` falls back to the other channel count and converts (`ChannelMix`), from the
  macOS measurement.
- The capture line buffer is 500 ms (`RdpMicrophone.LINE_BUFFER_MS`), so a backlog over the 200 ms
  limit can be seen by `available()` and flushed.

**Verified 2026-11-12:** the RPM requires `libasound.so.2()(64bit)` and the DEB depends on
`libasound2t64 | libasound2` (Release run 43202051664), and the DMGs carry jterm's
`NSMicrophoneUsageDescription` (jpackage's template already has the key, so the workflow `Set`s it).
**The microphone works live on Windows**: MSI from that run on a Windows 11 laptop with a G435
headset, against the Windows 11 VM; the user ran the checklist below successfully.

The live session's helper log (installed MSI, started from cmd with the `RdpConnection` logger at
debug): **Windows opens the microphone as PCM 44.1 kHz stereo with 441 frames per packet**, i.e. 10 ms
packets of 1 764 bytes, about 100 `MIC_DATA` messages and 176 KB/s through the pipe. It picks stereo
although the headset's microphone is mono, so on macOS, where that microphone has no stereo line,
the `ChannelMix` fallback is what makes it work. Playback was as on the first measurement (the same
30 formats, PCM 44.1 kHz stereo, 32 768-byte waves, static channel).

**macOS live, same day** (x64 DMG on the macOS 26 VM, G435 receiver passed through, started with
`open --env JAVA_TOOL_OPTIONS=… --stderr …` so that macOS attributes the microphone to jterm and
not to Terminal): Windows asked for the same 44.1 kHz stereo stream with 441-frame packets, and
the log shows `RDP microphone: no stereo line at 44100 Hz, capturing mono and converting`. The
fallback is what made the capture line open there. Playback as on Windows. The user confirmed: the remote
recorder got their voice; macOS asked "jterm would like to access the microphone" with jterm's
text; the orange dot showed while the Mic button was red and went away on Mute and on Block.
Those two runs settled what step 2 had left open: which format Windows asks of the microphone and
its `framesPerPacket`, that the prompt, the silence and the capture behave as specified, the OS
indicators, and the macOS usage string's effect. The helper logs `microphone: server opened PCM … frames per packet` at every
stream open. If a server picks a PCM variant outside the list (8-bit, say), audin opens the device
in a listed 16-bit format and FreeRDP's own DSP converts jterm's samples; that path is untested.

**Live test, microphone** (Windows 11 VM; a throwaway `user.home`, see below): session option on,
connect, open Sound Recorder (or Settings → System → Sound → Input → "Test your microphone") on
the remote side. Expect: one prompt, "Don't allow" focused; with "Don't allow" the recorder gets a
flat line and the local OS shows no microphone use; Mic → Allow records your voice and the button
turns red, the tab gets "●"; Mute and Block from the menu cut it mid-recording (the local
indicator goes off); a second recording doesn't ask; Reconnect asks again; in full screen the
prompt leaves full screen first.

**To try a build live** (the user's own jterm keeps its config):

```bash
export PATH=/home/mark/apache-maven-3.9.11/bin:$PATH
mvn -q package -DskipTests
native/rdp-helper/build-linux.sh /home/mark/frdp/dist   # FreeRDP 3.33 dist already built there
mkdir -p /tmp/jterm-sound-test
java -Duser.home=/tmp/jterm-sound-test \
     -Djterm.rdp.helper=$PWD/native/rdp-helper/build/stage/jterm-rdp-helper \
     -Dorg.slf4j.simpleLogger.log.com.katmoda.jterm.terminal.rdp.RdpConnection=debug \
     -jar target/jterm.jar 2>&1 | tee /tmp/jterm-sound-test/log.txt
grep -E "sound|rdpsnd|microphone|audin" /tmp/jterm-sound-test/log.txt
```

**Step 4 (2026-11-12):** `docs/rdp-sessions.md` ("Sound and microphone", the two Local resources
settings, the full-screen bar, the sound part of "Show performance", the limitations line),
`docs/troubleshooting.md` (no sound / microphone; the macOS Gatekeeper entry, which still said
right-click → Open, which macOS 15 removed), `CLAUDE.md` (a sound bullet under the RDP section
with the sharp edges; the drive bullet's `sys:fake` remark corrected to what was measured). Left
for step 3: how to get AAC on Linux, the Preferences option, the license files' note that AAC uses
the system's decoder. Proposed and not decided: a way to read the helper's log in an installed
build (a log file, or "Copy helper log" next to "Show performance"); the workaround is in
`CLAUDE.md`.

**Step 3 (2026-11-12):**

| Part | State |
|---|---|
| Measurement | done, see "AAC from Windows 11" above; the spike is removed |
| Decoder interface | `aac.h` (plain C types; builds the AudioSpecificConfig) |
| Linux | `aac_faad.c`: built, tested headlessly in a Fedora 43 container (libfaad 2.11.2): raw AAC-LC frames from ffmpeg at Windows' settings decode to the right tone; a corrupt frame is refused and decoding carries on; the one-second cap holds; a library writable by others, owned by a user, or linked into `/tmp` is refused (and logged as refused, not "not installed"). The staged helper passes `smoke-test.py --expect-aac` there |
| Device | `audio.c` claims `WAVE_FORMAT_AAC_MS` (cbSize 0) when CONNECT has `JRDP_AUDIO_AAC` and `aac_probe()` found a decoder; decodes in `PlayEx`; decoder survives `Close`; bit-rate switches logged |
| Java | `AppSettings.rdpAacAudio` (on), Preferences → General "Compressed sound (AAC) for RDP", passed by `RdpPane` |
| Smoke test | AAC flag on; `--expect-aac` |
| Windows | `aac_mf.c` (Media Foundation's AAC decoder MFT, `MFSTARTUP_LITE`): the test harness cross-compiled with MinGW and run on the Windows 11 VM over SSH decodes the raw frames to the right tone; a corrupt frame is *concealed* by Windows' decoder (23 ms out) rather than failed. MSVC build and a live session: the next Release run |
| Flatpak | `aac_ffmpeg.c` (the runtime's libavcodec): a harness (frames from libavcodec's AAC encoder, raw AAC-LC, no ADTS) run inside the Flatpak decodes the right tone at 22.05/44.1/48 kHz, mono and stereo; 2000 random frames refused, no crash; smoke test `AAC available` with the sandbox full (2026-11-15). Live session against Windows 11: not yet |
| macOS | `aac_audiotoolbox.c` (AudioConverter; magic cookie = an ES_Descriptor wrapping the built AudioSpecificConfig). Unbuilt so far: the DMG jobs build it and run `smoke-test.py --expect-aac` |

On Rocky/RHEL 10 `faad2-libs` (2.11.2) comes from **EPEL**; with it installed the local helper
passes `--expect-aac` too. **Live, same day:** jterm on Rocky 10 against the Windows 11 VM played
`AAC 44100 Hz, 2 channel(s), waves of 290 bytes`, decoded by `/usr/lib64/libfaad.so.2.11.2`, with no
decode errors and no bit-rate switch logged; with the Preferences option off the next connection
played PCM. **Lip sync:** sound came a bit after the picture, with AAC and with PCM alike, also
with the MSI on the laptop straight to the VM. mstsc on the same laptop showed the same lag, so part
of it is the server's (186 ms waves) and part the output: a Bluetooth headset was worse than the
monitor's speakers, which were better but still not in sync. jterm's own share was the queue
ratcheting up after bursts ("ms queued" 70–170, 477 ms dropped in a minute); delay steering (Buffer
policy above) removes that part. Live, MSI on the laptop to the VM: the tooltip said AAC (Media
Foundation decoding confirmed, before steering too), and with steering sound was better in sync;
"Show performance" read `0 ms queued (lowest 25 ms with the output, aim 40), 347 ms cut to catch
up, 454 ms dropped`. The drops mean the queue still went past 500 ms at least once, when and why
unknown (possibly a seek in the video). macOS (x64 DMG on the Proxmox VM, G435): `AAC decoder
available (AudioToolbox)`, `playing AAC 44100 Hz, 2 channel(s), waves of 300 bytes`, the tooltip
said AAC, no audible problems. Its line read `49 ms queued (lowest 140 ms with the output, aim
100), 576 ms cut to catch up, 5 underruns`: the VM's output reports more held than Windows', and
three late waves had raised the aim to 100. Steering only ever cuts the queue, never what the output
already holds. The 454 ms "dropped" on Windows is per new sound, not per seek: dropped rose by
exactly 454 every time a Windows test sound played after silence (1362 after three), and nothing
was heard missing, so Windows starts each sound with a burst over 500 ms that is mostly silence.
The overflow now counts its silent front part as trimmed; "dropped" is only sound meant to be heard. Confirmed
live with 9d823b8: after several test sounds, `1476 ms cut to catch up, 6 underruns`, no dropped,
aim still 40 (the underruns were sounds ending, not late waves).

**GNOME Remote Desktop (orion, Remote Login, from the work laptop's MSI):** offers
`WAVE_FORMAT_AAC_MS/2ch/44100` (cbSize 0, so jterm decodes it), `WAVE_FORMAT_OPUS/2ch/48000` (not
supported) and PCM; jterm played AAC with 278-byte waves. Server volume changes worked (first live
test of AUDIO_VOLUME). The microphone: GNOME opens it as soon as the session connects (Windows
waits for a recording app), as `PCM 44100 Hz, 2 channel(s), 441 frames per packet`, so GNOME's
microphone icon shows for the whole session whatever jterm's block or mute says; recordings were
silent while blocked or muted, as designed. Lip sync like the Windows VM: `0 ms queued (lowest 78
ms with the output, aim 40), 378 ms cut, 2 underruns, 454 ms dropped`. The 78 ms is the 100 ms
output kept full by ~23 ms frames, which steering didn't cut because it capped cuts at the queue,
empty at the low point; it now cuts the surplus from the next frames instead. The exact 454 ms
again, on a second server, points at jterm: the guess is that opening the output takes ~0.5 s on
that laptop (reopened after 3 s of silence) while sound piles up. jterm now logs at INFO an output
open of 100 ms or more and every overflow, saying whether the output was opening. Retested with
6b73983: `0 ms queued (lowest 43 ms with the output, aim 60), 44 ms cut, 1 underruns`, so cutting
from the next frames works (78 → 43). The log showed no slow open, only `more than 500 ms piled up,
453 ms skipped (while playing)`: the output-open guess was wrong. The figure is threshold
arithmetic, not burst size: ~23 ms frames crossing 500 ms land between 500 and 523 ms, less the
60 ms kept, so any burst of 0.5–1 s at a stream's start gives ~450 ms. Both servers evidently send
such a backlog when a stream starts (Windows' was silence); it is late by definition, so skipping
it is what keeps the start in sync. The user confirmed sync was better with this build, and that the
laptop's own microphone indicator goes off when the microphone is blocked or muted (A-property
holds on the client; GNOME's own icon is the server's view of the open channel).

**All four steps are done and live-tested** on Windows 11 (MSI) and macOS (DMG), against a Windows
11 server and GNOME Remote Desktop. Not done, by decision: xrdp. Untested live, by circumstance: a
mid-session AAC bit-rate switch (no server produced one).

Also done: the MSI job now runs `smoke-test.py` (without `--expect-aac`: a Windows Server runner
may lack Media Foundation), the DMG jobs add `--expect-aac`; the license files say AAC uses the
system's decoder and none is shipped (FAAD2 is GPL-2.0-or-later, loaded only if installed); the docs
cover the decoders per system and the Preferences option; the sandbox design has the decoders'
notes; `CLAUDE.md` the sharp edges and the test approach.

Verified since: the MSVC and macOS builds (Release runs), AAC live on Windows (MSI, Media
Foundation) and macOS (DMG, AudioToolbox), see "Lip sync" above. **Bit-rate switching:** with the
Windows VM's network device limited to 0.2 MB/s (about 1.6 Mbit/s, Proxmox rate limit) during AAC
playback, the server kept the same format: no `AAC bit rate` line, one `playing AAC ... waves of
300 bytes` for the whole session. Windows seems to pick the AAC format once per stream; at 96
kbit/s the sound is small next to the video, which takes the squeeze. A limit tight enough to
force a switch would leave the desktop barely usable, so the Close + Open path stays untested live
(the decoder surviving `Close` is still what it would rely on).

**Review, 2026-11-13:** the design sections above were brought in line with what was built (they
still described the first AAC plan, a microphone thread and `cbSize <= 64`). Code changes that came
out of it: a failure in the play or capture loop no longer ends its thread; a muted pane closes its
output line; the readout's sound part no longer freezes on a still desktop; the helper's format
list marks AAC formats it claims with `*` too (and no longer risks being cut at 1 024 bytes);
protocol v11's `AUDIO_DELAY`. Unit-tested, and the Linux helper passes `smoke-test.py
--expect-aac` as v11; **none of it run in a live session**, and `AUDIO_DELAY`'s effect on a real
server is unknown.
