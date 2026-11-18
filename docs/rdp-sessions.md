# RDP sessions

jterm can open **Remote Desktop (RDP)** sessions next to your terminals. A remote desktop opens in
a tab like any other session and can also be placed in a split pane. It connects to Windows hosts
and to Linux desktops running an RDP server, such as GNOME Remote Desktop.

## Creating a session

Click **New RDP session** in the sidebar toolbar, or right-click a folder and choose **New RDP
Session…**.

**Basic settings**

- **Host** and **Port** (3389 by default).
- **User**, plus a **Domain** for Windows domain accounts (leave it empty otherwise).
- **Save password**: when on, a password you type is stored encrypted in the
  [credential vault](ssh-auth-and-vault.md). Leave the field blank to keep a password that is
  already saved. Turning the switch off deletes the saved password.

**Display**

- **Fit to pane** (default): the remote desktop is resized to fill the pane, and follows it when you
  resize the window or split the tab.
- **Fixed resolution**: the desktop keeps the width and height you enter and is scaled to fit the
  pane.

**Local resources**

- **Share clipboard** (off by default): text, formatted text and images you copy on the remote desktop are placed on
  your computer's clipboard, **without asking**. Turn it on for computers you trust.
- **Remote can paste mine** (on by default, available once sharing is on): applications on the
  remote desktop may paste what is on your clipboard. Nothing is sent until one of them pastes.
  Turn it off to share one way only, remote to local.
