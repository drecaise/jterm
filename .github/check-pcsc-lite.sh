#!/usr/bin/env bash
#
# Fail when the pcsc-lite client library the Flatpak bundles is out of date or its pin no longer
# matches the published tarball.
#
#   .github/check-pcsc-lite.sh
#
# The Flatpak runtime has no libpcsclite, so the manifest builds pcsc-lite's client library from a
# pinned tarball (packaging/flatpak/com.katmoda.jterm.yml). It runs in the jterm process that holds
# the vault and talks to pcscd, and a fix reaches Flatpak users only with a jterm release. The
# Maven dependency scan can't see it. The RPM, DEB and Snap use the distribution's library.
#
# Checks:
#   hash       the pinned sha256 matches the release tarball (skip with PCSC_LITE_CHECK_OFFLINE=1)
#   freshness  any newer stable release: a warning for $grace_days days after it appeared, then an
#              error. Try a candidate with PCSC_LITE_CHECK_VERSION=<version>.
#
# Needs bash, curl, jq, sha256sum. GITHUB_TOKEN (optional) avoids the API's anonymous rate limit.
# Exit status: 0 fine, 1 something to fix, 2 the check itself could not run -- never a silent pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="packaging/flatpak/com.katmoda.jterm.yml"
grace_days=14

failed=0

report() { # report <error|warning> <message>
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$manifest::$2"
  else
    echo "$1: $manifest: $2"
  fi
  if [ "$1" = error ]; then failed=1; fi
}

die() {
  echo "check-pcsc-lite: $*" >&2
  exit 2
}

for tool in curl jq sha256sum; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done

version_lt() { # whether version A sorts strictly before version B
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

# The pin: the module's archive url and the sha256 on the line after it.
url="$(grep -oE 'https://pcsclite\.apdu\.fr/files/pcsc-lite-[0-9.]+\.tar\.xz' "$root/$manifest" | head -n 1)"
[ -n "$url" ] || die "no pcsc-lite tarball url in $manifest"
pinned="$(sed -E 's/.*pcsc-lite-([0-9.]+)\.tar\.xz/\1/' <<<"$url")"
sha="$(grep -F -A1 "$url" "$root/$manifest" | sed -nE 's/^[[:space:]]*sha256:[[:space:]]*([0-9a-f]{64})[[:space:]]*$/\1/p')"
[[ "$sha" =~ ^[0-9a-f]{64}$ ]] || die "no sha256 on the line after $url in $manifest"
version="${PCSC_LITE_CHECK_VERSION:-$pinned}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "unreadable pcsc-lite version '$version'"
echo "pinned: pcsc-lite $pinned${PCSC_LITE_CHECK_VERSION:+ (checking candidate $version)}"

# --- hash ------------------------------------------------------------------------------------

if [ -n "${PCSC_LITE_CHECK_OFFLINE:-}" ]; then
  echo "skipped: pcsc-lite hash (offline)"
elif [ -z "${PCSC_LITE_CHECK_VERSION:-}" ]; then
  actual="$(curl -fsSL --retry 3 --max-time 120 "$url" | sha256sum | cut -d' ' -f1)" \
    || die "could not download $url"
  if [ "$actual" != "$sha" ]; then
    report error "sha256 of $url is $actual, the manifest pins $sha"
  else
    echo "ok: pcsc-lite sha256 matches the release tarball"
  fi
fi

# --- freshness -------------------------------------------------------------------------------

auth=()
[ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
list="$(curl -fsSL --retry 3 --max-time 30 -H "Accept: application/vnd.github+json" "${auth[@]}" \
    "https://api.github.com/repos/LudovicRousseau/PCSC/releases?per_page=100" \
  | jq -r '.[] | select(.draft | not) | select(.prerelease | not)
           | [.tag_name, (.published_at // "" | .[0:10])] | @tsv')" \
  || die "could not read pcsc-lite's releases (set GITHUB_TOKEN if this is the API rate limit)"
[ -n "$list" ] || die "pcsc-lite's release list is empty"

newer="$(while IFS=$'\t' read -r tag date; do
    v="${tag#pcsc-}"
    [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    if version_lt "$version" "$v"; then printf '%s\t%s\n' "$v" "$date"; fi
  done <<<"$list" | sort -V | sed '/^$/d')"

if [ -z "$newer" ]; then
  echo "ok: pcsc-lite $version is current"
else
  latest="$(tail -n 1 <<<"$newer" | cut -f1)"
  since="$(head -n 1 <<<"$newer" | cut -f2)"
  [[ "$since" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "unreadable release date '$since'"
  deadline="$(date -u -d "$since +$grace_days days" +%F)"
  message="pcsc-lite $latest is out (pinned: $version, first newer release $since); update the url and its sha256"
  if [[ "$(date -u +%F)" > "$deadline" ]]; then
    report error "$message"
  else
    report warning "$message by $deadline"
  fi
fi

exit "$failed"
