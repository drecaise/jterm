# RDP drive redirection, file copy and clip-data locking

**Status:** designed, 2026-11-09. Decisions D1–D8 taken the same day (end of this document).
Phase 1 (drive redirection) built 2026-11-09, protocol v7; verified against a live Windows 11
server 2026-11-10.
Phase 2 (file copy, local to remote, with server locks) built 2026-11-10, protocol v8; verified
against the live Windows 11 server the same day. Phase 3 (remote to local, client locks) built
2026-11-10, protocol v9; verified against the live Windows 11 server the same day.
**Builds on:** `2026-11-07-rdp-session-design.md` (the helper, its protocol, the clipboard bridge).

## Where to pick up (end of 2026-11-10)

Phase 1 passed the live test against the Windows 11 VM (`172.16.1.122`): both drives appeared in
the remote Explorer, files and subfolders opened, the symbolic link to `/etc` was absent, a copy
onto the read-only share was refused as write-protected, and on the read-write share copying in,
renaming, deleting, creating a folder and copying a large file all worked.

Phase 2 passed its live test after one fix (below): one prompt per copy, a refusal holding
while Windows retried the request 21 times within two seconds (each refused at once, unasked),
files and folders arriving intact, and 1 207 file-contents requests with none failing.

Phase 3 passed its live test after two fixes (below), with "Copy files to this computer" on:

1. Copy a file in the remote Explorer: "Save files… (1 file, …)" appears in the pane's bar and
   nothing is written. Save into a test folder; the file arrives intact. Then a folder tree and
   a file over 4 GiB.
2. Copy text on the remote desktop: the button goes away.
3. Client lock: start saving a large file, copy something else remotely meanwhile; the save
   must finish with the original (Windows announced `CB_CAN_LOCK_CLIPDATA` in phase 2's test).
4. Save into a folder that already holds one of the files: the Yes / Yes to All / No / No to All
   / Cancel prompt; "No" leaves the old file.

Fixes from that test:
- **The progress window is modeless.** Modal, it made test 3 impossible: the remote desktop
  could not be used during a save. "Save files…" is disabled while a save runs instead.
- **A read under a lock no longer needs files on the current clipboard.** The helper refused any
  read while the server's clipboard held no files, so copying *text* during a save failed it at
  once, while copying another file passed by luck. A locked read names the locked copy, which the
  server keeps; the helper now lets it through. A 420 MiB save with two text copies and a file
  copy made during it arrived byte-identical, and each lock was released when its save ended.

Phase 3, as built:
- **The list only, until Save.** A server copy that holds files is locked at once (the server
  agreed to locking), and its list fetched with the rest of the copy and parsed in Java
  (`RemoteFileList`): names read inside their field, every component Windows-valid, repeats and
  unusable names left out and counted, never renamed. The button shows the counts and size.
- **Protected folders are refused as destinations and inside them**, by path as well as identity
  and ignoring case, before anything is created: a copied `.ssh\authorized_keys` saved into the
  home folder would otherwise plant a key on a machine with no `~/.ssh` yet.
- Each file goes to a hidden temporary name in its folder and is renamed when complete; an
  existing link on the way fails the entry. The size is asked separately and the smaller figure
  read; a short range fails the file.

Built as designed, with these decisions made while building it:
- **A copy is one announcement.** The answer (yes or no) belongs to the announced copy, and jterm
  announces only a *changed* clipboard (on a clipboard event, or on focus gain when the content
  differs). The server can never cause an announcement, so it can't re-ask to wear the user down.
  A copy that can't be sent (over the caps, a name Windows can't hold) is explained once. The
  snapshot is walked again on every paste, keeping the answer.