- **Copy files to this computer** (off by default, available once sharing is on): files and
  folders copied on the remote desktop can be saved here with **Save files…**. See
  [Copying files](#copying-files).
- **Remote can paste my files** (off by default, needs both settings above): the remote desktop
  may paste files and folders you copied on your computer, after you allow each copy.
- **Shared folders** (none by default): local folders the remote desktop sees as drives. See
  [Shared folders](#shared-folders).
- **Play sound on this computer** (off by default): the remote desktop's sound plays through your
  speakers. See [Sound and microphone](#sound-and-microphone).
- **Use this computer's microphone** (off by default): the remote desktop may ask for your
  microphone. jterm asks you before it hears anything, once per connection.
- **Print through jterm** (off by default): the remote desktop gets a printer called **jterm**.
  What you print to it waits in the session's bar until you print or save it. With **Make it the
  default printer** it is the remote session's default. **Print without asking on** sends jobs
  straight to one of your printers instead, within limits. See [Printing](#printing).
- **Use smart cards** (off by default) with the **Readers** ticked below it: the remote desktop
  may use smart cards in those readers. jterm asks before it uses a card, once per connection. See
  [Smart cards](#smart-cards).

**Security**

- **Allow TLS without NLA** (off by default). With it off, the server must support **Network Level
  Authentication (NLA)**: before your password is sent, the server has to prove it already knows
  it. That protects the password even if you accept a fake server's certificate the first time you
  connect. Turn it on for servers that can't do NLA, such as **xrdp** or a Windows computer where
  NLA has been switched off. The server's certificate is still checked either way.
- **Use Windows sign-in (Kerberos)** (Windows only, off by default). Signs in with Windows' own
  authentication, which can use Kerberos, for servers in a domain that refuses NTLM. That session's
  helper then runs with fewer restrictions; see "Requirements and limitations". Not exported.

**Gateway** (how the desktop is reached: **Directly**, the default, or one of these)

- **Through an SSH session**: reach the desktop through one of your saved SSH
  sessions, for a computer that is only reachable from a server you can SSH into. jterm connects
  that SSH session first, with its own jump hosts, keys, agent and saved password, and forwards the
  RDP connection through it. The **host and port** on the Basic settings tab are then seen **from
  the SSH server**: use `localhost` for a desktop running on the SSH server itself, or an internal
  name or address that the SSH server can reach. The desktop's certificate is still checked
  against the host name you entered, and the forward listens only on your own computer
  (`127.0.0.1`) while the session is open.
- **Through an RD Gateway (experimental)**: connect through a Remote Desktop Gateway, as many
  company networks require. This route has not been tested against a real gateway yet; if it fails
  for you, please report what the pane says. Enter the gateway's host (and port, 443 unless your administrator says otherwise). By
  default the gateway uses the same user name, domain and password as the desktop; turn **Use
  desktop credentials** off to give it its own, with the option to save that password too. jterm
  checks the gateway's certificate like the desktop's and asks you to confirm one it can't verify,
  saying it is the gateway's. The gateway is always used; jterm never tries the desktop directly.
  A separately saved gateway password is not included when you export sessions.

RDP sessions can be edited, duplicated, deleted and dragged into other folders, just like SSH
sessions. You can also drag one out of the sidebar:

- **onto a pane** to open it as a split beside that pane: drop on the top part for a new column,
  on the bottom part for a new row. If that direction is already full (three columns or three
  rows), it opens in a new tab instead; a pane is never replaced;
- **onto an empty cell** to open it there;
- **onto the tab strip's "+"** to open it in a new tab.

**Open All in Separate Tabs** and **Open All in Split Panes** on a folder include its RDP sessions.
Each RDP session gets its own tab either way: split panes are for terminals, and a remote desktop
in a ninth of the window is too small to use.

## Opening a session

Double-click the session, or right-click it and choose **Open** (a new tab), **Open in Active
Pane**, or **Open in Split Pane**. You can also drag it onto a pane, which splits that pane, or
onto the tab strip's **+**.

**macOS:** the first time jterm connects to a computer on your local network, macOS asks whether
jterm may "find devices on your local network". Choose **Allow**; jterm waits for your answer and
then connects. If you chose **Don't Allow**, turn jterm on in **System Settings → Privacy &
Security → Local Network**.

When you connect without a saved password, jterm asks for it and offers to **remember** it. The
password is only saved once the server accepts it. If the server rejects the user name or password,
jterm asks again.

## Verifying the server

The first time you connect to a server whose certificate jterm can't verify, which is normal for
self-signed certificates, you are shown the certificate's **SHA-256 fingerprint**. Compare it with
the server's own before you click **Trust**. Some ways to find it:

- **GNOME Remote Desktop:** `grdctl status` (or `sudo grdctl --system status` for remote login) shows
  the certificate file; `openssl x509 -noout -fingerprint -sha256 -in <file>` prints the fingerprint.
- **Windows:** the fingerprint of the Remote Desktop certificate in the computer's certificate store
  (*Remote Desktop* folder).

jterm remembers trusted fingerprints in `rdp_known_hosts.json` in the
[configuration directory](config-files.md). If a server you trusted later presents a **different**
certificate, jterm shows a warning, because that can mean someone is intercepting the connection.
Only trust the new certificate if you know the server's certificate was replaced.

There is deliberately no option to ignore certificate errors.

## Keyboard and mouse

While a remote desktop has focus, **keys go to the remote computer**: Ctrl+C, Ctrl+T, Ctrl+W and
the function keys all act on the remote desktop. Only jterm shortcuts that use **both Ctrl and
Shift** (such as ++ctrl+shift+t++ for a new tab) still work. Characters are sent in a way that
doesn't depend on the remote keyboard layout matching yours.

**macOS:** the **Cmd** key acts as **Ctrl** on the remote computer, so Cmd+C, Cmd+V, Cmd+Z and the
other shortcuts your fingers expect work there too, and Ctrl still works as Ctrl. To send Cmd as
the Windows key instead, turn off **Preferences → General → Cmd acts as Ctrl in RDP**. Shortcuts
macOS keeps for itself (Cmd+Tab, Cmd+Space) are not sent, and **Cmd+Q quits jterm**, as in any
Mac app: it is never sent as Ctrl+Q, even in full screen. When remote desktops, SSH sessions or
tunnels are open, jterm asks first and names what would be disconnected (a full-screen desktop
returns to its pane before the question; **Cancel** leaves it there, and ++ctrl+shift+f11++ goes
back).

The Windows key and Alt+Tab can't be captured. Use the **Ctrl+Alt+Del** button in the pane's bottom
bar, or ++ctrl+shift+del++ (**Terminal → Send Ctrl+Alt+Del**, rebindable in the shortcut settings),
to send that combination.

## Full screen

To show a remote desktop full screen, press ++ctrl+shift+f11++, click **Full screen** in the pane's
bottom bar, or choose **View → Full Screen (RDP)**. It opens on the monitor the jterm window is on,
and the remote desktop is resized to fit it (unless the session uses a fixed resolution). The pane
stays in its tab and shows **Return**, which brings the desktop back.

While full screen, every key goes to the remote computer, jterm shortcuts and macro hotkeys
included, except ++ctrl+shift+f11++ (leave full screen) and ++ctrl+shift+del++ (send
Ctrl+Alt+Del). Shortcuts your own computer keeps for itself still work there: Alt+Tab, the Windows
or Super key and the local Ctrl+Alt+Del take you out of the remote desktop. Closing the full-screen
window (Alt+F4, the taskbar) also leaves full screen; the session stays connected.

Rest the pointer on the top edge of the screen, in its middle third, for half a second to show the
**full-screen bar**. It shows the session, the folders the server can reach, **Save files…**,
the **Sound** and **Mic** buttons, **Ctrl+Alt+Del**, **Win** (opens Start on the remote computer)
and **Exit full screen**. It hides
a second after the pointer leaves it, unless **Pin** is on. It also shows for a few seconds each
time you enter full screen.

jterm never shows one of its own dialogs over a full-screen desktop: before asking anything (a
certificate, a password, whether to paste files, where to save them) it leaves full screen first.
A disconnect leaves it too. **Anything asking for a password in full screen is the remote
computer.**

On macOS the desktop opens in its own Space, like other full-screen Mac apps; the green window
button and Ctrl+Cmd+F also leave it.

## Clipboard

With **Share clipboard** on, copy and paste work between your computer and the remote desktop for
**text, formatted text (HTML) and images**, and a single copy is limited to 32 MiB. Files have
settings of their own (see [Copying files](#copying-files)); other formats are not shared.

- Copying on the remote desktop puts the text or image on your clipboard straight away. Formatted
  text, from a browser or Office for example, arrives as both HTML and plain text, so a word
  processor keeps the formatting and a terminal gets the plain text. When the remote copy holds
  text and an image, the text is used.
- Copying on your computer makes it available to the remote desktop. The content itself is only
  read and sent when something there pastes.
- Images are sent without transparency (a transparent area becomes white).
- Opening the session doesn't overwrite your clipboard. Whatever the remote desktop held from
  before is not copied over; your clipboard is offered to the remote desktop instead, so pasting
  there right after connecting pastes what you copied on your computer. Copies you make on the
  remote desktop once you start typing or clicking in it come over as usual.
- If you copy on your computer and then click into the remote desktop, paste there as usual. jterm
  tells the server again when the desktop gains focus.
- What you copy on the remote desktop that jterm doesn't share, such as files in Explorer, stays on
  the remote clipboard: switching away and back doesn't replace it unless you copied something new
  on your computer meanwhile.

With sharing off, the server isn't told that a clipboard exists.

## Copying files

Files and folders can be copied through the clipboard in either direction, each with its own
setting under **Local resources** (both off by default). A shared folder is the other way to move
files.

**From the remote desktop to this computer** (**Copy files to this computer**): copy files in the
remote Explorer as usual. The pane's bottom bar (and the full-screen bar) then shows **Save files…
(n files, size)**. Nothing is written until you click it and pick a folder. Then:

- Folders are copied with their contents. When a file already exists you're asked **Yes**,
  **Yes to All**, **No**, **No to All** or **Cancel**; it is replaced only on a yes.
- A progress window shows the save. You can keep using the remote desktop meanwhile, even copy
  something else there.
- Each file is written under a hidden temporary name and gets its real name only when complete,
  so a cancelled or failed copy never leaves a half-written file under the real name.
- On Windows and macOS, saved files are marked as downloaded from the internet, as a browser would
  mark them, so the system warns before running them (Linux has no such mark).
- jterm checks the free space first and won't fill the disk: it keeps at least 1 GiB, or 5% of
  the disk if that is more, free.
- jterm refuses to save into its own settings folder or into `.ssh`, even through a copied folder
  of that name.
- If the remote copy changes while saving (a new copy on the remote desktop), the save finishes
  when the server keeps the old copy available, and otherwise stops with a message.

**From this computer to the remote desktop** (**Remote can paste my files**): copy files on your
computer, then paste in the remote desktop. On the first paste jterm asks whether to allow it,
naming what was copied (for example "3 files and 1 folder, 2.1 GB"). **Allow** holds for that
copy; **Don't allow** refuses it, and the remote desktop isn't told anything about the files.
Copying again asks again.

- Links are never followed, and jterm's own settings folder and `.ssh` are left out.
- A copy is limited to 10 000 files and folders, nested at most 32 deep, with names Windows
  accepts. A copy that doesn't fit is refused as a whole, with a message saying why.
- A file that changed after you copied it is not sent; copy it again.

## Shared folders

Add folders under **Local resources → Shared folders**. Each appears on the remote desktop as a
drive, `<name> on <this computer>`, also reachable as `\\tsclient\<name>`.

- **Read-only** is ticked for every folder you add: the remote desktop can open and copy its files
  but change nothing. Untick it to let the remote desktop create, change, rename and delete files
  there too.
- **Name on the remote** defaults to the folder's name. Only letters, digits, `-` and `_` are used.
- The remote computer can read **every** file in a shared folder, and Windows may scan it by
  itself, for thumbnails and search. Share the folder you need, not your whole home folder.
- **Never shared:** jterm's own settings folder (it holds the password vault) and your `.ssh`
  folder (your SSH keys). They can't be added, and they stay hidden even inside a folder you do
  share, such as your home folder. Sharing your home folder or a whole drive asks for confirmation.
- **Links are not followed.** A symbolic link (or, on Windows, a junction) inside a shared folder
  is invisible to the remote desktop, so it can never lead outside the folder.
- Files the remote desktop writes are **marked as downloaded** on Windows and macOS, so the system
  warns before you run them. Office opens them in Protected View.
- The remote desktop can't fill your disk: a write that would leave less than 1 GiB (or 5 %) free
  is refused.
- While connected, the pane's bottom bar shows **Shared: N folders**; hover over it to see which
  folders, and whether each is read-only.
- Shared folders belong to this computer: they are **not** included when you export sessions, and
  an imported session shares nothing until you add folders yourself.
- In the Flatpak, only folders inside your home folder can be shared.
- Changes made on this computer appear in the remote Explorer after a refresh (F5).

## Sound and microphone

**Sound.** With **Play sound on this computer** on, the remote desktop's sound plays through your
computer's default speakers or headset, mixed with your other applications. The pane's bottom bar
(and the full-screen bar) shows a **Sound** button: click it to mute that desktop, and again to
hear it. Muting keeps the sound channel open, so nothing on the remote side notices. Hover over
the button to see what is playing ("44.1 kHz stereo, PCM") or **No sound device**.

- The remote desktop's volume control can make the sound quieter, never louder than it is; your
  computer's own volume is never changed. Use your system's mixer to set jterm's level.
- A desktop in another tab keeps playing; mute it with its button.
- When the default sound device changes (you plug in a headset), the sound moves to it after a
  few seconds of silence.
- With the setting off, the remote desktop is told this computer has no sound device.

**Microphone.** With **Use this computer's microphone** on, the remote desktop can ask for your
microphone, for a call or a recording. It hears **nothing** until you agree:

- The first time it opens the microphone in a connection, jterm asks whether to allow it, with
  **Don't allow** selected. Until you answer, and after a "no", it hears silence. It asks once per
  connection; a reconnect asks again.
- While the remote desktop hears your microphone, the **Mic** button in the bottom bar (and in the
  full-screen bar) turns red and reads **● Mic on**, and the tab's title starts with **●**, so you
  can see it from another tab too.
- Click **Mic** to **mute** the microphone, or to **allow** or **block** it for this connection,
  also before the remote desktop has asked (it then isn't asked). The button reads **Mic muted** or
  **Mic blocked** accordingly. Muting is kept when you reconnect.
- Whenever the remote desktop may not hear you, jterm closes the microphone, so your system's own
  indicator (the microphone icon on Windows, the orange dot on macOS) goes off as well.
- Your system's microphone permission applies on top of jterm's. **macOS** asks the first time
  ("jterm would like to access the microphone"); answer **Allow**, or turn jterm on later in
  **System Settings → Privacy & Security → Microphone**. **Windows** doesn't ask: if **Settings →
  Privacy & security → Microphone → Let desktop apps access your microphone** is off, the remote
  desktop hears silence, and the button may still show **Mic on**.
- A microphone that records only in mono works when the remote desktop asks for stereo, and the
  other way round.
- The setting is never included when you export sessions, and an imported session always arrives
  with it off.

**Compressed sound (AAC).** A Windows server sends sound compressed with AAC when jterm can decode
it, at about 96 kbit/s instead of 1.4 Mbit/s uncompressed. jterm includes no audio codec, because
AAC is still patent-licensed; it uses your system's decoder:

| System | Decoder | What you do |
|---|---|---|
| Windows | Media Foundation, part of Windows | Nothing. (Windows "N" editions need Microsoft's Media Feature Pack.) |
| macOS | AudioToolbox, part of macOS | Nothing. |
| Linux (RPM, DEB, snap, jar) | `libfaad` from your distribution | Install it: `faad2-libs` (Fedora; EPEL on RHEL, Rocky and Alma), `libfaad2` (Debian, Ubuntu), `faad2` (Arch). jterm finds it at the next connection. |
| Linux Flatpak | FFmpeg, part of the Flatpak runtime | Nothing. |

The Sound button's tooltip says which one is playing ("44.1 kHz stereo, AAC" or "…, PCM").
**Preferences → General → Compressed sound (AAC) for RDP** turns AAC off; sound is then sent
uncompressed. It applies from the next connection.

The microphone is always sent uncompressed, about 1.4 Mbit/s while the remote desktop has it open
(silence counts too). Muting the sound doesn't reduce its traffic (the server keeps sending);
turning the session's setting off does.

## Printing

With **Print through jterm** on, the remote desktop has a printer called **jterm** (Windows shows
it as "jterm (redirected 2)" or similar). Print to it from any program there. The job comes to
jterm as a PDF and **waits**: nothing prints or is saved until you choose.

- The session's bottom bar (and the full-screen bar) shows **Print job: 3 pages**, or **2 print
  jobs**. Click it to see each job with a picture of its first page, and choose:
  - **Print…** opens your system's print dialog: pick the printer, copies and pages.
  - **Save as PDF…** saves the document. Windows and macOS mark the file as downloaded, so it opens
    with the usual warnings.
  - **Discard** throws it away.
- Jobs stay after a disconnect, and are deleted when you close the session's pane or quit jterm.
- **Paper size:** the remote side prints on US Letter unless you pick a paper size in the remote
  program's print dialog. Printing on A4 here shrinks each page to fit and centres it; for an exact
  size, choose A4 over there.
- **Print without asking on:** pick one of your printers in the session's settings, and jobs print
  there right away; the bar shows "Printed: 3 pages on …" for a moment. Limits: at most 50 pages a
  job and 10 jobs in 10 minutes. A job over a limit, a document jterm can't read, or any job while
  that printer isn't available, waits in the bar as usual. Use it only for computers you trust.
  This choice names one of your printers, so it isn't included when you export sessions.
- jterm only accepts PDF jobs (the printer uses Windows' **Microsoft Print To PDF** driver, which
  Windows 10, 11 and current Windows Server have). Jobs are limited to 256 MB each and 8 waiting
  (1 GB in all); a larger job is refused, never cut short, and the bar says why.
- jterm never shows the remote desktop your own printers: it sees only "jterm". The document
  itself is opened only by jterm (to count its pages, draw the preview and print it), never handed
  to your printer or another program as it came from the server.

## Smart cards

With **Use smart cards** on and at least one reader ticked, the remote desktop sees **only the
ticked readers**: a contactless reader, a second card or any reader you didn't tick doesn't exist
for it. The session's settings list the readers plugged in now; a reader that isn't plugged in can
be kept by typing its name.

- **jterm asks** the first time the remote desktop wants to use a card in a connection: "Allow for
  this connection" or "Don't allow" (the default). Until you answer, the remote side waits. Listing
  the readers and waiting for a card to be inserted don't ask, because Windows does both all the time.
- The bar (and the full-screen bar) shows **Smart card**, in red while the remote desktop has a card
  open. Its menu blocks or allows cards for the rest of the connection. Blocking resets the card,
  which also forgets the PIN it had been given.
- **A PIN you type in the remote desktop passes through the remote computer**, so a computer you
  don't trust learns it. A reader with its own PIN pad avoids that, and its PIN entry is let through.
- **Wrong PINs:** after a wrong PIN with one try left, jterm asks before the remote desktop may try
  again, so it can't block your card without you noticing. A card that does get blocked is named in
  the Smart card button's tooltip.
- The remote desktop can use the card as the card allows: read its certificates, sign and decrypt
  with it, and log on with it where the remote computer accepts the card (websites, VPNs). It can't change your readers, see
  their serial numbers or take the card away from programs on your own computer: a request for
  exclusive use is turned into shared use.
- Each reconnect asks again, and everything the remote desktop held is released and the card reset
  when the connection ends.
- **Signing in to the remote computer with the card itself** (instead of a password) isn't
  supported: that needs Kerberos on your side. Sign in with a password; the card is there once you're
  in.
- Needs your system's smart-card service: built into Windows and macOS; on Linux the `pcscd`
  service (`pcsc-lite`, or the `pcscd` package on Debian and Ubuntu). The Flatpak reaches it through
  its `pcsc` permission. Readers and reader names, and whether smart cards are on, aren't included
  when you export sessions.

## When the connection ends

A disconnected desktop stays in its pane with **Reconnect** and **Close** buttons and the reason it
ended.

## H.264 video

H.264 is smoother and lighter on the network than the fallback formats. Where the decoder comes from
depends on the system, because H.264 is patent-encumbered and jterm cannot simply ship one:

| System | Decoder | What you do |
|---|---|---|
| Windows | Media Foundation, part of Windows | Nothing. (Windows "N" editions need Microsoft's Media Feature Pack.) |
| macOS | VideoToolbox, part of macOS | Nothing. |
| Linux (RPM, DEB, snap, Flatpak, jar) | Cisco's OpenH264 | Turn on **Preferences → General → H.264 video for RDP**. jterm downloads Cisco's decoder (about 0.6 MB) into your settings folder, checks it against a known checksum, and you can turn it off again, which deletes it. When a jterm update pins a newer decoder, jterm replaces it in the background; until then, sessions run without H.264. |

*OpenH264 Video Codec provided by Cisco Systems, Inc.* Cisco's patent license covers the decoder when
it is downloaded to your machine as above. jterm's installers contain no H.264 codec.

Without a decoder the session still works; the server just sends another format.

### When video stutters

Right-click the session's name in the pane's bottom bar and tick **Show performance**. While the
picture is changing, the bar then shows a line such as:

`30 fps · H.264 4:4:4 · 442 MB/s · pipe 4.1 ms (max 9) · copy 1.2 ms · paint 1.0 ms (max 3), 30/s`

- **fps** is what the server sends. A low figure with small times after it means the server, or
  the network, is the limit, not jterm.
- The **codec** should be H.264 for smooth video. "RemoteFX progressive" or "bitmap codecs" means
  the server isn't using H.264: see the table above, and on a Windows server the group policy
  *Prioritize H.264/AVC 444 graphics mode for Remote Desktop connections*.
- **pipe**, **copy** and **paint** are jterm's own time for each frame. A 30 fps video leaves 33 ms
  for all three together.
- **scaled** at the end means the remote desktop has another size than the pane (a fixed
  resolution, or a server that can't resize) and is being resized to fit, which costs paint time.
  **Fit to pane** avoids it.

The line ends with the sound, when the session plays sound, for example `· sound 44.1 kHz stereo,
120 ms queued (lowest 45 ms with the output, aim 40)`. The queued amount swings with every batch of
sound the server sends (Windows sends about five a second); **lowest** is how little was waiting to
be heard, just before a batch arrived, during the last second. jterm keeps that close to **aim**, so
the sound stays as close to the picture as the network allows: anything above it only adds delay and
is **cut to catch up**, in a quiet moment if one comes within a second, otherwise in short steps
blended so they don't click. **underruns** count the moments the sound ran out (a crackle or a gap),
usually because the network delivered it late; after one that was a late delivery, the aim goes up
by 20 ms, and back down after two minutes without another. **dropped** is sound lost because far too
much had piled up. Windows starts each new sound with more than half a second at once, mostly
silence; the silence skipped then counts as cut, not dropped.

Sound that is late even with a low **lowest** comes from before jterm: the server, or a Bluetooth
headset, which adds a delay of its own. A Windows server sends sound in batches of about 0.2 s, so
it is never quite in step with the picture, in Remote Desktop Connection either.

## Requirements and limitations

- RDP support uses a bundled FreeRDP helper. If a build of jterm doesn't include it, the pane says
  so instead of connecting.
- The helper doesn't inherit jterm's environment variables, so `https_proxy` and similar proxy
  settings don't apply to RDP connections. To reach a desktop you can't connect to directly, use
  the session's **Gateway** tab (through an SSH session or an RD Gateway).
- **On Linux the helper is sandboxed** once it starts talking to the server: it can read only the
  system's certificate store and time zone, connect only to the session's port, and can't start
  programs, open other kinds of connections or reach your files, SSH agent or keyring. A server
  that took over the helper through a FreeRDP bug would be stuck there. Hover over the session name
  in the pane's bar to see how much of the sandbox your system supports ("full" needs a kernel with
  Landlock network rules: Linux 6.7 or later, or the current RHEL/Rocky 9 kernel; older kernels get
  part of it). **On Windows** the helper runs with a restricted account token: it can't read or
  change your files, saved passwords or settings, use the microphone, start programs, use the
  clipboard directly or reach jterm and other programs; it can still connect to any computer.
  macOS has no sandbox for the helper yet.
- Network Level Authentication uses **NTLM**. On Linux and macOS that is all there is, so a
  server in a domain that refuses NTLM can't be reached. On Windows, turn on **Use Windows sign-in
  (Kerberos)** on the session's Security tab for such servers: jterm then signs in with Windows'
  own authentication, but that session's helper runs with fewer restrictions (it could read your
  files and saved passwords if a server took it over), so use it only where it's needed. The
  setting is not included when you export sessions.
  When a server redirects you to another computer, as Remote Desktop Services farms do, jterm looks
  up that computer's name for the helper.
- **The server must support NLA** (Network Level Authentication), as Windows does by default
  and GNOME Remote Desktop does, unless you turn on **Allow TLS without NLA** for the session
  (needed for xrdp). A server without NLA fails with a message pointing to that setting.
- jterm always refuses the older **"RDP security"** layer, which Windows XP used as a host: it can't
  verify the server's identity, so an attacker on the network could pose as the server and receive
  your password. A server that offers nothing else can't be reached; enable TLS or NLA on it.
- Clipboard sharing covers text, formatted text, images and files, and only what you turn on for
  the session. Sound, the microphone, the "jterm" printer and the ticked smart-card readers are
  shared only when turned on for the session. Your own printers, other smart-card readers, serial
  ports and other devices are never shared.
- A full-screen desktop covers one monitor. Alt+Tab and the Windows key stay with your own
  computer; they can't be sent to the remote desktop.
- Full-screen video is only smooth when the server can encode **H.264**. jterm decodes it when the
  server offers it. A Linux server without a hardware video encoder sends a slower format.
  See "H.264 video" above for where the decoder comes from on each system.
- GNOME Remote Desktop applies display scaling only in whole steps (100% or 200%) unless fractional
  scaling is enabled on the server.
