# ADR 0006: C4 documentation via Structurizr DSL + Mermaid

**Status:** Accepted
**Date:** 2026-07-04

## Context

Before this ADR, jterm had rich user documentation and one comprehensive `CLAUDE.md`
briefing for AI-assisted work, but no architecture documentation aimed at contributors.
Two shapes were considered:

- **Author diagrams directly in Mermaid** inside the Markdown pages. Simplest possible
  workflow: no build step, GitHub renders diagrams in PR review, MkDocs Material renders
  them in the site.
- **Structurizr DSL as canonical source, Mermaid as presentation format.** One
  workspace file (`docs/architecture/model/workspace.dsl`) defines the C4 model;
  Structurizr CLI exports Mermaid; Markdown pages `--8<--` the generated `.mmd` files.

## Decision

Adopt the DSL-canonical approach. `docs/architecture/model/workspace.dsl` defines
people, the `jterm` software system, containers, components, dynamic views,
deployment views, and styles. `make export` (wrapping `structurizr-cli`) writes
Mermaid views into `docs/architecture/model/generated/`. Markdown pages under
`docs/architecture/` include those files via `pymdownx.snippets`. MkDocs Material
renders the Mermaid via its bundled loader (wired through
`pymdownx.superfences.custom_fences`).

The initial commit ships hand-authored `.mmd` files as a bootstrap. The first
maintainer to run `make export` will regenerate them from the DSL; from then on the
DSL is the source of truth and any hand edit to a `.mmd` file is lost on the next
export.

Structurizr's exporter bakes a light-mode look into every file (`linkStyle default
fill:#ffffff`, white `fill`/`stroke` on the diagram wrapper and boundary subgraphs,
black deployment-node titles). MkDocs Material has a dark theme, so those render as a
white box with low-contrast text. Since the `.mmd` files are regenerated, the fix lives
in the export pipeline, not in hand edits: `make export` runs `postprocess.py` after the
CLI to make the diagrams theme-adaptive — it prepends a Mermaid `%%{init}%%` directive
(transparent edge-label background, neutral line color, Material font stack) and rewrites
the baked white backgrounds and black titles to `transparent`/neutral gray. Element node
colors (from the DSL `styles` block) are left untouched, and the step is idempotent so
`make check` drift detection still holds.

## Consequences

**Advantages**

- One place to change a component name or a relationship — every view stays in sync
  automatically.
- C4-model semantics are enforced by the DSL grammar (Person, SoftwareSystem,
  Container, Component). It's harder to accidentally mix levels.
- Drift is mechanically detectable via `make check` (`git diff --exit-code`) — a
  potential CI gate once the baseline is regenerated from the CLI.
- The published site keeps a single toolchain (MkDocs Material) with no new plugin
  installs.

**Disadvantages**

- Contributors need to install `structurizr-cli` locally to regenerate diagrams
  after changing the DSL. Documented in `docs/maintaining-the-docs.md`.
- Two artifact classes in diffs: the DSL and the generated `.mmd`. Reviewers see
  both, which is verbose but transparent.
- Structurizr's Mermaid export prioritises correctness over prettiness. Purely
  aesthetic tweaks are limited; styling beyond the DSL `styles` block must go through
  `postprocess.py` (applied on every export) rather than direct `.mmd` edits, which are
  lost on regeneration.

**Rejected alternatives**

- **Pure Mermaid, hand-authored.** Simpler day-1 but no drift detection and the same
  component labels drift across pages within a release. Reconsidered whenever the
  DSL toolchain becomes friction.
- **PlantUML with C4-PlantUML macros.** Needs Java + Graphviz plus a MkDocs plugin
  for rendering — strictly worse than Mermaid on our stack.
- **Structurizr Lite (Docker).** Nice interactive viewer, but delivers no static
  output for the published site; not on the critical path here.
