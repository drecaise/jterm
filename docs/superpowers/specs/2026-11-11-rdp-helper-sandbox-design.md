# Sandboxing the RDP helper

**Status:** designed, 2026-11-11. Phase 1 (layer 1) built 2026-11-13, see
[Layer 1 as built](#layer-1-as-built): Linux verified locally (this machine and a Rocky 9
container), Windows and macOS by CI (Release run 43430723857: the MSVC flags, the PE check and the
smoke test pass). Decisions D1–D6 at the end were answered on 2026-11-13. Phase 2 (the Linux
spike) done 2026-11-13, see [its results](#phase-2-spike-linux-2026-11-13). Phase 3 (the Linux
sandbox) built 2026-11-13, see [Layer 2 as built](#layer-2-as-built-linux); verified against live
xrdp and Windows 11 (NLA) servers, not yet GNOME. Phase 4 (Windows W1) built 2026-11-13 on the
Win11 VM, see [W1 as built](#w1-spike-and-as-built-2026-11-13); its spike found that W1 leaves
Credential Manager readable, and that W2's restricted token closes that. Phase 6 (Windows W2) built
the same day, see [W2 as built](#w2-as-built-2026-11-13); on Windows NLA now uses WinPR's NTLM
(no Kerberos) unless the session's "Use Windows sign-in (Kerberos)" switch is on. Brought up to
date with the sound and microphone work on 2026-11-13 (asset A7, the "Sound and microphone"
section, the measured table).

## Where things stand (end of 2026-11-13)

Committed and pushed (`home`, `gh-private`): `66c641d` layer 1, `7fed1c8` layers 2/3 (Linux
sandbox, Windows W1 + W2, the Kerberos switch, protocol v12). Release run **43500426323** on
`jterm-private` was started for `7fed1c8` and had not finished: **check it first** (`gh run view
43500426323 -R drecaise/jterm-private`). It is the first CI run of the sandbox: the RPM (EL9,
EL10) and DEB jobs require `--expect-sandbox full` (Docker's seccomp profile must let Landlock
through; Podman's does), the MSI job `--expect-sandbox partial` with all seven W1/W2 self-test
refusals on GitHub's runner (CredEnumerate there must answer "access denied", not "not found").
The user has limited Actions minutes: batch fixes, one run per round.

Open, in order:
1. **The CI result** above. *MSI (2026-11-14):* failed because the restricted child couldn't create
   its FreeRDP context on GitHub's Windows Server runner: OpenSSL's CryptoAPI entropy source fails
   while the start-up token is impersonated (traced with `cdb` + `wt`: `winpr_RAND` returned -1 in
   `license_new`; the same call works under the lockdown token). Media Foundation behaves the same,
   so H.264 and AAC were reported missing there. Fixed by seeding the generator and running both
   decoder probes with the start-up impersonation dropped (`sandbox_without_startup_token`); the
   runner then passes with `token=restricted`, H.264, AAC and all seven refusals. The Win11 VM
   never showed it. The Flatpak job of that run failed separately (`flatpak-builder` exit 139 after
   "Invalid cross-device link" updating the SDK): not looked at yet.
2. **Done 2026-11-14:** the user's GUI pass on Windows with the MSI of Release run 43580840685
   (all jobs green) found nothing broken. Two things to know when repeating it: one session shows
   *two* `jterm-rdp-helper.exe` (launcher + restricted child), both gone when the pane closes; and
   files copied on the remote are saved with "Save files…" in the session's bar, not by pasting
   locally. The list was: clipboard text,
   image and file each way, a shared folder (save a file into it), sound, microphone, a video, the
   pane tooltip ("Helper sandbox: partial (it can't reach your files, saved passwords, microphone
   or other programs, …)"), and one session with the Kerberos switch on. Everything so far was
   tested with a protocol script, not jterm's window (`~/jterm-sandbox-tools/`).
3. **Not tested at all:** Kerberos against a domain server (the switch's NLA through Windows'
   SSPI was confirmed only with NTLM, from the user's work laptop against the VM), an RD Gateway, a
   real RDS redirect (the `RESOLVE` path was tested only with a name the helper couldn't resolve),
   a GNOME Remote Desktop server.
4. **Flatpak H.264: decided 2026-11-14, download allowed.** The 25.08 runtime has no openh264
   extension, so `OpenH264.providedByFlatpak()` is gone and the Flatpak offers the same opt-in
   download as the other Linux packages (the spike showed it working there). Not yet seen in a
   built Flatpak's Preferences dialog.
5. Flatpak and snap jobs don't run the smoke test yet (so no sandbox check there).
6. **macOS (D5): built 2026-11-14**, on the Intel macOS VM (macOS 26.7, no GPU). The profile
   (`sandbox.c`, Layer 3b) passes the 14-attempt self-test, and live sessions to the Win11 VM keep
   H.264 (VideoToolbox), AAC, clipboard, a shared folder and the microphone channel. Measured:
   VideoToolbox needs `IOSurfaceRootUserClient` and its refusal is not logged. Hardware decoding
   can't be seen here, hence `h264_check_after_lockdown` (H.264 off rather than a black desktop)
   and the DMG jobs' `--expect-sandbox full --expect-h264`, which run on Apple Silicon. Not yet
   seen: a DMG from CI on the VM, and the Local Network prompt with the profile in place.

Test tools and how to rerun everything: `~/jterm-sandbox-tools/README.md` (outside the repo).
**Builds on:** `2026-11-07-rdp-session-design.md` (the helper process), and property P6 of
`2026-11-09-rdp-drives-and-file-copy-design.md` ("the helper holds no local paths and opens no
local files"), which is what makes this practical.

## Summary

The helper exists so that a memory-safety bug in FreeRDP, triggered by a hostile server, can't
reach jterm's JVM. Today that holds for **crashes and memory reads only**. The helper runs with the
user's full rights, so code execution inside it can read `~/.ssh`, talk to the SSH agent, ask the
desktop keyring for jterm's master password, start programs and write anywhere the user can.

This design takes those rights away from the helper once it has started and before the first byte
arrives from the server. It needs almost nothing afterwards: its two pipes to jterm, TCP to the
server, and a handful of read-only system files. The work is in three layers:

1. **Hardening that needs no sandbox** (every OS): a scrubbed environment, exploit mitigations in
   the build, no core dumps, name resolution done before lockdown.
2. **Linux:** Landlock, a small seccomp filter and resource limits, applied by the helper to
   itself.
3. **Windows and macOS:** self-restriction first (integrity level, job object, mitigation policies;
   a Seatbelt profile), with a stronger launcher-based Windows level as a separate, spiked step.

No change to the wire protocol is needed for layers 1 and 2.

## Threat model

**The attacker** has arbitrary code execution inside the helper process, through a FreeRDP or
codec bug reached from the server side (the server is hostile, or impersonated after a certificate
was accepted by mistake).

**What the helper legitimately holds and can't be denied:** the session's RDP password (and the
gateway's), the decoded screen, the session's clipboard and file traffic as far as the session
enabled it, the remote desktop's sound, the microphone samples jterm chose to send (after the
user's consent, and never while muted or blocked: the helper only ever sees what `MicGate` let
through), and a TCP connection to the server. A compromised helper can always leak those to the
server it is talking to. That is the floor.

**What this design protects:**

| | Asset | Reached today through |
|---|---|---|
| A1 | SSH keys, the vault file, every other file of the user | plain file reads |
| A2 | The SSH agent, the desktop keyring (jterm's master password), D-Bus, the display server | Unix sockets, named pipes, Mach services, `SSH_AUTH_SOCK` in the inherited environment |
| A3 | Persistence and tampering: dotfiles, autostart entries, jterm's own config | file writes |
| A4 | Other processes of the user, jterm itself | `ptrace`, `/proc/<pid>/mem`, process handles, signals |
| A5 | New processes | `execve`, `CreateProcess` |
| A6 | The rest of the network, as the user's machine | sockets to any address and port |
| A7 | The microphone, behind jterm's consent and mute (audio design, A1–A3) | opening a capture device itself: the PipeWire/PulseAudio socket or `/dev/snd` on Linux, WASAPI on Windows, CoreAudio on macOS. Seen on macOS (audio live test, 2026-11-12): capture by jterm's JVM is attributed to jterm, which is what the permission is granted to. The helper has never opened a capture device, so its capture counting as jterm too is inferred: it is a child of the same app, and its connections did count as jterm for Local Network |

A7 is new with the sound work. jterm's own rule ("the helper never touches a sound device") is
kept by the helper's code, which is exactly what an attacker with code execution replaces: the
consent prompt, the mute and the recording mark are all in the JVM and see nothing of a capture
the helper starts by itself. Once the user has granted jterm the microphone at all, only a
sandbox stands between a compromised helper and it.

**Still exposed afterwards, by design:** the message protocol to jterm. A compromised helper can
send any frame, certificate request, clipboard offer, drive request, sound or microphone-stream
announcement. jterm already treats all of it as hostile (the Java parsers, the per-copy consent,
the drive service; for sound a capped queue, attenuation only, and a `MIC_OPEN` that can cause
one prompt per connection, defaulting to "Don't allow"), and
[the review of 2026-11-11](#related-findings) lists the gaps there. The sandbox doesn't replace
that; it removes the ways *around* it.

**Not in scope:** other local users (the two FreeRDP patches cover the known case), and an
attacker who already runs code as the user outside the helper.

## What the helper needs (measured)

Linux, the staged helper under `strace -f` driven by `smoke-test.py` (a CONNECT to a closed port,
once by address and once by name; 2026-11-11). The TLS and session rows were traced on
2026-11-13 against a live xrdp server (phase 2, [below](#phase-2-spike-linux-2026-11-13)).

| Phase | Files | Sockets |
|---|---|---|
| Start-up (before CONNECT is read) | its own libraries, the system's `libssl`/`libcrypto`/`libz`/`libc`; `openssl.cnf`, the crypto-policy file, the `legacy` and `pkcs11` provider modules; `/proc/sys/crypto/fips_enabled`; `/etc/localtime` and zoneinfo | none |
| CONNECT applied | the OpenH264 library (probed in each library directory unless jterm passed one); with AAC asked for, `libfaad.so.2` looked for by absolute path in five system directories and loaded (traced 2026-11-13: `readlink`, `stat` and one `openat` of `/usr/lib64/libfaad.so.2.11.2`) | none |
| Name resolution | `/etc/resolv.conf`, `nsswitch.conf`, `hosts`, `host.conf`, `gai.conf` | `AF_INET` datagram (address sorting), `AF_NETLINK`; with systemd-resolved or sssd also `AF_UNIX` |
| Connecting | none | `AF_INET`/`AF_INET6` stream to the target |
| TLS | the crypto-policy file again; the CA bundle (`/etc/pki/tls/cert.pem`) and a hashed lookup in `/etc/pki/tls/certs`; FreeRDP's own store under the home folder, read only: `~/.config/jterm/jterm-rdp-helper/certs/<hash>.0` (FreeRDP adds that folder as a CA lookup directory) and `server/<host>_<port>.pem`; WinPR's `HKLM.reg` under the build prefix | none |
| Session | `/sys/devices/system/cpu/online` (glibc's processor count, for codec threads); with no codec passed by jterm, the OpenH264 library is searched for and loaded **again** here, see the spike | none beyond the TCP connection; threads only (`clone3`) |

Writes: none. Child processes: none. `mkdir`: none. With playback, microphone and AAC all set in
CONNECT (the smoke test's flags since the sound work; traced again 2026-11-13) the helper opens
nothing under `/dev/snd`, no ALSA or PulseAudio configuration and no socket other than the TCP
one: the forwarding devices are all there is.

Two things follow. **Everything that needs a file or a non-TCP socket happens before the server
is contacted**, except the CA store, the processor count and a codec loaded late (spike). And **name resolution is the
awkward part**: it is the only step that wants Unix sockets and datagram sockets, exactly the
kinds a sandbox most wants to deny.

### Phase 2 spike, Linux (2026-11-13)

Layer 2 applied by hand: a preloaded shim (not committed) wraps `freerdp_connect` and, just before
it, sets `PR_SET_NO_NEW_PRIVS`, `RLIMIT_FSIZE` 0 (with `SIGXFSZ` ignored, or a write would kill
the process instead of failing), `RLIMIT_NOFILE` 512, Landlock with exactly the file list of
layer 2 below plus a port rule for the session's port, and the seccomp deny-list as a raw BPF
filter with `SECCOMP_FILTER_FLAG_TSYNC`. Server: xrdp in a Fedora container, TLS without NLA,
clipboard and sound on. Not committed either: the session driver (`live-session.py`, the
protocol as jterm speaks it, answering the certificate prompt).

- **A live session works with the full sandbox** (`landlock=6 net=1 seccomp=1 fsize=1`): TLS,
  the certificate prompt, frames by RemoteFX progressive and by H.264 with jterm's downloaded
  codec, the sound channel's format exchange, the clipboard's capabilities, a clean BYE. The
  denials it met (the processor count, FreeRDP's own store, `HKLM.reg`) were all harmless.
- **Every forbidden action failed** in a self-test run after the lockdown: reading `~/.ssh`,
  `/etc/passwd` and `/proc/self/environ`; creating a file in `/tmp`; `AF_UNIX`, `AF_NETLINK` and
  UDP sockets; connecting to port 22; binding a TCP port; `execve`; `kill` and `ptrace` of the
  parent; opening `/dev/snd/controlC0` and even `/dev/null`.
- **By name it fails, as D3 expects**: `ERRCONNECT_DNS_NAME_NOT_FOUND` from FreeRDP's own lookup.
  Every TCP connection FreeRDP makes, the gateway's included (`rdg`, `wst`, `arm`, all through
  `freerdp_tcp_connect`), goes through `rdpTransportIo.TCPConnect`, so the hook covers them all.
  The hook must also refuse what `freerdp_tcp_default_connect` treats specially: a name starting
  with `/` is connected as a **Unix socket**, `|` takes the port number as an existing descriptor,
  and `vsock` names. A redirect's target address is already checked upstream (an IP address or a
  valid host name, `redirection.c`), so this is a second line, not a hole.
- **The codec is loaded twice.** `h264_decoder_available` creates and frees a decoder context, and
  freeing unloads the library; FreeRDP loads it again when the graphics channel starts, after the
  server has sent data. With `JTERM_OPENH264` the helper holds its own handle, so the second load
  finds it by SONAME and touches no file. Any other codec (a system one, or a runtime extension)
  is searched for in the library folders after lockdown, which Landlock refuses: the session
  silently falls back to RemoteFX (seen). **Phase 3: keep the probed decoder context, and so the
  library, alive until the process ends**, rather than adding library folders to the rules.
- **Inside the Flatpak** (the jterm Flatpak's sandbox, the staged helper, same server) the same
  result: Landlock and seccomp stack on bubblewrap's, every forbidden action fails, H.264 works
  with the downloaded codec. Landlock and seccomp need no Flatpak permission.
- **The Flatpak has no codec today.** The freedesktop **25.08** runtime has no
  `org.freedesktop.Platform.openh264` extension point any more (24.08 has one; 25.08 mounts only
  `codecs-extra`, which has no OpenH264), while `OpenH264.providedByFlatpak()` hides the download
  because it expects that extension. Not a sandbox question, but the sandbox favours the download
  route anyway: it is the case where the helper already holds the library.
- **EL9's kernel has Landlock ABI 6**: `kernel-core-5.14.0-687.66.1.el9_8` builds Landlock in,
  first in `CONFIG_LSM`, and has every hook through ABI 6 (truncate, device ioctl, TCP
  bind/connect, the Unix-socket and signal scopes; not ABI 7's denial logging). Read from the
  package's `System.map`, not run. Older EL9 kernels may report less, which D1 covers.
- **`PR_SET_DUMPABLE` stops `strace` from decoding** the helper's strings (it reads them as a
  debugger would). Tracing needs a preloaded shim that ignores that one call; worth knowing before
  the next "trace it after a FreeRDP bump".

Changes for phase 3 from this: keep the H.264 library loaded; add `/sys/devices/system/cpu/online`
as a read-only file rule (harmless, and the codec's thread count stays right); refuse `/`, `|` and
vsock names in the transport hook; start the reader thread after the lockdown as planned (the shim
couldn't, so Landlock didn't cover that thread in the spike; seccomp did, through `TSYNC`).

## Architecture

### The lockdown point

`main` today: handshake, read CONNECT, `apply_connect`, start the reader thread,
`freerdp_connect`. The sandbox is entered **after `apply_connect` and before `freerdp_connect`**:
the password is in memory, every library is loaded (OpenSSL is initialised, the H.264 decoder
probed by `h264_decoder_available` and the AAC decoder by `aac_probe`), and no byte from the
server has been parsed.

```
HELLO ─ CONNECT ─ apply_connect ─ resolve names ─ ENTER SANDBOX ─ freerdp_connect ─ session
        (trusted input only: jterm's)              │              (hostile input from here on)
                                                   └─ irreversible; inherited by every thread
```

Threads created later inherit Landlock and seccomp. The reader thread is started after the
lockdown for that reason (seccomp's thread-sync flag exists, but starting it later is simpler).
FreeRDP's own threads (rdpsnd's play threads, audin's, drdynvc's) only start inside
`freerdp_connect`, so they inherit it too.

### Name resolution before lockdown

FreeRDP resolves names itself, inside `freerdp_connect`, more than once. Instead of allowing the
resolver's files and sockets in the sandbox, the helper resolves the target and the gateway
**once, before lockdown**, and installs FreeRDP's transport hook (`rdpTransportIo.TCPConnect`,
`freerdp_set_io_callbacks`, present in 3.33's `transport_io.h`) to connect from that cached
answer. No resolver runs in the sandbox.

A server redirect to a name that was not resolved then fails with a clear message ("the server
redirected to another computer, which jterm doesn't follow"). GNOME's login handoff redirects to
the same host and keeps working. See D3.

### Layer 1: hardening without a sandbox (all platforms)

- **Scrubbed environment.** `RdpConnection.start` builds the helper's environment from an
  allow-list (`WLOG_LEVEL`, `JTERM_OPENH264`, `TZ`, the locale, and on Windows `SystemRoot`,
  `TEMP`, `TMP`) instead of inheriting jterm's. That alone removes `SSH_AUTH_SOCK`,
  `DBUS_SESSION_BUS_ADDRESS` and `XDG_RUNTIME_DIR` from the helper, and the variables OpenSSL and
  the dynamic loader honour (`OPENSSL_CONF`, `OPENSSL_MODULES`, `LD_PRELOAD`).
- **Exploit mitigations in the build.** The staged Linux helper and FreeRDP libraries are
  measured to have none: not position-independent, no stack protector, no `_FORTIFY_SOURCE`, lazy
  binding. Add to the helper's `CMakeLists.txt` and to the FreeRDP configure line in each build
  script: `-fPIE -pie`, `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-fcf-protection`
  (x86), `-Wl,-z,relro,-z,now`; with MSVC `/guard:cf`, `/CETCOMPAT`, `/DYNAMICBASE`,
  `/HIGHENTROPYVA`; on macOS the defaults already give PIE and stack protection. A CI step checks
  the result (`readelf` on Linux, `dumpbin /headers /loadconfig` on Windows).
- **No core dumps, no stray handles.** `RLIMIT_CORE` 0 and `PR_SET_DUMPABLE` 0 on Linux (the
  password is in memory; this also closes `/proc/<pid>/mem` to other processes of the user),
  `SetErrorMode` plus Windows Error Reporting excluded on Windows, and every inherited descriptor
  above 2 closed at start.

#### Layer 1 as built

Built 2026-11-13. Where it differs from the plan above, and what was found:

- **The allow-list needs `HOME`** (`USERPROFILE` on Windows). `freerdp_settings_new` fails when
  `GetKnownPath(KNOWN_PATH_HOME)` returns null, so without it the helper couldn't start. Kept:
  `HOME`, `TZ`, `LANG`, `LANGUAGE`, `LC_*`, `WLOG_LEVEL` (inherited, else `WARN`), `TMPDIR` on Unix,
  and `USERPROFILE`, `SystemRoot`, `SystemDrive`, `windir`, `TEMP`, `TMP` on Windows. Written as
  `terminal.rdp.HelperEnvironment` (unit-tested); `smoke-test.py` starts the helper with the same
  list, so a variable it turns out to need fails CI instead of a user. `JTERM_OPENH264` only ever
  comes from the target.
- **The scrubbed environment closes more than the plan listed.** Reading FreeRDP 3.33 for every
  environment read: `https_proxy`/`HTTPS_PROXY`/`no_proxy` make `proxy_prepare` send the
  connection through an HTTP or SOCKS proxy nobody set in jterm; `WTSAPI_LIBRARY` names a library
  WinPR loads; `WLOG_APPENDER`, `WLOG_FILEAPPENDER_*` and `WLOG_UDP_TARGET` send the log to a file or
  the network. All gone now. The proxy one is a user-visible change (an environment proxy no
  longer applies to RDP), documented in `docs/rdp-sessions.md` with the Gateway tab as the way
  round; it also had to go before layer 2, whose port rule would block a proxy anyway.
- **Build flags**: `-fstack-protector-strong -D_FORTIFY_SOURCE=2 -fcf-protection` (x86), PIE and
  `-z relro -z now` on Linux, in the helper's `CMakeLists.txt` and passed as `CMAKE_*_FLAGS` to
  FreeRDP's configure (not through `CFLAGS`/`LDFLAGS`, which CMake reads only on a fresh build
  directory). Windows: `/guard:cf` and `/CETCOMPAT` added, `/DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT`
  spelled out; the FreeRDP configure line repeats CMake's MSVC defaults (`/DWIN32 /D_WINDOWS`,
  `/machine:x64`) because setting those variables replaces them. macOS: only
  `-fstack-protector-strong` (PIE and fortify are already the toolchain's defaults). vcpkg's
  OpenSSL, zlib and cJSON stay without CFG.
- **The CI check is `check-hardening.py`**, one script for ELF (through `readelf`: PIE, RELRO,
  BIND_NOW, no executable stack, `__stack_chk_fail`, a `__*_chk` import, IBT+SHSTK on x86-64) and PE
  (parsed directly, so no Visual Studio prompt and no `dumpbin`: ASLR, high-entropy ASLR, DEP,
  `GUARD_CF`, CET compatibility from the type-20 debug directory). Files jterm builds must pass
  everything; others (the distribution's cJSON, vcpkg's DLLs, the MSVC runtime) only the baseline.
  It runs after every helper build: MSI, RPM (EL9, EL10), DEB, snap. The PE half was checked against
  `objdump -p` on Microsoft- and MinGW-built binaries (no Windows build of the helper was at hand).
  Measured on Linux: the old stage failed every check except RELRO and the stack; the new one
  passes, here and in a Rocky 9 container (gcc 11).
- **Fortify found nothing in FreeRDP.** Two `-Wstringop-overflow` warnings appear with it: a
  bounded `strncat` in `winpr_RemoveDirectory_RecursiveA`, and a `memcpy` in
  `Stream_ResizeToCapacity` that would overflow if a non-owned stream shrank. That function is
  internal to `stream.c` and its only caller only grows the buffer, so both are false positives.
- **Core dumps and descriptors** (`src/sandbox.c`, `sandbox_early()`, the first call in `main`):
  `RLIMIT_CORE` 0/0, `PR_SET_DUMPABLE` 0 (checked: `/proc/<pid>/fd` and `environ` become root's), and
  `close_range(3, ~0)` with a loop as fallback (checked with an inherited fd 7). Nothing is open
  before `main` (the helper's own descriptors are the protocol dup and WinPR's eventfds), so
  closing at entry is safe. The JDK already closes descriptors in the child on Unix; this covers
  any other way the helper is started.
- **Windows crash reports, partly.** An unhandled-exception filter ends the process before WER
  sees it, `_set_abort_behavior` stops `abort()` from reporting, and the CRT's invalid-parameter
  handler ends the process instead of calling Watson. **Not covered:** `__fastfail` (the `/GS`
  check, CFG violations) goes straight to WER and can't be intercepted from inside; only a registry
  exclusion would, and the helper writes nothing. **Inherited handles are not closed on Windows:**
  Java starts the child with `bInheritHandles`, and a process can't list its own handles. Both are
  left to W2's launcher (an explicit `PROC_THREAD_ATTRIBUTE_HANDLE_LIST`).

### Layer 2: Linux

Applied in this order, each one best effort, the result reported (below):

1. `PR_SET_NO_NEW_PRIVS`.
2. **Resource limits:** `RLIMIT_FSIZE` 0, so no regular file can be written or extended, whatever
   the kernel supports (pipes and sockets are unaffected); `RLIMIT_NOFILE` to a few hundred.
3. **Landlock** (kernel 5.13+; this machine: 6.12 with Landlock in the LSM list, ABI 6):
   - *Files (every access right handled):* read-only on the CA stores (`/etc/pki`, `/etc/ssl`,
     `/usr/share/pki`, `/usr/share/ca-certificates`, `/etc/crypto-policies`) and on
     `/etc/localtime` and `/usr/share/zoneinfo`. Nothing else: not the home folder, not `/proc`,
     not `/tmp`, not the helper's own folder (its libraries are already mapped). No write, create,
     delete or execute right anywhere.
   - *Network (ABI 4+):* `connect` only to the TCP ports of this session (the target's, the
     gateway's, or the SSH forward's local port). No `bind`.
   - *Scopes (ABI 6+):* no abstract Unix sockets and no signals outside the helper.
4. **seccomp**, a small deny-list written as a raw BPF filter (no libseccomp, so nothing new to
   bundle), returning `EPERM`: `execve`, `execveat`; `ptrace`, `process_vm_readv`,
   `process_vm_writev`; `socket` for any family other than `AF_INET`/`AF_INET6`, and for any type
   other than stream (so no Unix sockets: the agent, D-Bus, the keyring, the display server; no
   netlink; no UDP); `add_key`, `request_key`, `keyctl`; `bpf`, `perf_event_open`,
   `userfaultfd`, `io_uring_setup`; `mount`, `unshare`, `setns`, `open_by_handle_at`; `kill` and
   `tgkill` aimed at another process.

A deny-list, not an allow-list, on purpose: the helper runs FreeRDP, OpenSSL and a codec against
several distributions' libc, and an allow-list that kills the process on the first unexpected
`futex` variant would trade a security bug for a stream of support cases. Landlock and the limits
carry the file-system guarantee; seccomp removes the kernel surface and the socket families
Landlock can't.

Against the assets: A1 Landlock; A2 seccomp's socket rule plus the scrubbed environment; A3
Landlock and `RLIMIT_FSIZE`; A4 seccomp, Landlock's signal scope, `PR_SET_DUMPABLE`; A5 seccomp
and Landlock (no execute right); A6 Landlock's port rule (any address, but only the session's
ports, and TCP only); A7 the same two rules as A2 and A1: no Unix socket, so no PipeWire or
PulseAudio, and no file rule for `/dev/snd`, so no ALSA device.

**Flatpak** runs the helper inside jterm's own sandbox, which grants the home folder, the network,
the agent socket and, since the sound work, the PulseAudio socket (`--socket=pulseaudio`, which
is playback *and* capture), so all of the above still matters there; Landlock and a second seccomp
filter stack on bubblewrap's (spike: they do). The runtime extension the codec was meant to come
from doesn't exist in 25.08 (spike); with the downloaded codec the Flatpak is the same as the RPM. **Snap** (classic) is an ordinary process.

#### Layer 2 as built (Linux)

Built 2026-11-13 as designed above, with what the spike changed:

- **`src/netroute.c`** resolves the names the session needs before the lockdown (the desktop's, or
  through an RD Gateway only the gateway's: the desktop's name is the gateway's to resolve) and
  installs `rdpTransportIo.TCPConnect`. The hook passes numeric addresses to FreeRDP's own connect,
  replaces a cached name by its addresses (tried in order, so `localhost` → `::1` refused →
  `127.0.0.1` works), asks jterm about a name it hasn't seen (protocol v12: `RESOLVE` /
  `RESOLVE_REPLY`, answered by `terminal.rdp.HelperResolver`, which checks the server-supplied name
  is a plain DNS name before looking it up and returns numeric addresses only), and refuses `/`,
  `|` and `vsock://` names. Only installed where the platform has a lockdown
  (`sandbox_needs_early_resolution`: Linux), so Windows and macOS connect exactly as before.
- **`sandbox_lockdown`** (`src/sandbox.c`) in `main` after `apply_connect` and the resolution,
  before the reader thread and `freerdp_connect`. Landlock's structures and constants are declared
  in the file rather than taken from `<linux/landlock.h>`: the build hosts' headers range from EL9's
  to the snap base's. Read rules as designed plus `/etc/ca-certificates`, `/var/lib/ca-certificates`
  (Arch and openSUSE keep the CA store there) and `/sys/devices/system/cpu/online`. Port rule: the
  desktop's port, or the gateway's. seccomp for x86-64 and aarch64 (other architectures report
  `seccomp=0`); x32 calls are refused, a 32-bit entry point kills. `SIGXFSZ` is ignored so a write
  under `RLIMIT_FSIZE` fails instead of killing.
- **The H.264 probe keeps its decoder context** for the life of the process, so the codec library
  stays loaded (the spike's late reload).
- **Reporting:** the line `jterm-rdp-helper: sandbox level=… landlock=… net=… scope=… seccomp=…
  fsize=… nnp=…` (`level=none` on Windows and macOS), parsed by `terminal.rdp.HelperSandbox` and
  shown in the tooltip of the pane bar's status label. `full` means files, network and system calls
  are all restricted; the Landlock scopes (ABI 6) are reported but not required, since seccomp
  already refuses Unix sockets and signals to other processes.
- **Self-test:** `JTERM_HELPER_SELFTEST=1` (not in jterm's allow-list, so jterm can't pass it) makes
  the helper try 13 forbidden things after the lockdown. `smoke-test.py --expect-sandbox full`
  requires the level and every refusal; the RPM (EL9, EL10) and DEB jobs run it. The Flatpak and
  snap jobs don't run the smoke test yet.
- **Verified:** live sessions against xrdp under the lockdown (RemoteFX and H.264, by address, by a
  name resolved before, by a name only jterm could resolve, and a name nobody resolves, which fails
  as `ERRCONNECT_DNS_NAME_NOT_FOUND`); through `RdpConnection` (v12 handshake, the sandbox report);
  in a Rocky 10 container with the default seccomp profile (full) and with Landlock blocked
  (`partial`, and the smoke test fails).

### Layer 3a: Windows

Java can't start a process with a restricted token, so there are two levels.

**W1, self-restriction** (the helper restricts itself at the lockdown point):
- **Low integrity level** on its own token: no writes to the user's files or registry, no opening
  jterm or any other medium-integrity process (A3, A4).
- **A job object** it assigns itself to: one process only (A5), kill on close (which also ends
  the orphan race noted in the review), and UI restrictions: no clipboard, no global atoms, no
  desktop or display-settings changes, no handles to other processes' windows.
- **Mitigation policies** set at run time: no dynamic code, strict handle checks, no extension
  points, images only from the install folder and System32, no remote images. Win32k lockdown and
  the dynamic-code policy are to be tried against Media Foundation's H.264 decoder before they are
  kept (spike).

W1 does **not** stop reads: a low-integrity process can still read the user's files (A1) and
connect anywhere (A6), and whether it can read Credential Manager entries (A2, the master
password) or open a capture device (A7) is not known (spike).

**W2, a launcher** (a separate step): the helper started by jterm becomes a tiny outer process
that starts the real one with a restricted token (restricting SIDs in the manner of Chromium's
"limited" level, so files granted only to the user are unreadable while `Program Files` and
System32 stay loadable) and hands it the pipe handles, then only waits. That closes A1 and A2. An
AppContainer would be stronger still and also bounds the network, but it blocks loopback by
default, which is how the SSH route reaches the desktop; it needs its own investigation.

#### W1 spike and as built (2026-11-13)

Spike on the Win11 VM (a standalone program applying W1 to itself, then trying things), run in an
interactive session as a scheduled task, since an SSH key logon has no password and so no
Credential Manager. jterm's side was played beforehand at medium integrity: a generic credential
written, clipboard text set. The microphone was a redirected one (a session with `audin` open).

| | unrestricted | W1: low integrity + job + policies | restricted token (W2's mechanism) |
|---|---|---|---|
| create a file in the profile, write HKCU | allowed | **blocked** | blocked |
| open jterm's process | allowed | **blocked** | allowed (integrity not lowered, see below) |
| start a process | allowed | **blocked** (job) | allowed; blocked with W1's job on top |
| read the clipboard | allowed | **blocked** (job) | allowed; blocked with W1's job on top |
| read / enumerate Credential Manager | allowed | **allowed** | blocked |
| read the user's files | allowed | **allowed** | blocked |
| open the microphone | allowed | **allowed** | blocked (the device isn't even listed) |
| Media Foundation H.264 after lockdown | works | works, also with no dynamic code | works |

- **Win32k lockdown can't be switched on in a running process** (`SetProcessMitigationPolicy`
  fails), so W1 doesn't have it.
- **W1 doesn't protect the master password.** jterm's Windows keyring keeps it in Credential
  Manager, and a low-integrity process reads it; with the vault file (readable too) that is
  everything. This is the W2 trigger named when D2 was decided.
- **W2 is smaller than this spec assumed.** A process may start a child with a restricted copy of
  its own token without any privilege, so the helper can relaunch *itself* (no separate launcher
  binary); H.264 kept working. Lowering the integrity level from inside a restricted token fails, so
  the launcher sets it on the new token before starting the child. A per-user install directory
  (ACL: the user only) would be unreadable to the restricted child: loading its DLLs needs
  Chromium's trick of starting the main thread impersonating the normal token and reverting at the
  lockdown point.

**As built** (`src/sandbox.c`, Windows section): at the lockdown point a job object (one process,
kill on close, die on unhandled exception, UI limits: clipboard, global atoms, USER handles,
desktops, display and system settings, logoff), four mitigation policies (no dynamic code, strict
handle checks, no extension points, image loads: no remote, no low-label, System32 first), then low
integrity. Reports `sandbox level=partial integrity=low job=1 mitigations=4`; `HelperSandbox` words
it as "partial (it can't change your files or reach other programs, but can still read files)". No
name resolution change (the resolver works under W1). The self-test covers W1's promises only:
create a profile file, write HKCU, open the parent (its id taken before the lockdown: afterwards the
process snapshot no longer lists it), start a process, read the clipboard; the MSI job's smoke test
runs `--expect-sandbox partial`. Verified on the VM: built with the VS 2022 Build Tools, hardening
check and smoke test pass, a live session to xrdp (through an SSH reverse tunnel) decodes H.264 with
Media Foundation under the lockdown (screenshot checked), sound and clipboard channels come up, and
`RdpConnection` (the Java side, with `HelperEnvironment`'s Windows list) gets the report.

#### W2 as built (2026-11-13)

`src/launcher_win.c`, called first in `main` after `sandbox_early`. The helper jterm starts becomes
the launcher: it builds a lockdown token (no privileges; the user's SID, Administrators and "local
account and member of Administrators" deny-only; restricting SIDs Everyone, Users, RESTRICTED and
the logon SID; low integrity; a default DACL granting SYSTEM, RESTRICTED and the logon SID, since
the user's SID is deny-only) and a start-up token (a restricted token whose restricting SIDs are
all of the user's, so the same access, but restricted: Windows won't let a restricted process
impersonate an unrestricted token; low integrity). It starts itself again suspended with the
lockdown token and `--jterm-restricted-child`, working directory System32, inheriting only the
three standard handles (`PROC_THREAD_ATTRIBUTE_HANDLE_LIST`), puts the child in a job (one process,
kill on close, die on unhandled exception, the UI limits), sets the start-up token on its main
thread, resumes it, closes its own pipe ends and waits, returning the child's exit code. The child
reverts to the lockdown token in `sandbox_lockdown`, reports `token=restricted`, and resolves names
before the lockdown as Linux does (netroute). If the relaunch can't be set up, the helper logs why
and carries on with W1 alone.

Found while building it:
- **NLA through Windows' SSPI fails under the restricted token** (`AcquireCredentialsHandle`:
  `SEC_E_NO_CREDENTIALS`): LSA won't serve it, and NLA runs after the lockdown, again on every
  reconnect and on a redirect. The child therefore sets `WINPR_NATIVE_SSPI=0`, so WinPR's own NTLM
  runs in-process, as on Linux and macOS. **Windows loses Kerberos** for NLA (WinPR is built
  without it), which matters where a domain restricts NTLM. Reverting the token only after NLA
  would keep Kerberos for the first connection but break reconnects and RDS redirects.
- **Per-session opt-out, "Use Windows sign-in (Kerberos)"** (`RdpSessionConfig.useKerberos`, the
  dialog's Security tab, Windows only; never exported, ignored on import like `useMicrophone`).
  jterm passes it as `JTERM_RDP_KERBEROS=1` in the helper's environment (`HelperEnvironment`): the
  relaunch is decided before CONNECT is read. Such a helper skips the relaunch, keeps Windows' SSPI
  and runs with W1 only (tooltip: the W1 wording). Checked on the VM: no relaunch, `token=normal`,
  `AcquireCredentialsHandle` succeeds. **Not checked: a completed NLA through Windows' SSPI at low
  integrity.** Loopback NLA to the VM itself fails at the second `InitializeSecurityContext`
  (`SEC_E_UNKNOWN_CREDENTIALS`) with `DisableLoopbackCheck` set too, and identically with no
  restriction at all (a test-only build that skipped the lockdown), so this setup can't show it
  either way: the client ran in an SSH key logon, dialling the machine itself. **Settled the same
  day from a separate (work) laptop** with a test tool (`nlacheck`: the helper driven like jterm,
  one sign-in per mode) against the VM by DNS name: the Kerberos-switch mode (W1, Windows' SSPI)
  and the default mode (W2, WinPR's NTLM) both signed in and received frames. The VM is in a
  workgroup, so Windows' SSPI used NTLM there; **actual Kerberos against a domain server is still
  untested.**
- A nested job can't carry UI restrictions (`ERROR_NOT_SUPPORTED`), so the child doesn't make its
  own; the launcher's applies.
- The self-test gains `list-profile` and `credential-manager` (blocked only on a real refusal: "no
  credentials" means the store was readable).

Verified on the VM (live sessions driven by a protocol script): against Windows 11 itself
(`127.0.0.2`, NLA as `claude` with WinPR's NTLM) AVC444 decoded by Media Foundation, sound as AAC
decoded after the lockdown (482 chunks), the remote clipboard announcing copied text, drive
requests on a shared folder, a microphone stream opened by a recording program (44.1 kHz stereo);
against xrdp through an SSH tunnel by address, by a name resolved before the lockdown and by one
only jterm resolved; the smoke test with all seven self-test refusals; killing the launcher kills
the child. Not done: jterm's GUI on Windows (needs someone at the screen), an RD Gateway, a real
redirect.

### Layer 3b: macOS

A Seatbelt profile applied with `sandbox_init` at the lockdown point: deny by default; read
access to the system libraries and frameworks; outbound TCP; the Mach services VideoToolbox needs
(to be found by running it, as every other client of that API has had to), and whatever
AudioToolbox's AAC converter needs; no file writes, no `process-exec`, no access to `securityd`
(the keychain, where jterm's master password is kept), and no `device-microphone` or audio
server (`coreaudiod`) access (A7): jterm records, never the helper.
`sandbox_init` with a custom profile is deprecated but is what browsers and system daemons use;
there is no supported replacement for a process that isn't an App Store app. Needs a real Mac,
Apple Silicon included, and a check against the Local Network permission prompt.

### Reporting and failure policy

The helper logs one line at the lockdown point, in the form the statistics line already uses:
`jterm-rdp-helper: sandbox landlock=6 net=1 seccomp=1 fsize=1` (or `integrity=low job=1 …`).
jterm parses it and shows the level in the session's tooltip in the pane's bar ("Helper sandbox:
full", "partial: this kernel has no Landlock network rules", "none"). No protocol change.

A kernel without Landlock, or a container that forbids it, gets what it supports and says so
(D1). **CI is strict where users can't be:** `smoke-test.py --expect-sandbox <level>` fails a
package whose helper reports less than that target should give.

## Testing

- **Self-test mode** (`JTERM_HELPER_SELFTEST=1` in the helper's environment, which jterm never
  sets; it only makes the helper attempt things that should fail): after lockdown the helper tries
  each forbidden thing and reports what happened: open a
  file under the home folder, create one in the temp folder, open a Unix socket, connect to a
  port outside the session's, `execve` a shell, signal its parent, open a capture device (a node
  under `/dev/snd` on Linux, the default input through WASAPI and CoreAudio). `smoke-test.py` requires every
  one to fail, in every package (RPM, DEB, Flatpak, snap, MSI, DMG), from inside the finished
  package.
- **Does it still work:** the live matrix of the session design, with the sandbox on: Windows 11
  and GNOME Remote Desktop servers; NLA and TLS-only; through SSH; through the (fake) gateway;
  H.264 with the downloaded codec, the Flatpak extension and Media Foundation; clipboard, drives
  and file copy; sound as PCM and as AAC through each decoder (libfaad, Media Foundation,
  AudioToolbox), with a decoder opened for the first time *after* lockdown; the microphone;
  reconnect; a server redirect.
- **`strace -f` on a live session** before writing the rules, to replace the "not traced" rows of
  the table above with measurements.

## Phasing

1. **Layer 1** everywhere: environment, build flags, core dumps, descriptors. Ships on its own.
   *Built 2026-11-13* ([as built](#layer-1-as-built)); one behaviour change after all, the
   environment proxy.
2. **Spike, Linux:** a live session under `strace`; Landlock and the filter by hand; the Flatpak
   codec path; EL9's kernel (5.14: which Landlock ABI, if any). *Done 2026-11-13*
   ([results](#phase-2-spike-linux-2026-11-13)).
3. **Linux build:** name resolution and the transport hook, the lockdown, reporting, the
   self-test, CI. Includes the resolve-by-jterm message for redirects to other names (D3).
   *Built 2026-11-13* ([as built](#layer-2-as-built-linux)); the live matrix against Windows and
   GNOME servers is still to do.
4. **Windows W1**, with the Media Foundation and Credential Manager questions answered first.
   *Built 2026-11-13* ([spike and as built](#w1-spike-and-as-built-2026-11-13)): Media Foundation
   works; Credential Manager does **not** stay closed under W1.
5. **macOS profile**, on the Intel macOS VM (D5: no Apple Silicon hardware).
6. **Windows W2** (launcher and restricted token), as its own design once W1 has run for a while.
   *Built 2026-11-13 after all*, right after W1, because W1's spike found Credential Manager
   readable ([as built](#w2-as-built-2026-11-13)). NLA on Windows is NTLM-only since.

## Sound and microphone

Added 2026-11-12 from the audio design (`2026-11-11-rdp-audio-design.md`), revised 2026-11-13.

**Nothing has to be loosened.** The helper opens no sound device, socket or service; samples travel
over the existing pipes (`AUDIO_*`, `MIC_DATA`, and since protocol v11 `AUDIO_DELAY`, a number
from jterm). Traced: see "What the helper needs".

**What it adds to protect** is A7, the microphone, above.

**What it adds to the helper's attack surface** is an AAC decoder parsing server bytes, inside the
helper, when the session plays sound and the Preferences option is on. On Linux that is FAAD2,
with a long advisory history (the audio design's D3). It is the kind of code this sandbox is for,
and until the sandbox exists a decoder bug there has the user's full rights. The decoder's
configuration is built by jterm and the frame size is capped, but the frames themselves are the
server's.

What the decoders need from each layer:

- **Linux:** `aac_probe()` runs inside `apply_connect`, i.e. before the lockdown point, so
  `libfaad.so.2` is already mapped (by absolute path from a root-owned system directory) and needs
  no file rule. A decoder instance is created later, on rdpsnd's play thread, and opens nothing.
- **Windows:** Media Foundation's AAC decoder MFT joins the H.264 decoder in the list of things to
  try under the W1 mitigation policies. `MFStartup` runs at probe time with `MFSTARTUP_LITE` (no
  sockets). The MFT is created again on rdpsnd's play thread **after** lockdown (`CoInitializeEx`
  and `CoCreateInstance` of an in-process object): check that the image-load and dynamic-code
  policies and the low integrity level allow it.
- **macOS:** AudioToolbox's converter is created at probe time and again per stream after lockdown;
  the Seatbelt profile may need a Mach service for it, to be found by running it.
- **Flatpak:** no `libfaad` in the runtime; `aac_ffmpeg.c` opens the runtime's `libavcodec` (by
  absolute path, on the runtime's read-only mount) in the same place, before the lockdown, and the
  decoder instance is created on rdpsnd's play thread like FAAD2's. Measured 2026-11-15: smoke test
  `AAC available` with the sandbox `level=full`; a harness decoding raw AAC-LC frames through it
  inside the Flatpak gives the right tone at 22.05/44.1/48 kHz, mono and stereo, and refuses 2000
  random frames without crashing.

## Related findings

From the review of 2026-11-11, not part of this design but on the same boundary: the certificate
prompt takes helper-supplied strings unsanitised and keys pins by the helper's host name; the EDT
writes to the helper's pipe directly; directory listings are capped per handle but not in total.
A sandboxed helper makes the first of these matter more, because the message protocol becomes the
only way left to attack jterm.

## Decisions

Answered 2026-11-13.

| | Question | Proposal | Why |
|---|---|---|---|
| D1 | A system that can't apply the full sandbox | Run with what it supports and show the level; CI fails a package that gives less than expected. *Accepted.* | Refusing to connect on an older kernel or in a locked-down container punishes the user for something they can't change. |
| D2 | Windows | W1 now, W2 as a separate design. *Accepted: W1. W2 is not scheduled.* W1 built 2026-11-13; its spike found Credential Manager readable under W1 (the W2 trigger); W2 built the same day on request. | W1 is a day's work inside the helper; W2 is a new process model that needs Media Foundation and handle-inheritance testing. |
| D3 | Name resolution | The helper resolves before lockdown and connects from the cached answer. *Accepted, with the fallback built in phase 3, not deferred:* a redirect to a name that wasn't resolved asks jterm to resolve it over a new protocol message, because RDS farms (a connection broker redirecting to a session host by name) must work for professional users. | No resolver in the sandbox and no protocol change. The alternative, asking jterm to resolve over a new message, is the fallback if redirects to other hosts turn out to matter (RDS farms). |
| D4 | UDP | Denied. *Accepted.* | Nothing uses it today. FreeRDP's experimental UDP transport would need the rule opened when it is adopted. |
| D5 | macOS | After Linux and Windows W1. *Accepted, but no Mac will be bought:* tested on the Intel macOS VM only, so a Mach service only Apple Silicon needs can go unnoticed. | The Mach-service list can't be written from documentation. |
| D6 | Order | Layer 1 first, as its own change. | It is cheap, has no failure mode, and closes the environment leak at once. *Done that way, 2026-11-13.* |
