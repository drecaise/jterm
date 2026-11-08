# Third-Party Licenses

`jterm` is distributed with the following third-party libraries. Each remains under its
own license; the texts are referenced below. This file is generated from the dependency
set declared in `pom.xml` and must be updated when dependencies change.

| Library | Coordinates | Version | License | License text |
|---|---|---|---|---|
| FlatLaf | `com.formdev:flatlaf` | 3.7.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| FlatLaf Extras | `com.formdev:flatlaf-extras` | 3.7.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| JediTerm Core | `org.jetbrains.jediterm:jediterm-core` | 3.70 | LGPL-3.0 | https://www.gnu.org/licenses/lgpl-3.0.txt |
| JediTerm UI | `org.jetbrains.jediterm:jediterm-ui` | 3.70 | LGPL-3.0 | https://www.gnu.org/licenses/lgpl-3.0.txt |
| pty4j | `org.jetbrains.pty4j:pty4j` | 0.13.12 | EPL-1.0 | https://www.eclipse.org/legal/epl-v10.html |
| Apache MINA SSHD Core | `org.apache.sshd:sshd-core` | 2.18.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| Apache MINA SSHD SFTP | `org.apache.sshd:sshd-sftp` | 2.18.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| EdDSA-Java (ed25519) | `net.i2p.crypto:eddsa` | 0.3.0 | CC0-1.0 (public domain) | https://creativecommons.org/publicdomain/zero/1.0/legalcode |
| Jackson Databind | `com.fasterxml.jackson.core:jackson-databind` | 2.19.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| java-keyring | `com.github.javakeyring:java-keyring` | 1.0.4 | ISC (see note) | https://opensource.org/licenses/ISC |
| Apache Commons Compress | `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| Apache Commons Codec / IO / Lang | `commons-codec`, `commons-io`, `org.apache.commons:commons-lang3` (via Commons Compress) | 1.19.0 / 2.20.0 / 3.18.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| SLF4J Simple | `org.slf4j:slf4j-simple` | 2.0.13 | MIT | https://opensource.org/licenses/MIT |

## Native components (RDP helper)

The RDP helper (`native/rdp-helper`, installed under `rdp/`) bundles the following native
libraries. Their license texts ship beside the binaries in `rdp/licenses/`. Versions are pinned
by `native/rdp-helper/FREERDP_VERSION` and, on Windows, by the `vcpkg.json` baseline.

| Library | Version | Bundled on | License | License text |
|---|---|---|---|---|
| FreeRDP (libfreerdp, libfreerdp-client, WinPR) | 3.33.0 | all targets | Apache-2.0 | https://github.com/FreeRDP/FreeRDP/blob/master/LICENSE |
| OpenSSL | 3.6.5 | Windows (Linux uses the system OpenSSL 3) | Apache-2.0 | https://www.openssl.org/source/license.html |
| zlib | 1.3.2 | Windows (Linux uses the system zlib) | Zlib | https://zlib.net/zlib_license.html |
| cJSON | 1.7.19 (Windows), distribution package (Linux) | all targets | MIT | https://github.com/DaveGamble/cJSON/blob/master/LICENSE |
| Microsoft Visual C++ runtime | 14.x | Windows | Microsoft redistributable | https://visualstudio.microsoft.com/license-terms/ |

On Windows, H.264 decoding uses the operating system's Media Foundation decoder; no H.264 codec is
bundled. On macOS it will use Apple's VideoToolbox.

**OpenH264 (Linux, optional, not bundled).** On Linux the helper can load Cisco's OpenH264 decoder.
jterm does not ship it: when the user turns on Preferences → General → "H.264 video for RDP", jterm
downloads Cisco's own binary from `ciscobinary.openh264.org` (checked against a pinned SHA-256) into
the user's settings folder. The Flatpak uses the runtime's `org.freedesktop.Platform.openh264`
extension instead. FreeRDP is built against OpenH264's BSD-licensed headers only.

> About the Cisco-provided binary of OpenH264: it is provided under the BSD license, and is licensed
> under Cisco's AVC/H.264 Patent Portfolio License from MPEG LA, at no cost to the user, provided that
> the conditions in https://www.openh264.org/BINARY_LICENSE.txt are met. This product's use of the
> binary is as follows: the Cisco-provided binary is separately downloaded to the end user's device and
> is not integrated into or combined with third party software prior to being downloaded; the end user
> can control (enable, disable or re-enable) its use; and the text "OpenH264 Video Codec provided by
> Cisco Systems, Inc." is displayed where the user controls it.
>
> AVC/H.264 PATENT PORTFOLIO LICENSE NOTICE: THIS PRODUCT IS LICENSED UNDER THE AVC PATENT PORTFOLIO
> LICENSE FOR THE PERSONAL USE OF A CONSUMER OR OTHER USES IN WHICH IT DOES NOT RECEIVE REMUNERATION TO
> (i) ENCODE VIDEO IN COMPLIANCE WITH THE AVC STANDARD ("AVC VIDEO") AND/OR (ii) DECODE AVC VIDEO THAT
> WAS ENCODED BY A CONSUMER ENGAGED IN A PERSONAL ACTIVITY AND/OR WAS OBTAINED FROM A VIDEO PROVIDER
> LICENSED TO PROVIDE AVC VIDEO. NO LICENSE IS GRANTED OR SHALL BE IMPLIED FOR ANY OTHER USE.
> ADDITIONAL INFORMATION MAY BE OBTAINED FROM MPEG LA, L.L.C. SEE HTTP://WWW.MPEGLA.COM

## Notices and obligations

### LGPL-3.0 — JediTerm (core + ui)
JediTerm is licensed under the GNU Lesser General Public License v3.0. jterm uses the
unmodified library. Per the LGPL, recipients are entitled to replace or relink the
JediTerm component with a modified version. The LGPL-3.0 license text is included by
reference above. Source for JediTerm is available at
https://github.com/JetBrains/jediterm.

### EPL-1.0 — pty4j
pty4j is licensed under the Eclipse Public License 1.0. jterm uses the unmodified
library. Per the EPL, the source code for the EPL-covered component is made available;
it can be obtained at https://github.com/JetBrains/pty4j. The EPL-1.0 includes a
patent-retaliation clause.

### Apache-2.0 — FlatLaf, Apache MINA SSHD, Jackson
These components are licensed under the Apache License 2.0. Their `NOTICE` files (where
provided) are preserved in the distributed artifact under `META-INF`.

### CC0-1.0 — EdDSA-Java
Dedicated to the public domain; no attribution required (provided here as a courtesy).

### ISC — java-keyring
The `java-keyring` 1.0.4 POM does not declare a license element. The upstream project
(https://github.com/javakeyring/java-keyring) is published under the ISC License. This
should be confirmed against the upstream `LICENSE` file before a commercial release.

### MIT — SLF4J Simple
Licensed under the MIT License.

### Apache-2.0 — FreeRDP, OpenSSL (RDP helper)
Unmodified FreeRDP and OpenSSL libraries, run in a separate helper process. The Apache-2.0 texts
ship in `rdp/licenses/`. Source: https://github.com/FreeRDP/FreeRDP (tag in `FREERDP_VERSION`) and
https://github.com/openssl/openssl. Apache-2.0 is compatible with jterm's GPL-3.0-or-later.

### Zlib, MIT — zlib, cJSON (RDP helper)
Permissive licenses; their texts ship in `rdp/licenses/`.