- **First live test (2026-11-10)** found the prompt's own focus change re-announcing the clipboard:
  a "no" was asked again at once (Windows retries a refused list request twice), every paste asked
  twice, and an announcement landing just after an allowed paste started broke it ("unspecified
  error" in Windows, which unlocks the data it was reading). Fixed by announcing on focus gain only
  when the content changed.
- **The prompt defaults to "Don't allow"**, and names the first three copied entries.
- Protected folders (the vault's folder, `~/.ssh`) are left out of a snapshot wherever they
  appear, like links, and counted as "left out" in the prompt.
- The helper also clears `CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES` from FreeRDP's
  `ClipboardFeatureMask` without the opt-in, so FreeRDP's own channel refuses file requests too.

**CI and Windows (2026-11-11).** The `Release` workflow built every target from `a6354e0`
(MSI, both DMGs with the in-image smoke test, both RPMs, DEB, Flatpak, snap), so the helper code of
protocol v7 to v9 compiles everywhere. The RDP unit tests were then run on the Windows 11 VM
(146 pass), with a new Windows-only class, `WindowsFileSystemTest`, for what the other tests can't
make there. It found two defects, both fixed:

- **"Save files…" wrote through a junction.** To Java a junction is not a symbolic link *and is a
  directory* (`isDirectory()` and `isOther()` are both true), so `RemoteFileTransfer.folder`, which
  asked only "symbolic link?" and "directory?", let it through. It now refuses `isOther()` too, as
  `DriveShare` always did (shares and local-to-remote copies were not affected).
- **The mark of the web was never written.** `OriginMark` named the `Zone.Identifier` stream as a
  `Path`, which Java refuses (`InvalidPathException` for the `:`), and the best-effort catch hid it.
  It now goes through `java.io`, and the test reads the stream back.

Confirmed there as designed: junctions refused and hidden in shares and left out of a local copy,
a protected folder refused under another case, stream names refused.

**Still untested:** `DosFileAttributes` handling against a live server, the macOS quarantine
attribute, and a share on a volume without streams.

## Summary

- **Drive redirection:** share named local folders with the remote desktop as drives
  (`\\tsclient\<name>`). Per session, off by default, read-only or read-write per folder.
- **File copy through the clipboard:** copy files on one side and paste them on the other. Each
  direction is a separate opt-in.
- **Clip-data locking:** built as part of file copy, in both roles.

One rule runs through all three: **the helper never touches the local file system and never
learns a local path.** FreeRDP's own drive device (`channels/drive`) and its clipboard-file code
(`client/common/client_cliprdr_file.c`) are not used. The helper relays the protocol; jterm parses
it in Java and makes every file-system decision.

## Why not FreeRDP's drive device and clipboard-file code

Using them would be one setting each. They are rejected on the evidence:

- **Confinement is lexical only.** `drive_file.c` joins the share root and the server's path, then
  rejects `..` components (`contains_dotdot`). Nothing checks symbolic links, junctions or other
  reparse points, and the open (`CreateFileW`, which is `open()` on Linux and macOS) follows them.
  A link already inside a shared folder (`share/home -> /home/mark`) hands the server everything it
  points at.
- **The code keeps producing advisories.** FreeRDP's published advisories for 2026 include, for the
  drive device: a shared-root boundary bypass through non-rooted paths (GHSA-8xqm-wp3f-rfp9, July),
  a malformed request that silently stops the drive worker (GHSA-5qqp-5ffm-m3p9), and failed
  creates that retain handles (GHSA-jgw9-wqvx-j495). For clipboard files: a server-supplied
  `cFileName` that traverses out of the base path with `..` (GHSA-c3rh-2hv6-7hf2), an unterminated
  `cFileName` read out of bounds (GHSA-jp2r-gm4v-wvq2), and two Windows-client overflows
  (GHSA-m37j-jcr2-8gcc, GHSA-pvgq-84w2-93ph).
- **It needs the local paths inside the helper**, which is the process exposed to the server.

## Threat model

**The server is untrusted**, or impersonated after a certificate was accepted by mistake. It
controls every path, name, size, offset, flag and the timing of every request.

**What is protected:** files outside the shared folders; jterm's config, the vault and `~/.ssh`;
the machine itself, through files the server writes that the user later opens; free disk space;
the JVM's memory; whatever is on the local clipboard.

**Not in scope:** local processes. They already run with the user's rights.

**Properties the design must keep:**

| | Property |
|---|---|
| P1 | The server can name only paths beneath a share root. Links are never followed. |
| P2 | A read-only share has no code path that writes, creates, deletes, renames or changes attributes. |
| P3 | Nothing leaves the machine except what a share exposes or a paste the user allowed. |
| P4 | New parsing of server-controlled data happens in Java (memory-safe, unit-tested), never in new C. FreeRDP's `rdpdr` and `cliprdr` cores, which do the framing, are the only C involved. |
| P5 | Every resource the server can make jterm hold is capped: open handles, pending requests, locks, file-list size, free disk space. |
| P6 | The helper holds no local paths and opens no local files. That later allows sandboxing it with no file-system access at all (Landlock on Linux, AppContainer on Windows), a follow-up this design enables but does not include. |

## Architecture

```
                      helper (C, FreeRDP)                          jterm (Java)
server ── rdpdr ──▶ rdpdr core ─▶ jterm drive device ══ pipe ══▶ RdpDriveService
                                  (forwards IRPs, ~150 lines)      MS-RDPEFS parser, NIO file
                                                                   system, share policy
server ── cliprdr ─▶ cliprdr core ─▶ clipboard relay ═══ pipe ══▶ RdpClipboardBridge
                                     (formats, file contents,      + RdpFileClipboard
                                      locks)                       (descriptors, transfers)
```

### Helper: a forwarding drive device

- **Registration.** The helper installs an add-in provider (`freerdp_register_addin_provider`)
  that returns its own `DeviceServiceEntry` for the `drive` service and delegates every other name
  to the provider that was there before. FreeRDP's drive code is then never loaded.
- **CONNECT (protocol v7)** carries the share list: per share a numeric id, a DOS name (up to
  7 ASCII characters, unique, chosen and sanitised by jterm) and a display name. **No paths and
  no access flags**: read-only is enforced in Java.
- `FreeRDP_DeviceRedirection` is turned on only when the session shares at least one folder.
  Printers, smart cards, serial and parallel ports are never added.
- **Per IRP**, the device sends `DRIVE_IRP` {deviceId, fileId, completionId, major, minor, input
  bytes} and keeps the IRP in a pending table. jterm answers with `DRIVE_IRP_DONE` {completionId,
  ioStatus, output bytes}; the helper appends the output and completes the IRP. FreeRDP's own drive
  completes IRPs from its worker thread, so completing from the reader thread is supported.
- **Caps:** 256 pending IRPs (beyond that, `STATUS_INSUFFICIENT_RESOURCES` at once, never queued
  without limit). Write payloads fit the existing inbound frame cap. Everything pending is
  discarded at disconnect.

### Java: `RdpDriveService` (new, `terminal.rdp.drive`, Swing-free)

**Operations:** the set FreeRDP's drive implements, so Windows Explorer, `copy`, `robocopy` and
Office behave the same:

| IRP | Handled |
|---|---|
| CREATE, CLOSE, READ, WRITE | yes |
| QUERY_INFORMATION | Basic, Standard, AttributeTag |
| SET_INFORMATION | Basic, EndOfFile, Allocation, Disposition (delete), Rename |
| QUERY_VOLUME_INFORMATION | Volume, Size, FullSize, Attribute, Device |
| DIRECTORY_CONTROL | query directory (Directory, FullDirectory, BothDirectory, Names); change notification not in v1 (`STATUS_NOT_SUPPORTED`, so Explorer needs F5 to see local changes) |
| LOCK_CONTROL | success without locking, as FreeRDP does |
| DEVICE_CONTROL | `STATUS_NOT_SUPPORTED` |

**Path resolution, every request:**

1. Decode the UTF-16 path, length-checked against the IRP, capped at 32 767 characters.
2. Split on `\`. Refuse empty components, `.`, `..`, `:` (alternate data streams), control
   characters, and wildcards in a CREATE target. On a Windows host also refuse reserved device
   names (`CON`, `NUL`, `COM1`…) and trailing dots or spaces.
3. Walk from the share root one component at a time with `NOFOLLOW_LINKS`. A component that is a
   symbolic link, junction or other reparse point (`isSymbolicLink()` or `isOther()`) fails the
   request with `STATUS_ACCESS_DENIED`. The final open uses `NOFOLLOW_LINKS` too.
4. Directory listings omit links, so the server never learns they exist.
5. **Protected folders** (D4) are refused and left out of listings wherever they appear inside a
   share, compared by real path: jterm's config folder (`AppPaths`, which holds the vault) and
   `~/.ssh`. So sharing the home folder, which D4 allows after a warning, still never exposes the
   vault or the SSH keys.

The share root is resolved once at connect (`toRealPath`), so a root the user chose through a link
is fine, and is fixed for the connection. Check-then-open is enough here because the server cannot
create links (MS-RDPEFS offers no link operation and FreeRDP's set-information handles none), and
local processes are out of scope.

**Handles:** FileId → open `FileChannel` or directory cursor, capped at 1 024 per session, all
closed at disconnect. Requests on one FileId run in order; different FileIds run on a small
per-session pool (4 threads).

**Read-only shares:** CREATE with any disposition other than open-existing, or any access mask
with write, delete or attribute bits, is refused; SET_INFORMATION is refused
(`STATUS_ACCESS_DENIED`); the volume reports `FILE_READ_ONLY_VOLUME`, so Windows shows the drive
as read-only up front.

**Writes:** refused with `STATUS_DISK_FULL` when the write would leave the volume with less than
1 GiB or 5 % free, whichever is larger, so a server cannot fill the disk. Files the server creates
are marked as downloaded (D5): on Windows a `Zone.Identifier` stream with `ZoneId=3`, on macOS
the quarantine attribute. Marking is best effort (a FAT volume has no streams) and never blocks the
write.

**Case:** Windows hosts are case-insensitive already. On Linux and macOS a path that doesn't match
exactly falls back to a case-insensitive match in its parent directory only when that match is
unique.

### File copy through the clipboard

**Wire format** (MS-RDPECLIP): a `FileGroupDescriptorW` format (registered, like "HTML Format")
holding a count and 592-byte `FILEDESCRIPTORW` entries, then `FileContents` requests for each
file's size (`FILECONTENTS_SIZE`) and byte ranges (`FILECONTENTS_RANGE`). Capabilities announced:
`CB_STREAM_FILECLIP_ENABLED`, `CB_FILECLIP_NO_FILE_PATHS` (descriptors carry names only, never a
local path), `CB_HUGE_FILE_SUPPORT_ENABLED` (files over 4 GiB) and `CB_CAN_LOCK_CLIPDATA`.

**Helper:** relays descriptor lists as raw bytes (a new clipboard kind, FILES), and relays
file-contents requests, responses and lock/unlock messages both ways. It reports the server's
capability flags in a new `CLIP_CAPS` message. It never parses a `FILEDESCRIPTORW`.

#### Local to remote: copy on this computer, paste on the remote desktop

- **Offer:** when the local clipboard holds a file list (`javaFileListFlavor`, which is what
  Explorer, Finder and Nautilus copy), FILES is announced, if the direction is on (D2).
- **Descriptor request:** jterm takes a **snapshot**: the copied entries walked recursively,
  links not followed, at most 10 000 entries, depth 32, names up to 259 characters. Directories get
  their own entries (`FILE_ATTRIBUTE_DIRECTORY`), files their size and write time, names are
  relative (`folder\file.txt`). **Consent (D2):** this direction has its own session setting,
  off by default, and the first request for a given copy asks: "The remote desktop wants to paste
  3 files (2.1 GB) from your clipboard. Allow?" Nothing is described to the server before the
  answer; a refusal fails the request, and the answer holds for that copy only.
- **File contents:** `lindex` must index the snapshot, so the server can only ever name a file the
  user copied. A size request answers 8 bytes; a range request reads up to 4 MiB at the position.
  The file is opened `NOFOLLOW_LINKS`, and if its size or write time differs from the snapshot the
  request fails, rather than sending something the user didn't copy.

#### Remote to local: copy on the remote desktop, paste on this computer

- The server's `FileGroupDescriptorW` makes FILES part of the remote offer. Nothing is written
  locally on a copy (D1): jterm fetches only the list, and the RDP pane bar shows **"Save files…
  (3 files, 2.1 GB)"**. Choosing it asks for a destination folder, then transfers. The list goes
  stale on the next server announcement, and the button with it.
- **Names are hostile.** Each `cFileName` is read inside its 520-byte field (the terminator is not
  trusted), split on `\`, and validated by `SftpTransfer`'s existing component check plus the
  Windows rules on a Windows host. Directory entries create directories. Every resolved path is
  asserted to be inside the destination. Files are written under a temporary name and renamed when
  complete. Conflicts use `SftpTransfer`'s Yes / Yes to All / No / No to All / Cancel prompt.
  Written files are marked as downloaded (D5).
- **Sizes are hostile.** Each file's size is asked for (`FILECONTENTS_SIZE`) and reading stops at
  the smaller of that and the descriptor's size. At most 10 000 entries. The total is shown before
  anything is written.
- **Transfer:** 1 MiB ranges, up to 4 in flight. Progress and Cancel through the `SftpTransfer`
  dialog.

### Clip-data locking

Locking exists because file content is fetched in pieces after the copy, and the clipboard can
change in between.

- **jterm pastes from the server (client lock).** If the server announced `CB_CAN_LOCK_CLIPDATA`,
  jterm sends Lock with a fresh `clipDataId` before the first range, puts that id in every
  file-contents request, and sends Unlock when the transfer ends, fails, is cancelled, or the
  session closes. Without locking support, a new server format list during the transfer aborts it
  with "the remote clipboard changed during the copy", instead of mixing two copies into one file.
- **The server pastes from jterm (server lock).** jterm announces `CB_CAN_LOCK_CLIPDATA`. A Lock
  pins the snapshot current at that moment under the `clipDataId`; requests carrying the id are
  served from it, even after the user copies something else locally; Unlock releases it. A
  snapshot is only a list of paths, sizes and times, so locks are cheap, but they are capped at 16
  (the oldest is dropped) and all are released at disconnect, so a server cannot pin unbounded
  state.

## UI

- **Session dialog, "Local resources" tab** (which already holds the clipboard options):
  - *Shared folders:* a table of Folder · Name on the remote · Read-only, with Add… and Remove.
    New rows are read-only (D3). The name defaults to the folder's name and is sanitised to a
    valid DOS name. Choosing jterm's config folder or `~/.ssh`, or a folder inside either, is
    refused; choosing the home folder itself or a drive or system root asks for confirmation (D4).
    A permanent
    warning: "The remote computer can read every file in these folders (Windows may scan them by
    itself, for thumbnails and search). Folders not marked read-only can also be changed and
    deleted."
  - *Clipboard:* "Copy files to this computer" and "Paste my files on the remote", under the
    existing Share clipboard option, which they require.
- **RDP pane bottom bar:** a "Shared: 2 folders" indicator while connected, whose tooltip lists
  each folder with its access, so it is always visible what the server can reach.
- **Transfers:** the `SftpTransfer` progress dialog, titled for RDP.

## Persistence

`RdpSessionConfig` gains `driveShares` (a list of {path, name, readOnly}), `clipboardFilesToLocal`
and `clipboardFilesToRemote`. The class is `@JsonIgnoreProperties(ignoreUnknown = true)`, so an
older build reads such a file and simply shares nothing; no schema bump is needed.
`SessionExportService` leaves `driveShares` out of an export (D6); the two clipboard settings,
which name no local path, are exported like the existing ones.

## Platform notes

- **Flatpak:** `--filesystem=home` only, so folders outside the home directory can't be shared.
  The dialog says so when one is picked (the document portal would lift this later).
- **Snap** (classic confinement): no restriction.
- **Windows:** junctions and every other reparse point count as links (refused); alternate data
  streams are refused by the `:` rule.
- **macOS:** marking downloads (D5) uses the `com.apple.quarantine` extended attribute; JDK
  support for extended attributes on macOS needs checking before relying on it.

## Testing

- **Java, headless:** MS-RDPEFS request fixtures run against temporary directories. Traversal
  (`..`, `\..\`, mixed separators, ADS, links, a junction on Windows CI, reserved names, overlong
  names), the read-only matrix (every write path refused), handle and pending caps, the disk floor.
  `FILEDESCRIPTORW` parsing and generation with malformed and random input. Lock bookkeeping and
  eviction.
- **Helper:** the forwarding device under the existing fake-server smoke harness: the device is
  announced, an IRP reaches jterm, its completion reaches the server.
- **Live, Windows 11 VM:** Explorer copy into and out of `\\tsclient\<name>`; `robocopy` of a tree;
  a file over 4 GiB; a read-only share refusing a write; clipboard copy of a folder both ways;
  copying again locally in the middle of a remote paste (server lock); copying again on the remote
  in the middle of a local paste (client lock, or abort without it).

## Phasing

Each phase ships on its own:

1. **Drive redirection** (helper device, protocol v7, `RdpDriveService`, dialog, pane indicator).
2. **File copy, local to remote**, with server locks.
3. **File copy, remote to local**, with client locks.

In this order (D8): drive redirection alone already moves files both ways through the remote
Explorer.

## Decisions

All taken 2026-11-09, each as recommended.

| | Question | Decision | Why |
|---|---|---|---|
| D1 | When do files copied on the remote desktop arrive locally? | Explicitly: "Save files…" in the pane, with a folder chooser. | Downloading on every remote copy pulls gigabytes for copies meant for the remote side; a paste-time download freezes the pasting application and needs a staging folder. |
| D2 | What consent does the server need to read files from the local clipboard? | A separate opt-in, off by default, plus a prompt per copy. | Unlike text, a server could otherwise pull every file the user copies locally, even for a local move. |
| D3 | Default access for a newly added shared folder | Read-only; read-write is a deliberate tick. | |
| D4 | Sharing a sensitive folder | jterm's config folder and `~/.ssh` refused, and hidden inside any other share; the home folder and drive or system roots allowed after a confirmation. | The vault and SSH keys are the two things a share must never expose; hiding them inside shares makes the home-folder case safe. |
| D5 | Mark files the server writes as downloaded | Yes: mark of the web on Windows, quarantine on macOS. | The OS warns before running them. Office then opens such files in Protected View. |
| D6 | Shared folders in a session export | Not exported. | A path is specific to one machine, and an import must never share a folder unasked. |
| D7 | Drive implementation | jterm's own: the helper forwards, Java does all file access. | FreeRDP's drive device fails P1 (links escape), P4 and P6. |
| D8 | Order of the phases | Drive redirection, then local-to-remote file copy, then remote-to-local. | Drives alone cover moving files both ways. |
