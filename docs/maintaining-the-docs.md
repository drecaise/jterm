# Maintaining the documentation

This guide is for **maintainers** of the jterm user manual — how to build, preview, publish, and
extend the docs. It is **not** part of the published site (it's excluded in `mkdocs.yml`).

The manual is a [MkDocs](https://www.mkdocs.org/) site using the
[Material](https://squidfunk.github.io/mkdocs-material/) theme. Source lives in `docs/`,
configuration in `mkdocs.yml`, and it publishes to **GitHub Pages** at
<https://drecaise.github.io/jterm/>.

## Prerequisites

- **Python 3.x** with `pip`.

Install the doc tooling (pinned versions) into a virtualenv:

```bash
python3 -m venv .venv-docs
source .venv-docs/bin/activate          # Windows: .venv-docs\Scripts\activate
pip install -r requirements-docs.txt
```

`requirements-docs.txt` pins `mkdocs-material` and `mkdocs-exclude`. No Java/Maven toolchain is
involved — the docs build is completely independent of the application build.

## Preview locally

```bash
mkdocs serve
```

Open <http://127.0.0.1:8000>. The site live-reloads as you edit files in `docs/`.

## Build (and validate)

```bash
mkdocs build --strict
```

`--strict` turns warnings into errors, so it fails on **broken internal links, missing pages, or
missing images**. CI runs exactly this command, so always run it before pushing. Output goes to
`site/` (git-ignored).

## Project layout

```
mkdocs.yml                 # site config, theme, nav, plugins
requirements-docs.txt      # pip dependencies for the docs build
docs/
  index.md                 # manual home page
  getting-started.md       # one .md file per manual page
  ...                      # (see the `nav:` block in mkdocs.yml)
  img/                     # screenshots (PNG)
  SCREENSHOTS.md           # screenshot shot list (excluded from the site)
  maintaining-the-docs.md  # this file (excluded from the site)
  licensing.md             # internal license analysis (excluded from the site)
  superpowers/             # internal design specs (excluded from the site)
```

## Adding a page

1. Create `docs/<your-page>.md`.
2. Add it to the `nav:` list in `mkdocs.yml` so it appears in the sidebar, e.g.:

   ```yaml
   nav:
     - Home: index.md
     - My new page: your-page.md
   ```

3. Run `mkdocs build --strict` to confirm there are no broken links.

Link between pages with **relative paths to the `.md` file** (MkDocs rewrites them), and use the
`#kebab-case` heading anchor for deep links:

```markdown
See [SSH auth & vault](ssh-auth-and-vault.md#the-credential-vault).
```

!!! warning "Avoid slashes in headings used as link targets"
    A heading like `## Import / export` produces an unexpected anchor. Prefer
    `## Import and export` (anchor `#import-and-export`) when you link to it.

## Adding screenshots

Screenshots are tracked in [`SCREENSHOTS.md`](SCREENSHOTS.md), which lists every expected
filename, what it should show, and which page uses it.

- Each page references its image as `![Alt text](img/<name>.png)`.
- The repo currently ships **labelled placeholder PNGs** ("Screenshot pending: …") so the strict
  build passes before real screenshots exist.
- To add a real screenshot, **overwrite the placeholder file with the same filename** in
  `docs/img/`. No page edits are needed.

Capture tips: use the **dark** theme, a ~1200×800 window, and crop to the relevant
window/dialog. Save as **PNG**.

## Architecture diagrams (C4 via Structurizr DSL)

The `architecture/` section documents jterm's internal design. Diagrams are authored in
[Structurizr DSL](https://structurizr.com/dsl) at `docs/architecture/model/workspace.dsl`
and exported to Mermaid under `docs/architecture/model/generated/*.mmd`. Markdown pages
include the generated files via `pymdownx.snippets`.

**Install the Structurizr CLI once:**

```bash
curl -sSL -o /tmp/structurizr-cli.zip \
  https://github.com/structurizr/cli/releases/latest/download/structurizr-cli.zip
mkdir -p ~/.structurizr-cli && unzip /tmp/structurizr-cli.zip -d ~/.structurizr-cli
sudo ln -sf ~/.structurizr-cli/structurizr.sh /usr/local/bin/structurizr-cli
```

The CLI needs a JDK 17+ on PATH. The Java toolchain from the app build is fine.

**Regenerate the Mermaid views after editing `workspace.dsl`:**

```bash
cd docs/architecture/model
make export      # writes generated/*.mmd (Structurizr export + postprocess.py)
make check       # regenerate and fail if committed output is stale (what CI runs)
```

CI runs `make check` from `docs/architecture/model` on every docs build. If the DSL
changed but the committed `.mmd` files weren't regenerated, the workflow fails.

`make export` runs `postprocess.py` after the Structurizr CLI. Structurizr bakes a
light-mode look into its output (white diagram/boundary backgrounds, black deployment-node
titles, a `linkStyle default fill:#ffffff`) that renders as a jarring white box in the
site's dark theme. The post-processor makes the diagrams **theme-adaptive**: it prepends a
Mermaid `%%{init}%%` directive (transparent edge-label background, neutral line color,
Material font stack) and rewrites the baked white backgrounds and black titles to
transparent/neutral gray, leaving the DSL `styles` element colors untouched. It's
idempotent, so re-running `make export` is byte-stable and drift detection still holds.
It needs `python3` (already required for the docs build; no extra pip deps).

!!! note "Don't hand-edit `.mmd` files"
    The Mermaid files under `generated/` are overwritten by `make export`. Style tweaks
    beyond what the DSL's `styles` block controls belong in the DSL or, for cross-theme
    presentation, in `postprocess.py`; changes made directly in `.mmd` files will be lost
    on the next regeneration.

## What's excluded from the published site

The `mkdocs-exclude` plugin (configured under `plugins.exclude.glob` in `mkdocs.yml`) keeps
internal/maintainer files out of the built site:

- `superpowers/**` — internal design specs
- `licensing.md` — internal license analysis
- `SCREENSHOTS.md` — the screenshot shot list
- `maintaining-the-docs.md` — this guide
- `img/README.md` — note in the images folder

If you add another internal file under `docs/`, add it to that glob list too.

## Publishing (CI → GitHub Pages)

Publishing is automated by [`.github/workflows/docs.yml`](../.github/workflows/docs.yml):

- **On every push and pull request** (touching `docs/`, `mkdocs.yml`, `requirements-docs.txt`,
  or the workflow): it installs the deps and runs `mkdocs build --strict`. PRs build but do
  **not** deploy, so a broken link fails the check before merge.
- **On pushes to `main`**: after a successful build it deploys the `site/` artifact to GitHub
  Pages via `actions/deploy-pages`.

### One-time repository setup

GitHub Pages must be switched to the **GitHub Actions** source once, or deploys will fail:

1. In the GitHub repo, go to **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **GitHub Actions**.

After that, every push to `main` republishes the manual automatically. The site URL is
<https://drecaise.github.io/jterm/>.

### Manual deploy (fallback)

If you ever need to publish from your machine instead of CI:

```bash
mkdocs gh-deploy --force
```

This builds and pushes to the `gh-pages` branch. Prefer the CI workflow for normal use.

## Where the manual is linked

- **README** — a "User Manual" link near the top of `README.md`.
- **In-app** — **Help → User Manual…** in jterm opens the published site
  (`MANUAL_URL` in `app/MainWindow.java`).

If the Pages URL ever changes, update both of those references.
