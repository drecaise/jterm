# Architecture Decision Records

Short, dated records for jterm's non-obvious architectural choices. Each ADR follows
[MADR](https://adr.github.io/madr/)-lite: **Context / Decision / Consequences**.

| # | Title | Status |
| --- | --- | --- |
| [0001](0001-uniform-grid-vs-split-tree.md) | Uniform R x C pane grid instead of a binary split tree | Accepted |
| [0002](0002-custom-jdk-agent-factory.md) | Custom JDK Unix-socket ssh-agent factory instead of MINA's APR-based one | Accepted |
| [0003](0003-exclude-java-keyring-linux-dbus.md) | Exclude java-keyring's dbus-java Linux backend | Accepted |
| [0004](0004-tofu-host-key-verification.md) | Trust-on-first-use host-key verification | Accepted |
| [0005](0005-per-os-keyring-clis.md) | Native OS keyring CLIs for Linux and macOS master-password storage | Accepted |
| [0006](0006-c4-with-structurizr-and-mermaid.md) | C4 documentation via Structurizr DSL + Mermaid | Accepted |
| [0007](0007-debounced-highlight-scans.md) | Debounce highlight scans off the emulator thread | Accepted |

## Writing a new ADR

1. Copy an existing file and increment the number.
2. Keep it to one screen — Context, Decision, Consequences, links.
3. Never edit an accepted ADR to change the decision. Add a new ADR that supersedes it
   and mark the old one **Superseded by NNNN** in a status line at the top.
4. Add the new file to the table above **and** to the `nav:` block in `mkdocs.yml`.
