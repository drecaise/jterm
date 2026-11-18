# RDP printer and smart-card redirection

**Status:** designed, 2026-11-17. Decisions D1–D10 at the end answered by the user the same day
(all as proposed, except D3, which gained an opt-in "print without asking" per session).
Required for the release candidate, on which the Windows full-screen test also runs.
**Printing built 2026-11-17** (phase 2, protocol v13), see [Printing as built](#printing-as-built).
**Smart cards built 2026-11-17** (phase 3), see [Smart cards as built](#smart-cards-as-built),
after a spike with a software PIV card on Linux ([the smart-card spike](#smart-card-spike-software-piv-card-2026-11-17));
a real card before the RC.
**Builds on:** `2026-11-07-rdp-session-design.md` (the helper and its protocol),
`2026-11-09-rdp-drives-and-file-copy-design.md` (the forwarding drive device, P1–P6),
`2026-11-11-rdp-helper-sandbox-design.md` (nothing here may loosen it),
`2026-11-11-rdp-audio-design.md` (the consent pattern for a local device: A1–A3).

## Summary

- **Printing:** the session gets **one** printer, "jterm". What the remote desktop prints to it
  arrives in jterm as a PDF and waits in the pane's bar ("Print job: 3 pages"). Nothing is printed
  or saved until the user picks **Print…** (the local print dialog, with a preview), **Save as
  PDF…** or **Discard**. A session can opt in to printing on one named local printer without
  asking, within a page cap. Per session, off by default.
- **Smart cards:** the remote desktop can use the local smart-card readers the user picked for the
  session, after a prompt once per connection. Per session, off by default, no readers picked.

The rule from the drives, the clipboard and sound applies again: **the helper never touches a
local device.** It forwards the two device types' requests over the pipe. jterm parses them in
Java and does all the printing and PC/SC calls. FreeRDP's own `printer` and `smartcard` channels
are not used, and the add-in provider keeps refusing them.

## Why not FreeRDP's printer and smart-card channels

Each would be one setting. Rejected, from FreeRDP 3.33's source (`/home/mark/frdp/src`):

- **The printer channel writes server-supplied data to disk.** The server's printer cache events
  (`RDPDR_ADD_PRINTER_EVENT`, `UPDATE_`, `DELETE_`, `RENAME_`) go to `printer_save_to_config` /
  `printer_move_config` / `printer_remove_config` in `printer_main.c`. They create, rename and
  delete files under FreeRDP's config folder, with names built from strings the server sent. Under
  the Linux sandbox that fails (`RLIMIT_FSIZE` 0, Landlock); without the sandbox it is a file
  write the server controls.
- **The printer channel hands the job to the local print system unread.** The CUPS backend
  announces the driver `"MS Publisher Imagesetter"` (`printer_cups.c`), so the server sends
  **PostScript**, and CUPS passes it to Ghostscript. PostScript is a programming language, and
  Ghostscript has had remote-code-execution bugs in 2023 and 2024 (CVE-2023-36664,
  CVE-2024-29510). On Windows the job goes to `WritePrinter` raw. Either way a hostile document
  reaches a native interpreter with nobody having seen it.
- **Mirrors every local printer.** That shows the server every printer's name and model, and lets
  it print silently on any of them (paper, toner, a 500-page job).
- **The smart-card channel puts 290 KB of NDR parsing in C** (`smartcard_pack.c`,
  `smartcard_call.c`, `smartcard_operations.c` in `libfreerdp/utils`) inside the process exposed to
  the server, and needs the PC/SC daemon's Unix socket. The sandbox refuses every Unix socket on
  Linux (seccomp) and every non-system file on macOS. Opening one for `pcscd` would also let a
  compromised helper reach the card directly, past any consent jterm asks for.
- **The smart-card channel exposes every reader** (contactless ones too: a bank card lying on an
  NFC reader is a card), and passes on calls that change the local PC/SC configuration
  (`SCardIntroduceReader`, `SCardForgetReader`, `SCardSetAttrib`). Its `SCardWriteCache` lands in
  the client's real card cache: on Windows that is the machine-wide cache shared with local
  programs.

## Threat model

**The server is untrusted**, or impersonated after a certificate was accepted by mistake. It
controls every print job's bytes, sizes and timing, and every smart-card call's arguments and
order. **The helper is untrusted by jterm** (sandbox design).

**What is protected:** the local printers (paper, toner, what they print); the local file system;
jterm's JVM (memory, threads, the unlocked vault); the local smart cards and readers the user did
not pick; the picked card outside the connections the user allowed; the card's PIN retry counter;
the local PC/SC configuration.

**Not protected, by the nature of the feature:** once the user allows a card for a connection,
the server can use it as far as the card allows: read its certificates, sign, decrypt, log on.
**A PIN typed in the remote session passes through the server**, so a hostile server learns it.
A reader with a PIN pad avoids that, and the PIN-pad commands are let through (below). The dialog
and the user manual say this plainly. Not in scope: local processes, as before.

**Properties the design must keep:**

| | Property |
|---|---|
| R1 | Nothing is printed, saved or opened without the user acting on that job, unless the session opted in to printing without asking: then only on the one printer named there, within its caps, and every job printed shows in the bar. |
| R2 | A job document never reaches a native interpreter as the server sent it. Locally it is parsed only in Java (PDFBox). What the OS print system gets is what Java draws. |
| R3 | The server learns nothing about the local printers: there is one printer, named by jterm. |
| R4 | A server can't flood the user: jobs wait in the pane's bar and never open a dialog by themselves. Pending jobs and their size are capped. |
| R5 | The helper writes nothing and stores nothing for a printer: no cache data, no config files. |
| S1 | The server sees only the readers the user picked for the session. Any other reader name is unknown to it, even when the server guesses the name. |
| S2 | No card is connected before the user allows it for this connection (one prompt, "Don't allow" focused). The user can withdraw it at any time, and the bar shows when a card is in use. |
| S3 | The server can't change the local PC/SC setup: no introducing or forgetting readers or groups, no `SCardSetAttrib`, no direct (cardless) reader access, and only the PIN-pad control codes. |
| S4 | Nothing a server sends for the smart-card channel is stored outside the connection's memory. |
| S5 | A server can't block a card by wrong PINs unnoticed: after a failed PIN with one try left, the next attempt needs the user's consent. |
| S6 | No new parsing of server bytes in C: the helper forwards. PC/SC runs in the JVM, so the helper sandbox gains no socket and no file. |
| S7 | Everything the server can make jterm hold is capped (contexts, card handles, pending calls, buffer sizes, cache), and a disconnect releases all of it, resetting the card. |

## Architecture

```
                     helper (C, FreeRDP)                          jterm (Java)
server ─ rdpdr ─▶ rdpdr core ─┬▶ jterm printer device ══ DEVICE_IRP ══▶ RdpPrintService ─▶ job files
                              │  (forwards CREATE/WRITE/CLOSE)            ↓ (user: Print…)
                              │                                    PDFBox ─▶ java.awt.print ─▶ OS
                              └▶ jterm smart-card device ═ DEVICE_IRP ═▶ ScardService
                                 (forwards DEVICE_CONTROL)          NDR, policy, consent ─▶ PC/SC
```

### Protocol v13

`protocol.h` and `RdpProtocol` change together.

- **CONNECT** gains, after the audio part: `u8 printerFlags` (bit 0: enabled, bit 1: set as the
  session's default printer), `str printerName`, `str printerDriver`, and `u8 smartcardEnabled`.
  The driver name comes from jterm (spike, below), never from the user.
- **`DRIVE_IRP` / `DRIVE_IRP_DONE` become device-neutral.** They keep their ids and layout. The
  printer gets device id `0xFFFF0001` and the smart card `0xFFFF0002`, outside the share-id range,
  and `RdpConnection` routes by id. The helper code already forwards an IRP's bytes unparsed, so
  the new devices reuse the same pending table, cap (256) and completion lock.

### Helper: two forwarding devices

`j_addin_provider` returns jterm's entry for `printer` (when CONNECT enabled it) and for
`smartcard` (when CONNECT enabled it). Serial, parallel and every other device service still get
nothing.

- **Printer** (`RDPDR_DTYP_PRINT`): announced with jterm's name and driver, the default-printer flag
  if asked, `CachedFieldsLen` 0, and the code page 0. `IRP_MJ_CREATE`, `WRITE`, `CLOSE` and
  `DEVICE_CONTROL` are forwarded. Anything else is answered `STATUS_NOT_SUPPORTED` in the helper.
  Custom-component messages (`PAKID_PRN_CACHE_DATA`, `PAKID_PRN_USING_XPS`) are ignored and
  logged once. No file, no thread of its own.
- **Smart card** (`RDPDR_DTYP_SMARTCARD`, name `SCARD`): `IRP_MJ_DEVICE_CONTROL` is forwarded with
  its output length, IOCTL code and input buffer. Anything else is answered
  `STATUS_NOT_SUPPORTED`. The helper doesn't look inside: it doesn't even know the IOCTL names.
- Nothing is opened, loaded or connected, so the lockdown point, the Landlock rules, seccomp, W1/W2
  and the Seatbelt profile stay exactly as they are. The self-test gains nothing new to refuse.

### Java: printing (`terminal.rdp.print`, Swing-free except the dialog)

**A job** is `CREATE` → `WRITE`s → `CLOSE` on the printer device. `RdpPrintService`:

- On `CREATE`, opens a job file in a private folder (`<config>/rdp-print-jobs/`, created owner-only:
  0700, files 0600, a Windows ACL for the user only). One job at a time per connection. A second
  `CREATE` before `CLOSE` fails with `STATUS_DEVICE_BUSY`; Windows' spooler waits and retries.
- `WRITE` appends in arrival order. The server's offset field is **not** a position: the spooler
  sent offset 0 for every write of a job (spike), and FreeRDP's printer ignores it too. Caps: 256 MiB per job, 8 pending jobs, 1 GiB in total, and above
  the drive design's free-space floor (1 GiB or 5 %). Over a cap the write fails
  (`STATUS_DISK_FULL`) and the job is dropped with a note in the bar. The job is never truncated.
- On `CLOSE`, the file must start with `%PDF-`, or it is deleted and counted ("the remote desktop
  sent a print job in a format jterm doesn't accept"). Then PDFBox opens it once to count the pages
  and render page 1 as a thumbnail, both with caps (below). The job appears in the bar.
- Files are deleted when the job is printed, saved or discarded, when the pane closes, and at
  startup (whatever a crash left behind).

**The bar:** "Print job: 3 pages" (or "3 print jobs") beside "Save files…", in the pane's bar and
the full-screen bar. Clicking opens the job dialog through `dialogParent()`, so it leaves full
screen first. The dialog shows the thumbnail, the page count and the size, with **Print…**,
**Save as PDF…** and **Discard**. With several jobs, a list.

- **Print…** opens the OS print dialog (`PrinterJob.printDialog()`: printer, copies, pages) and
  prints PDFBox's `PDFPageable`, each page shrunk to fit the chosen paper and centred (jobs arrive
  as US Letter by default, see the spike). Java draws the pages. What the printer driver or CUPS gets is
  Java's own output (GDI on Windows, PostScript **generated by Java** on Linux and macOS), never
  the server's document (R2).
- **Save as PDF…** copies the file to a chosen place and marks it as downloaded (`OriginMark`:
  mark of the web on Windows, quarantine on macOS), like a remote file saved with "Save files…".
- **Discard** deletes it.

**Print without asking** (opt-in per session, D3). The session names one local printer (picked
from the print services at edit time, stored by name). A job that passed the checks above then
prints there at once, through the same PDFBox path with the printer's defaults, and the bar shows
"Printed: 3 pages on *printer*" for a while. Caps: 50 pages per job and 10 jobs per 10 minutes per
connection. A job over a cap, a job PDFBox couldn't preview, and every job while the named printer
is missing wait in the bar as usual, with the reason. The server still can't pick the printer,
learn its name or print without limit.

**PDFBox is hostile-input parsing in the JVM,** memory-safe but able to burn CPU and memory. It
has had denial-of-service bugs (infinite loops, out-of-memory), none of them code execution. So:

- `MemoryUsageSetting.setupTempFileOnly()` with its temp folder inside the private job folder, so
  a large document doesn't sit on the heap.
- Opening, counting and the thumbnail run on one `rdp-print` thread, with a 20 s limit. Past it
  the job is shown as "can't be previewed" and can still be saved, not printed.
- At most 2 000 pages, and the thumbnail at 96 dpi. XMP metadata is never parsed.
- Printing renders page by page on the print thread. Cancel in the OS dialog stops it.

### Java: smart cards (`terminal.rdp.smartcard`, Swing-free)

| Class | Role |
|---|---|
| `ScardNdr` | Decoder and encoder for the MS-RDPESC call structures (MS-RPCE type serialisation v1). Every length is checked against the buffer before use; strings are capped at 1 024 characters; reader-state arrays at 11 entries; transmit buffers at 66 560 bytes. A malformed call is answered `SCARD_E_INVALID_PARAMETER`, never thrown to the reader thread. Pure, unit-tested with captured calls. |
| `ScardService` | One per connection: dispatches each IOCTL, owns the virtual handles and the call threads. |
| `ScardPolicy` | Pure: reader filter, consent state, the PIN guard, the control-code and attribute allow-lists. Unit-tested. |
| `Pcsc` | Interface over the eighteen PC/SC functions used. `JnaPcsc` implements it per OS, a fake in the tests. |

**Calls supported** (what Windows' logon, the smart-card service and the base CSP use):
establish, release and validate a context; list reader groups (answers the default group only);
list readers; get status change; cancel; connect; reconnect; disconnect; begin and end a
transaction; state; status; transmit; control (filtered); get attribute (filtered); read and write
the cache (in memory); the access-started event; the transmit count; locate cards by ATR; the
device type (USB, as FreeRDP answers) and the reader icon (none). The spike's capture of a real
session is in
[the smart-card spike](#smart-card-spike-software-piv-card-2026-11-17).
**Refused** with `SCARD_E_UNSUPPORTED_FEATURE`: introducing or forgetting a reader or group, adding
or removing a reader from a group, set attribute, and anything unknown (S3).

**Handles are jterm's, never the system's.** The server gets small random numbers that map to the
real `SCARDCONTEXT` / `SCARDHANDLE` inside one connection. A guessed or stale value is
`SCARD_E_INVALID_HANDLE`. Caps: 32 contexts and 8 card handles per connection (Windows had 7
contexts open at once in the spike).

**Readers (S1).** The session lists the readers the user picked, by name. pcsclite appends an
index (`… 00 00`), which is ignored in the comparison, so a picked reader stays picked after a
replug. Listing returns only picked readers that are present. A status-change request is
filtered the same way. The server may still watch the PnP pseudo-reader (`\\?PnP?\Notification`),
which tells it only that the reader count changed, and only for picked readers. Connecting to
any other name is `SCARD_E_UNKNOWN_READER`, exactly what a missing reader answers.

**Consent (S2).** Listing readers and waiting for a card insertion need no consent: Windows does
both all the time, starting at logon. **The first connect to a card in a connection** asks:
"The remote desktop *name* wants to use your smart card in *reader*. It can then sign, decrypt and
log on with it, and a PIN you type in the remote desktop is visible to that computer." Buttons:
Allow for this connection / Don't allow, with "Don't allow" focused. The prompt goes through
`dialogParent()`. While the question is open the connect waits (Windows' logon waits too).
"Don't allow" answers `SCARD_E_NO_SMARTCARD` for the rest of the connection: the server can't ask
again (as A2). The **card button** in the bar ("Smart card", red while a card handle is open) has
Allow / Block for this connection. Blocking disconnects every card handle with
`SCARD_RESET_CARD`, which drops the card's verified-PIN state.

**Share modes.** `SCARD_SHARE_DIRECT` (talking to the reader with no card, the way firmware tools
do) is refused (S3). `SCARD_SHARE_EXCLUSIVE` is downgraded to `SHARED` (D8): an exclusive open
would lock the card out of local programs, such as an SSH agent using the same card, for as long
as the server likes. Transactions are kept (the base CSP needs them) and released at disconnect.

**Control codes (S3).** Allowed: `CM_IOCTL_GET_FEATURE_REQUEST`, and the codes that request
returned for the PIN-pad features (verify PIN direct, modify PIN direct, PIN properties, TLV
properties). Everything else is refused. The server sends Windows' codes
(`SCARD_CTL_CODE(n)` = `0x00310000 | n << 2`), and pcsclite uses `0x42000000 + n`. So on Linux
and macOS jterm translates them, including the codes inside the feature request's answer.

**Attributes.** Allowed: vendor name, reader type and version, channel id, ATR, current protocol,
card presence and interface status, device friendly and system names. The reader's serial number
is left out (it identifies the user's hardware across servers).

**Cache (S4).** Read and write cache calls are kept in a map in memory per connection (64 entries,
64 KiB each), keyed as the server keys them. Never on disk, never in the system's cache.

**PIN guard (S5).** jterm reads the ISO 7816 status word of every response to a VERIFY (`INS 20`),
CHANGE REFERENCE DATA (`24`) or RESET RETRY COUNTER (`2C`) command. After a `63 C1` (one try left)
the next such command on that card waits for a question: "The remote desktop entered a wrong PIN.
One more wrong attempt will block your card. Let it try again?" A `69 83` (blocked) is shown in
the bar. This is best effort: a card using secure messaging hides it, and the user manual says
so.

**Threads.** A status-change call blocks, possibly for ever. `ScardService` gives each context a
thread and runs calls on that context in order. `SCardCancel` and the access-started event bypass
the queue. At disconnect jterm cancels every context, waits briefly, disconnects card handles
(reset) and releases the contexts. The drive service's per-file dispatcher is not reused: every
smart-card IRP carries the same file id.

**PC/SC per OS** (`JnaPcsc`, JNA, which jterm already ships at run time through pty4j and
java-keyring; it moves to compile scope):

| OS | Library | Notes |
|---|---|---|
| Windows | `%SystemRoot%\System32\winscard.dll`, by full path | `DWORD`/`LONG` are 32-bit. |
| Linux | `libpcsclite.so.1`, by absolute path from the system library folders, root-owned and not writable by others (the `libfaad` rule) | `DWORD`/`LONG` are `unsigned long`/`long`, so 64-bit on x86-64. Needs `pcscd` (socket-activated on the RPM and DEB distributions). |
| macOS | `/System/Library/Frameworks/PCSC.framework/PCSC` | 32-bit `DWORD`/`LONG` despite the 64-bit OS, and a few error codes differ. |

The type sizes are the classic PC/SC porting bug. `JnaPcsc` has one mapping per OS and a test
that checks the structure sizes against each platform's headers.

**Not in this design: signing in to NLA with the card.** NLA with a smart card needs Kerberos
PKINIT on the client. WinPR is built without Kerberos, and W2 removes Windows' own SSPI. A user
whose account requires a card can turn on "Allow TLS without NLA" and sign in at the remote logon
screen with the redirected card, or use the Kerberos switch on Windows. Both are explained in the
user manual (D9).

## UI

- **Session dialog, "Local resources" tab**, beside the drives and sound:
  - *Printer:* "Print from the remote desktop through jterm" (off), and under it "Make it the
    default printer there" (off) and "Print without asking on:" with a printer list (off; the
    caps are named beside it). Hint: "Print jobs wait in the session's bar until you print or
    save them."
  - *Smart cards:* "Use smart cards from this computer" (off), and a list of the readers present
    now with a tick each, plus a field to keep a reader that isn't plugged in. Hint: "jterm asks
    before the remote computer can use a card, once per connection. A PIN you type in the remote
    desktop is visible to that computer."
- **Pane bar and full-screen bar:** the print-job button, the smart-card button.
- **"Show performance"** gains nothing. The helper's log names devices announced.

## Persistence

`RdpSessionConfig` gains `printToJterm`, `printerIsDefault`, `autoPrintPrinter` (a local printer
name, blank = ask), `useSmartCards` and `smartCardReaders` (a list of names). `copyWithNewId` copies them. The class ignores unknown properties, so there is
no schema bump. **`useSmartCards` and `smartCardReaders` are left out of exports and ignored on
import** (the mix-in that drops `driveShares` and `useMicrophone`): a reader name is
machine-specific, and an import must not enable a local device. The two printer switches name
nothing local and are exported; `autoPrintPrinter` names a local printer, so it is left out too.

## Packaging

| Target | Change |
|---|---|
| `pom.xml` | Apache PDFBox 3.x (`pdfbox`, its `fontbox`, `commons-logging` → routed to SLF4J), JNA at compile scope (its version pinned to what pty4j brings). The `Security` workflow's dependency check covers them. |
| Flatpak | `--socket=pcsc` (the `pcscd` socket) and `--socket=cups` (Java's printing talks to CUPS). Both are visible on Flathub's permission list. |
| RPM, DEB | Java's printing needs `libcups.so.2`. Check that the built packages require it, as was done for `libasound`, and add `pcsc-lite-libs` / `libpcsclite1` as a weak dependency (Recommends), since smart cards are optional. |
| MSI, DMG, snap | Nothing. |
| Licenses | PDFBox and fontbox (Apache-2.0, with their NOTICE) in `THIRD-PARTY-LICENSES.md` and `third-party-licenses.txt`. |
| `smoke-test.py` | A v13 CONNECT with both devices on (closed port). |

## Spike results, Windows 11 (2026-11-17)

A spike build (not committed) announced four printers, one per candidate driver, forwarded their
IRPs like a share's, and kept each job. The Win11 VM connected to itself (`127.0.0.2`, NLA as
`claude`), and a task in that session printed a line of text to each printer with `Out-Printer`.

| Printer's driver | Created on the server | What jterm received |
|---|---|---|
| `Microsoft Print To PDF` | yes | **a PDF** (`%PDF-1.7`, Producer "Microsoft: Print To PDF", one Letter page, font embedded as a CID TrueType subset, the text extracts intact), 45 KB in one write |
| `Microsoft PWG Raster Class Driver` | yes | PWG raster (`RaS2`), 2550×3300 at 300 dpi, 24-bit sRGB, 22 KB in one write |
| `Microsoft XPS Document Writer v4` | **no** (the printer never appeared) | nothing |
| `MS Publisher Imagesetter` (FreeRDP's) | yes | PostScript from `PScript5.dll`, 31 KB in two writes |

Also seen: each job is one CREATE, its WRITEs (at most 28 KiB each here), then CLOSE. The CREATE
carries no path. No DEVICE_CONTROL came. **Every write's offset was 0**, so offsets can't be trusted
(the design now appends in order). The server sent printer cache data three times
(`PAKID_PRN_CACHE_DATA`, the messages FreeRDP turns into files): the spike ignored it and nothing
broke. Redirected printers show as "jterm PDF (redirected 2)" on port `TS00n`.

**The user's run, same day** (the work laptop's jterm with the spike build, to the Win11 VM, Notepad
printing a script to each printer): the same picture. PDF 49 KB (title "cleanup.ps1 - Notepad",
font embedded, the text extracts with its layout), PostScript 70 KB, PWG raster 167 KB (one page,
2550×3300 at 300 dpi, decoded to its last byte). Writes again all at offset 0, and one was
**103 KiB**, so the design can't assume small writes. Nothing showed in jterm, as expected: the
spike has no interface.

**Paper size:** every job came out **US Letter**, the redirected printer's default, while the
user's printers are A4. Two consequences for the build: Print… scales each page to the chosen
paper (shrink to fit, centred), and the user manual says to pick the paper size in the remote
program's print dialog. Announcing A4 as the printer's default would need the printer's cached
configuration (`CachedFields`, a DEVMODE the server stores), the very data the design ignores;
left as a possible later step.

So D2 holds on Windows 11: PDF, with PWG raster as a working fallback. Still to check, on the work
laptop with real programs (Edge, Word, a multi-page document; the user's spike run) and on a Windows
Server (whether the PDF driver exists there and what Easy Print's default policy does):

## Printing as built

Built 2026-11-17 on `feature/rdp-session`, as designed above, with these decisions made while
building:

- **One driver, no fallback yet.** The helper announces `Microsoft Print To PDF` only. A server
  without that driver silently creates no printer (the XPS v4 case in the spike) and the client is
  not told, so the PWG raster fallback of D2 waits until such a server is actually met (a Windows
  Server check belongs to the RC). The troubleshooting page names the cause.
- **Waiting jobs belong to the pane** and survive a reconnect; the job being received belongs to
  the connection attempt and is dropped with it. Closing the pane deletes every waiting job.
- **The job folder is per process** (`<local data>/rdp-print-jobs/<pid>/`), in the non-roaming
  local data folder (a new `AppPaths.localDataDir()`), so a second running jterm's jobs are never
  swept by this one. PDFBox's font cache goes to the same local data folder.
- **The fat jar now keeps every dependency's `META-INF/LICENSE` and `NOTICE`**, concatenated: by
  default the shade step kept only the last one, which would have dropped PDFBox's notice and its
  fonts' licence texts (and already dropped others').
- **Auto-print caps count per connection attempt**; a refused job (over a cap, unreadable, printer
  missing) waits in the bar with the reason, shown in the print button for a while.

**Verified:** unit tests for the service (lifecycle, offsets, busy, caps, free-space floor, hostile
lengths and handles, clean-up), the folder sweep, the auto-print limiter, PDFBox on good, truncated,
random and oversized documents, the v13 CONNECT, the settings and the export rule; PDFBox on both
real Windows PDFs from the spike (one page, thumbnail drawn). **End to end without the GUI**: the
Linux helper (v13, sandbox full) driven by jterm's `RdpConnection` and `RdpPrintService` against the
Win11 VM: the printer appeared as "jterm (redirected 2)", the server's cache data was dropped, a
45 KB PDF arrived and was queued and previewed. The Windows helper builds with MSVC (VM). The smoke
test sends a v13 CONNECT with the printer on; a printer part the helper refuses fails CONNECT.

**GUI test on Linux (2026-11-17, jar + Linux helper, Win11 VM, CUPS with two Epsons):** the
session dialog's three settings (the two dependent ones greyed out until printing is on), a Notepad
job showing in the bar, the dialog's thumbnail, Print… to a real printer (shrunk to A4, centred),
Save as PDF, Discard, two waiting jobs, the full-screen bar's button leaving full screen before the
dialog, auto-print to a named printer with its note, and a job surviving a disconnect until the pane
closed. The job folder held only `scratch/`, owner-only; nothing was written to the home folder.
Windows names the printer "jterm (redirected N)", N being the server's session id: Windows adds
that suffix itself, the client has no say in it.

**Flatpak (2026-11-18, the user, local Flatpak build, Win11 VM):** a Notepad job printed to "jterm"
showed in the bar, and Print… and Save both worked: the Flatpak's CUPS access holds.

**macOS (2026-11-18, the user, x64 DMG of 9f84e2a, Intel VM on macOS 26.7, Win11 VM):** a Notepad job
showed in the bar and Save worked; Print… opened the macOS dialog and printed on the user's network
printer, which macOS found by itself. Helper sandbox full (Seatbelt).

**Windows client and Windows Server 2025 (2026-11-18, the user, MSI of 9f84e2a on the Win11 VM,
Server 2025 evaluation VM signed in as Administrator):** the server created "jterm (redirected 1)"
with the in-box `Microsoft Print To PDF` driver, as Windows 11 does; a Notepad job showed in the bar,
Print… and Save both worked. A redirected printer is listed only inside its own session: `Get-Printer`
over SSH didn't show it, run in the RDP session it did.

**Not verified yet:** Windows Server 2016, 2019 and 2022; the RPM/DEB `libcups`
dependency (next Release run).

## Smart-card spike, software PIV card (2026-11-17)

**Setup, Linux, no root.** A software PIV card: PivApplet 0.9.0 in jCardSim 2.2.2, plugged into
vsmartcard 0.11's `vpcd` (patched to listen on loopback only; stock it listens on every
interface), under a private `pcscd` started in `bwrap` with its own folder over `/run/pcscd`;
clients reach it through `PCSCLITE_CSOCK_NAME`. RSA 2048 key and a self-signed certificate with
the Smart Card Logon EKU in slot 9A, PIN 123456. jCardSim 2.2.2 needed four applet patches (no EC
keys, so no attestation and RSA only; the RSA private operation done by CRT because its cipher and
key getters misbehave). The whole kit lives outside the repo (`~/scard-spike`, README there);
checked locally with `javax.smartcardio`: select, certificate, PIN, a signature that verifies.

**Capture.** A spike build of the helper (git stash "smart-card spike 2026-11-17 (not for
commit)") let FreeRDP's own smart-card device through the add-in provider and hex-dumped every IRP
in and every completion out. It skipped the lockdown: the seccomp filter refuses the `pcscd` Unix
socket, which the design avoids by doing PC/SC in Java. jterm's `RdpConnection` connected to the
Win11 VM as `claude`; `certutil -scinfo -silent -pin 123456` ran inside that RDP session as a
scheduled task with `/IT`.

**Result.** Windows found the card in the redirected reader as "Identity Device (NIST SP 800-73
[PIV])" with its in-box minidriver (Microsoft Base Smart Card Crypto Provider), read the
certificate, and reported "Private key verifies": the PIN and a private-key operation crossed the
channel and worked. (The chain is untrusted: self-signed, as expected.) 718 IRPs in about a minute,
all `IRP_MJ_DEVICE_CONTROL`, all the Unicode (`W`) forms:

| Call | Count | Notes |
|---|---|---|
| GetStatusChangeW | 199 | |
| ReadCacheW | 178 | 102 answered "not found" (`0x80100070`), 76 hits |
| Transmit | 90 | largest IRP 320 bytes in, 180 out: short APDUs, chaining and GET RESPONSE |
| WriteCacheW | 39 | about nine distinct items per card connect, at most ~1 KB each |
| StatusW | 35 | |
| EstablishContext / ReleaseContext | 27 / 24 | **up to 7 contexts open at once** |
| BeginTransaction / EndTransaction | 26 / 26 | |
| ConnectW / Disconnect | 21 / 21 | one card handle at a time |
| ListReadersW | 18 | |
| Cancel, IsValidContext | 4, 4 | |
| AccessStartedEvent | 3 | |
| GetDeviceTypeId | 3 | first counted as GetReaderIcon: the IOCTL table used then was off by one (see as built) |

Not seen in this run: Control, GetAttrib, State, Reconnect, LocateCardsByATR, the transmit count,
reader groups. They stay in the list (Windows' logon and other minidrivers use them), but have no
captured fixture yet.

**What it changes in the design:**
- **Context cap: 32, not 8.** Seven were open at once in one `certutil` run beside the smart-card
  service's own watcher; 8 would have been one away from refusing Windows.
- **The cache is real traffic**, so the in-memory map stays (S4); 64 entries of 64 KiB is ample.
  Without it every miss means more Transmits on each connect, not an error.
- **`GetDeviceTypeId`** joins the supported list, answered "USB" as FreeRDP does (its reply is in
  the capture); GetReaderIcon, never seen, answers unsupported as WinPR does.
- **The PIN crosses as a plain APDU** (`00 20 00 80 08` + PIN padded with `FF`) inside the channel.
  That is what makes the S5 guard possible, and it means **jterm never logs APDU contents or IRP
  bytes**, at any log level; test fixtures are made with test PINs only.
- `ScardNdr`'s fixtures come from `run1.log` (the W calls above, with their real NDR).

**Not covered yet:** Windows' logon screen with a card (needs a domain with a certificate
authority; the domain VM is a work VM), removing and inserting the card during a session, the
wrong-PIN and blocked-PIN path, and the Windows and macOS clients' PC/SC. The first three can be
done with the software card; the real card before the RC covers the clients.

## Smart cards as built

**Pieces** (`terminal.rdp.smartcard`, Swing-free unless named): `ScardNdr` (decode and encode),
`NdrReader`/`NdrWriter`, `ScardService`, `ScardPolicy`, `Pcsc` and `JnaPcsc`, `SmartCards` (the
process's PC/SC and the reader list); in `ui.rdp`, the pane's consent and PIN questions, the **Smart
card** button (pane and full-screen bar) and `SmartCardReadersEditor` in the session dialog. The
helper's device is ~60 lines that forward. Protocol v13 kept its number: `smartcardFlags` was
already in CONNECT, reserved.

**Differences from the design above, all found by measuring:**
- **Card state.** pcsc-lite reports a connected card as NEGOTIABLE (flags `0x34`) and never SPECIFIC.
  Passed through, Windows' minidriver disconnected after every Status and certutil's private-key
  test failed ("Could not verify certificate public key against private key") while the reader,
  card and certificate all showed. WinPR maps NEGOTIABLE to SPECIFIC; so does `JnaPcsc` now.
- **IOCTL codes.** They are `0x00090000 | n << 2` for n = 5..66; the first table had the last three
  wrong (ReleaseStartedEvent `0xE4`, GetReaderIcon `0x104`, GetDeviceTypeId `0x108`), and the A/W
  forms don't differ by one bit (ConnectA `0xAC`, ConnectW `0xB0`). Caught by the byte-exact tests.
- **Reply padding.** Every reply except the started-event ones is padded to a multiple of 8, the
  padding counted in OutputBufferLength and ObjectBufferLength (MS-RPCE 2.2.6.3, FreeRDP).
- **Queues.** One per context was not enough: FreeRDP answers Cancel, IsValidContext and
  ReleaseContext at once, and runs BeginTransaction, Connect and Reconnect on a second queue, since
  they may wait for another handle's transaction. Both copied. A ListReaders queued behind a
  status change that waits for ever still waits (FreeRDP does the same; seen only at disconnect).
- **macOS and the PnP reader.** macOS' PC/SC crashes on `\\?PnP?\Notification` (WinPR's note), so
  there jterm never passes it and looks at the reader list once a second instead.
- **pcsc-lite's first call.** Two EstablishContexts at once failed one with
  `SCARD_F_INTERNAL_ERROR`; `JnaPcsc.establish` is serialised.
- **The cache** is per connection (WinPR's is per context): 71 hits against FreeRDP's 76 with far
  fewer calls overall.

**Verified:** 44 tests (`ScardNdrTest` against the captured calls and FreeRDP's replies, byte for
byte, plus truncated and garbled input; `ScardServiceTest` with a fake PC/SC: handles, reader filter,
consent once, denial, exclusive to shared, direct refused, PIN guard, control and attribute
allow-lists, cache and freshness, the PnP count of visible readers, polling without PnP, cancel,
release during a wait, a blocked BeginTransaction, caps, block, close; `ScardPolicyTest`;
`JnaPcscTest` for the layouts and translations). `JnaPcsc` against the software card through a real
pcscd: list, status change, PnP, connect, status, transaction, transmit with chaining, attribute,
feature request, cancel. **Live, headless** (harness: jterm's `RdpConnection` and `ScardService` with
a consent that allows, the real helper, sandbox **full**, Win11 VM as `claude`): certutil found the
card as a PIV device through the in-box minidriver, read the certificate and reported "Private key
verifies" in both the CSP and KSP sections; consent asked once; 28 contexts, 25 transactions begun
and ended, no call failing except the cancelled waits at disconnect.

**GUI test on Linux (2026-11-17, the user, jar + Linux helper, Win11 VM, software card):** the
reader list in the session dialog, the consent question (once, at the first card use: certutil,
not the password sign-in, which never touches a card), "Private key verifies", block and allow from
the Smart card button, the button in full screen (the dialog left full screen first), and the PIN
guard: a second wrong PIN with one try left was asked about. certutil asks for the PIN twice, once
per provider section (CSP, then KSP): Windows' behaviour, not a retry.

**Flatpak (2026-11-18, the user, Win11 VM signed in as a second account, software card behind a
private pcscd handed in through `PCSCLITE_CSOCK_NAME`):** the bundled pcsc-lite 2.5.3 client reached
the 2.2.3 pcscd, sandbox level full. The consent question came at sign-in this time (Windows' sign-in
screen enumerates the readers), still once per connection; blocking from the Smart card button
worked; certutil asked for the PIN twice as before. The first certutil run in the new Windows profile
reported "No AT_KEYEXCHANGE cert retrieved" in the CSP section while the KSP section verified; the
second run, same connection, verified in both, so it was Windows' first use in that profile. Both runs
fail certutil's "AES256+RSAES_OAEP" test with `CRYPT_E_NO_DECRYPT_CERT`: it looks the certificate up
in the user's store, where nothing propagated it, not on the card.

**macOS (2026-11-18, same DMG and VMs):** PCSC.framework works. The software card reached the Mac
through vsmartcard's `ifd-vpcd` (built there, loopback only, ad-hoc signed, installed in
`/usr/local/libexec/SmartCardServices/drivers/`; since macOS 10.10 a reader driver is started only
by a matching USB device, so its `Info.plist` names the VM's QEMU keyboard, 0627:0001, and the VM was
rebooted), the card bridge connecting over an SSH tunnel. certutil reported "Private key verifies" in
both sections; consent once; blocking from the Smart card button worked. **Removing and re-inserting
the card within one connection** (the bridge stopped and started; on macOS jterm polls the reader
list): Windows reported the reader `SCARD_STATE_EMPTY`, then present again, and certutil verified
again once the card was allowed. The card was still **blocked** from the earlier Block test during the
first run after the insertion: the reader reported the card present with its ATR while every connect
was refused, so Windows could not identify it as PIV and certutil failed with
`SCardGetCardTypeProviderName: ERROR_FILE_NOT_FOUND`. That is what Block does (status stays truthful,
the card can't be opened); the user then chose Allow and the next run verified.

**Windows client (2026-11-18, the user, MSI of 9f84e2a on the Win11 VM opened through the Proxmox
console, Windows Server 2025 evaluation VM as the server, signed in as Administrator):** winscard.dll
works live. The card was the Win11 VM's TPM virtual smart card (GIDS, "Identity Device (Microsoft
Generic Profile)"), empty at first ("Keyset does not exist"). Through the session `certreq -new`
generated a 2048-bit key on the card with the Smart Card Key Storage Provider and stored a
self-signed certificate (Smart Card Logon EKU) on it, so writes cross the channel too; certutil then
read the certificate back and matched the key in both sections (it ran no private-key test this
time), and an RSA-SHA256 signature made with the card's key in PowerShell verified against the
certificate. The client is opened through the console on purpose: a Win11 session reached over RDP
sees the RDP client's redirected readers, not the VM's own TPM card.

**Reader removed and re-attached mid-session, Windows client (same setup):** a TPM virtual card can't
leave its reader, so its device was disabled and enabled again on Win11. The server reported
`SCARD_E_NO_READERS_AVAILABLE`, then the reader and card again in the same connection, and a fresh
signature with the card's key verified. This build (9f84e2a) predates 0184b2a, the fix for a reader
vanishing in the middle of a status change, so that path held up without it here.

**Card removed and re-inserted mid-session, Linux client (2026-11-18, headless harness:
`RdpConnection` + `ScardService` from the working tree, the Linux helper, sandbox full, Windows
Server 2025 as a local Remote Desktop user):** with the software card behind the private pcscd,
certutil verified, then reported the reader `SCARD_STATE_EMPTY` after the card bridge stopped, and
three seconds after it started again found the card as PIV and reported "Private key verifies" in
both sections, all in one connection. With the macOS and Windows results this covers removal on
every client. Every non-zero result in the run (cancelled waits, invalid handles) came at the
teardown. **Open question:** in this run and in all four of 2026-11-17's, about 1.2 s after connecting
the server sends a `t=0` GetStatusChange and a ListReaders on a context that already has an infinite
GetStatusChange pending; with one queue per context (as FreeRDP has it) both wait until the
disconnect. Nothing visibly fails (sign-in and certutil work), but which Windows component sends them,
and whether it is left stuck, is not known; answering a `t=0` call outside the queue would be the
change to consider.

**Not verified yet:** a real card
and a PIN-pad reader; logon to a domain with a card.

## Spike before building (decides D2)

On the Windows 11 VM and a Windows Server 2022 evaluation VM:

1. **Which driver names give a PDF.** Announce the printer with `Microsoft Print To PDF` and log
   the first bytes of the job: the hope is that the driver's own output, a PDF, is what reaches the
   redirected port. Also try `Microsoft PWG Raster Class Driver` (a simple raster format) and
   `Microsoft XPS Document Writer v4`. Check that each driver is present on Server 2016, 2019,
   2022 and 2025 without extra features. Check what the server does when "Use Remote Desktop Easy
   Print printer driver first" is on (the default) and jterm doesn't offer Easy Print.
2. **Job shape:** whether a job is one CREATE/WRITE…/CLOSE, write sizes, and whether the spooler
   waits on `STATUS_DEVICE_BUSY`.
3. **Smart cards:** with a YubiKey (PIV) on the laptop: capture the call sequence of the Windows
   logon screen, of `certutil -scinfo` and of a signing in the session, to make `ScardNdr`'s test
   fixtures and confirm the call list above. On Linux, the same through `pcscd`.

If no driver gives a PDF on the servers that matter, the fallback is the PWG raster driver with a
small Java decoder, not PostScript (R2).

## Testing

- **Java, headless:** `RdpPrintService` (job lifecycle, out-of-order writes, every cap, the `%PDF-`
  check, clean-up at close and startup, a 2 000-page and a malformed PDF under the time limit);
  `ScardNdr` (every call from captured fixtures, truncated and oversized inputs, random input);
  `ScardPolicy` (reader filter with index suffixes, consent per connection, block and reset, the
  PIN guard on `63 C1`/`69 83`, control-code translation both ways, attribute allow-list);
  `ScardService` against the fake `Pcsc` (handle mapping, caps, cancel of a blocked status change,
  teardown order); `RdpProtocolTest` (v13); the export mix-in.
- **Helper:** the smoke test; the fake-server harness announcing both devices and completing a
  forwarded IRP.
- **Live, Windows 11 VM and a Windows Server VM:** print from Notepad, Edge and Word; a 300-page
  job; a job while another waits; Save as PDF (mark of the web); Print to a real printer on
  Windows, Linux and macOS; full screen (the bar's button leaves full screen). Smart card: log on
  at the remote logon screen with a YubiKey (TLS without NLA), sign a document in the session, the
  consent prompt, Block mid-session, a wrong PIN until the guard asks, an unpicked reader invisible
  in `certutil -scinfo`, a contactless reader not picked.
- **Sandbox:** the helper's self-test unchanged; `strace` once on Linux with both devices on, to
  confirm no new file or socket.

## Phasing

1. **Spike** (above).
2. **Printing:** protocol v13 (both devices' CONNECT fields at once), the helper's printer device,
   `RdpPrintService`, the bar and dialog, PDFBox, packaging, docs.
3. **Smart cards:** the helper's device, `ScardNdr`, `ScardPolicy`, `ScardService`, `JnaPcsc` per
   OS, consent and the button, packaging, docs.
4. **Release candidate:** the live tests above, plus the Windows full-screen list of the
   full-screen design.

## Decisions (answered 2026-11-17)

| | Question | Decision | Why |
|---|---|---|---|
| D1 | Which printers the server sees | One printer, "jterm", not the local printers. | R3; every job goes through the user anyway, who then picks the real printer in the local dialog. |
| D2 | Job format | PDF from an in-box Windows driver; PWG raster as the fallback; never PostScript. Settled by the spike. | PDF can be saved as it is and rendered in Java; PostScript needs Ghostscript. |
| D3 | When a job prints | Only when the user picks Print… for it, jobs waiting in the bar; **plus**, per session and off by default, printing without asking on one named local printer, capped at 50 pages a job and 10 jobs per 10 minutes. *(User: the opt-in was added to the proposal.)* | R1, R4; trusted servers needn't cost a click per job, and the caps keep a hostile one from emptying the paper tray. |
| D4 | Rendering | PDFBox in the JVM, with the caps above. | Memory-safe, the same on three OSes, and the OS gets Java's output, not the server's document. A separate renderer process isn't possible on the Windows runtime, which jpackage strips of `java.exe`. |
| D5 | Smart-card scope | Only the readers picked per session; none by default. | S1; contactless readers and second cards stay invisible. |
| D6 | Smart-card consent | Prompt at the first card connect, once per connection, "Don't allow" focused; revocable from the bar. | S2, as for the microphone. Listing readers needs none, so the logon screen doesn't prompt before the user picks a card. |
| D7 | PC/SC access | JNA from Java, not `javax.smartcardio`. | `javax.smartcardio` has no multi-reader status change, no attributes, cache or reconnect, and returns exceptions instead of the `SCARD_*` codes Windows expects. |
| D8 | Exclusive access | Downgraded to shared. | Keeps the card usable by local programs. Revisit if a remote program needs exclusive access. |
| D9 | Smart-card NLA sign-in | Not in this design. | Needs Kerberos PKINIT, which the W2 sandbox and WinPR's build exclude. |
| D10 | Export of the settings | Printer switches exported; the auto-print printer and the smart-card settings neither exported nor imported. | Same rule as shared folders and the microphone. |
