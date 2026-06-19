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
