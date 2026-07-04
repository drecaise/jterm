# Architecture

This section documents jterm's internal design for **contributors and reviewers**. End
users can safely skip it — it does not describe features or configuration, but rather how
the source tree fits together and why the non-obvious pieces are the way they are.

The docs follow the [**C4 model**](https://c4model.com/): four static levels
(Context → Container → Component) plus dynamic and deployment views.

- [System context](context.md) — jterm's place in the world; users, remote hosts,
  the ssh-agent, the OS keyring, and the local file system.
- [Containers](containers.md) — the single JVM process and its OS-native peers.
- Components — grouped for readability:
    - [UI](components-ui.md) — window, tabs, pane grid, sidebar, SFTP, theming.
    - [Terminal](components-terminal.md) — session abstraction, local and SSH backends, ssh-agent stack.
    - [Security](components-security.md) — credential vault, keyring integration, trust boundaries.
- [Runtime views](runtime-views.md) — sequence diagrams for SSH connect, broadcast
  fan-out, and drop-to-split.
- [Deployment](deployment.md) — per-OS packaging and OS integration (Flatpak, MSI, DMG).
- [Decision records](adr/index.md) — short, dated ADRs for the sharp-edge choices.

## Reading the diagrams

Every static diagram uses colour to signal the C4 layer:

| Colour | Meaning |
| --- | --- |
| Indigo person | Human user |
| Blue | The `jterm` JVM container |
| Sky blue | UI components inside the JVM |
| Teal | Terminal / session components |
| Red | Security components |
| Brown | Persistence components |
| Grey | External systems (SSH daemon, ssh-agent, OS keyring, file system) |

Sequence diagrams call out **which thread** each participant runs on
(Swing EDT vs. `SwingWorker` vs. JediTerm's reader thread), because that separation is
load-bearing across the SSH connect and broadcast paths.

## How to edit these diagrams

The canonical source is [Structurizr DSL](https://structurizr.com/dsl) at
`docs/architecture/model/workspace.dsl`. Diagrams are exported to Mermaid under
`docs/architecture/model/generated/*.mmd` and included from the Markdown pages via
`pymdownx.snippets`. The workflow is:

1. Edit `docs/architecture/model/workspace.dsl`.
2. `cd docs/architecture/model && make export` to regenerate the `.mmd` files.
3. Run `mkdocs build --strict` from the repo root to validate.

The Structurizr CLI install steps and CI drift-check behaviour live in
`docs/maintaining-the-docs.md` alongside the rest of the docs-build guide.

If you edit a `.mmd` file directly without touching the DSL, the next `make export` will
overwrite your change. Always start at the DSL.

## What's intentionally missing

- **Class-level (C4 L4) diagrams.** Those go stale within a release. The Component
  diagrams name the important classes; the code is the source of truth beyond that.
- **A change log for the architecture.** That's what the [ADR log](adr/index.md) is for
  — each significant decision gets its own dated file.
- **Vendor internals.** JediTerm, MINA SSHD, pty4j, and FlatLaf are treated as opaque
  external components; only the integration seam is described.
