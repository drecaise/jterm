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

- **Share clipboard** (off by default): text and images you copy on the remote desktop are placed on
  your computer's clipboard, **without asking**. Turn it on for computers you trust.
- **Remote can paste mine** (on by default, available once sharing is on): applications on the
  remote desktop may paste what is on your clipboard. Nothing is sent until one of them pastes.
  Turn it off to share one way only, remote to local.

RDP sessions can be edited, duplicated, deleted and dragged into other folders, just like SSH
sessions.

## Opening a session

Double-click the session, or right-click it and choose **Open** (a new tab), **Open in Active
Pane**, or **Open in Split Pane**. Dropping an RDP session onto a pane is not supported yet.

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

The Windows key and Alt+Tab can't be captured. Use the **Ctrl+Alt+Del** button in the pane's bottom
bar to send that combination.

## Clipboard

With **Share clipboard** on, copy and paste work between your computer and the remote desktop for
**plain text and images**. Files, formatted text and other formats are not shared, and a single copy
is limited to 32 MiB.

- Copying on the remote desktop puts the text or image on your clipboard straight away. When the
  remote copy holds both, the text is used.
- Copying on your computer makes it available to the remote desktop. The content itself is only
  read and sent when something there pastes.
- Images are sent without transparency (a transparent area becomes white).
- Opening the session can bring the remote clipboard's current content onto your clipboard.
- If you copy on your computer and then click into the remote desktop, paste there as usual. jterm
  tells the server again when the desktop gains focus.

With sharing off, the server isn't told that a clipboard exists.

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
| Linux (RPM, DEB, snap, jar) | Cisco's OpenH264 | Turn on **Preferences → General → H.264 video for RDP**. jterm downloads Cisco's decoder (about 0.6 MB) into your settings folder, checks it against a known checksum, and you can turn it off again, which deletes it. |
| Linux Flatpak | The runtime's `org.freedesktop.Platform.openh264` extension | Nothing; Flatpak installs it with the runtime. |

*OpenH264 Video Codec provided by Cisco Systems, Inc.* Cisco's patent license covers the decoder when
it is downloaded to your machine as above. jterm's installers contain no H.264 codec.

Without a decoder the session still works; the server just sends another format.

## Requirements and limitations

- RDP support uses a bundled FreeRDP helper. If a build of jterm doesn't include it, the pane says
  so instead of connecting.
- **File and drive redirection are not available.** Clipboard sharing covers text and images
  only, and only when you turn it on for the session. Nothing else on your computer is shared with
  the server.
- Full-screen video is only smooth when the server can encode **H.264**. jterm decodes it when the
  server offers it. A Linux server without a hardware video encoder sends a slower format.
  See "H.264 video" below for where the decoder comes from on each system.
- GNOME Remote Desktop applies display scaling only in whole steps (100% or 200%) unless fractional
  scaling is enabled on the server.
