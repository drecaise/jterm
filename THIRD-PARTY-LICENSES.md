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
| SLF4J Simple | `org.slf4j:slf4j-simple` | 2.0.13 | MIT | https://opensource.org/licenses/MIT |

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
