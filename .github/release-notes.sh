#!/usr/bin/env bash
#
# Extract release notes for a tag from the commit log.
#
#   .github/release-notes.sh v1.9.0            # notes for v1.9.0 (or HEAD if untagged)
#   .github/release-notes.sh v1.9.0 v1.8.0     # explicit previous tag
#
# This repo has no CHANGELOG and takes commits straight to master (no pull
# requests), so GitHub's own generate_release_notes finds nothing to list and
# emits only a compare link. The commit subjects are the release notes.
#
# Prints markdown on stdout; the Release workflow feeds it to action-gh-release
# as body_path. Needs full history + tags (actions/checkout fetch-depth: 0).
set -euo pipefail

tag="${1:?usage: release-notes.sh <tag> [previous-tag]}"
prev="${2:-}"

# The commit the notes end at: the tag if it exists (a real release build),
# otherwise HEAD (previewing notes for a tag not yet pushed).
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  head_ref="$tag"
else
  head_ref="HEAD"
fi

# Previous tag = highest version tag strictly below this one. Splicing "$tag"
# into the sorted list means this works whether or not the tag exists yet.
if [ -z "$prev" ]; then
  prev="$(
    { git tag --list 'v[0-9]*.[0-9]*.[0-9]*'; echo "$tag"; } \
      | sort -u -V \
      | awk -v cur="$tag" '$0 == cur { print last; exit } { last = $0 }'
  )"
fi

if [ -n "$prev" ]; then
  range="$prev..$head_ref"
else
  range="$head_ref"          # first release: everything
fi

# Newest first. Merge commits carry no information here (none exist today, but
# a future merge would only restate its branch's commits, which are listed).
notes="$(
  git log --no-merges --pretty=format:'%s' "$range" \
    | sed -E 's/^[-*[:space:]]+//; s/[[:space:]]+$//' \
    | grep -vEi '^(bump(ed)?( the)? version|version bump|release v?[0-9]|\[skip ci\])' \
    | grep -v '^$' \
    | awk '!seen[$0]++ { printf "- %s%s\n", toupper(substr($0,1,1)), substr($0,2) }' || true
)"

if [ -z "$notes" ]; then
  # Never emit an empty body -- action-gh-release would publish a bare release.
  notes="- Maintenance release; no user-visible changes."
fi

printf '## Changes\n\n%s\n' "$notes"
