#!/usr/bin/env bash
#
# Fail when the FreeRDP bundled with the RDP helper is out of date, has a published security
# advisory, or is pinned inconsistently.
#
#   .github/check-freerdp.sh
#
# FreeRDP is native code bundled on every target (see docs/architecture/adr/0012), so the Maven
# dependency scan can't see it, and distributions don't patch it for us: a FreeRDP security
# release means a jterm release. The pin lives in native/rdp-helper/FREERDP_VERSION; the Flatpak
# manifest repeats it (with the tarball's sha256), which is where drift can creep in.
#
# Checks:
#   consistency  the Flatpak manifest builds the pinned version, and its sha256 matches the
#                tarball (skip the download with FREERDP_CHECK_OFFLINE=1)
#   advisories   any published GitHub security advisory for FreeRDP/FreeRDP whose patched
#                version is newer than the pin is an error
#   freshness    a newer 3.x release is a warning for $grace_days days after it appeared, then an
#                error -- FreeRDP ships its security fixes in regular releases
#
# Needs bash, curl, jq, sha256sum. GITHUB_TOKEN (optional) avoids the API's anonymous rate limit.
# Exit status: 0 fine, 1 something to fix, 2 the check itself could not run -- never a silent pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version_file="native/rdp-helper/FREERDP_VERSION"
flatpak_manifest="packaging/flatpak/com.katmoda.jterm.yml"
repo="FreeRDP/FreeRDP"
grace_days=14

failed=0

# report <error|warning|notice> <file> <message>: a workflow annotation on GitHub, a plain line
# anywhere else.
report() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$2::$3"
  else
    echo "$1: $2: $3"
  fi
  if [ "$1" = error ]; then failed=1; fi
}

die() {
  echo "check-freerdp: $*" >&2
  exit 2
}

for tool in curl jq sha256sum; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done

api_get() { # api_get <url> <header file>: the body on stdout, the response headers in the file
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL --retry 3 --max-time 30 -H "Accept: application/vnd.github+json" "${auth[@]}" \
    -D "$2" "$1"
}

# api_all <path>: every page of a list endpoint as one JSON array. Follows the Link header's
# rel="next" (the advisories endpoint pages by cursor and ignores ?page=).
api_all() {
  local url="https://api.github.com/repos/$repo/$1" headers out="[]" chunk pages=0
  headers="$(mktemp)"
  while [ -n "$url" ]; do
    chunk="$(api_get "$url" "$headers")" || { rm -f "$headers"; return 1; }
    out="$(jq -s 'add' <(echo "$out") <(echo "$chunk"))"
    url="$(tr -d '\r' < "$headers" | sed -nE 's/^[Ll]ink:.*<([^>]*)>; rel="next".*/\1/p')"
    pages=$((pages + 1))
    [ "$pages" -lt 50 ] || { rm -f "$headers"; echo "more than 50 pages from $1" >&2; return 1; }
  done
  rm -f "$headers"
  echo "$out"
}

# version_lt A B: whether version A sorts strictly before version B.
version_lt() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

pinned="$(tr -d '[:space:]' < "$root/$version_file")"
# FREERDP_CHECK_VERSION checks another version than the pin (to exercise the check itself).
pinned="${FREERDP_CHECK_VERSION:-$pinned}"
[[ "$pinned" =~ ^3\.[0-9]+\.[0-9]+$ ]] || die "$version_file does not hold a FreeRDP 3.x version: '$pinned'"
echo "pinned: FreeRDP $pinned"

# --- consistency -----------------------------------------------------------------------------

url="$(grep -oE 'https://github.com/FreeRDP/FreeRDP/archive/refs/tags/[^[:space:]]+\.tar\.gz' \
  "$root/$flatpak_manifest" | head -n 1)"
[ -n "$url" ] || die "no FreeRDP tarball url in $flatpak_manifest"
manifest_version="$(sed -E 's#.*/tags/([^/]+)\.tar\.gz#\1#' <<<"$url")"
manifest_sha="$(grep -A1 -F "$url" "$root/$flatpak_manifest" | sed -nE 's/^[[:space:]]*sha256:[[:space:]]*([0-9a-f]{64}).*/\1/p')"

if [ -n "${FREERDP_CHECK_VERSION:-}" ]; then
  echo "skipped: manifest consistency (FREERDP_CHECK_VERSION overrides the pin)"
elif [ "$manifest_version" != "$pinned" ]; then
  report error "$flatpak_manifest" "builds FreeRDP $manifest_version but $version_file pins $pinned"
else
  echo "ok: the Flatpak manifest builds FreeRDP $pinned"
fi
if [ -z "$manifest_sha" ]; then
  report error "$flatpak_manifest" "the FreeRDP source has no sha256"
elif [ -z "${FREERDP_CHECK_OFFLINE:-}" ]; then
  actual_sha="$(curl -fsSL --retry 3 --max-time 120 "$url" | sha256sum | cut -d' ' -f1)" \
    || die "could not download $url"
  if [ "$actual_sha" != "$manifest_sha" ]; then
    report error "$flatpak_manifest" "sha256 of $url is $actual_sha, the manifest says $manifest_sha"
  else
    echo "ok: the Flatpak manifest's sha256 matches the tarball"
  fi
fi

# --- advisories ------------------------------------------------------------------------------

advisories="$(api_all "security-advisories?state=published&per_page=100")" \
  || die "could not read $repo's security advisories (set GITHUB_TOKEN if this is the API rate limit)"

# The advisory data is not consistent: a "patched version" can sit below the vulnerable range,
# and ranges are sometimes free text ("2.0.0 through 3.32.1 and current master"). So a version
# counts as affected when EITHER a listed 3.x patched version is newer than it, OR it falls under
# an upper bound stated in the range ("<= X", "< X", "through X"). Erring towards flagging is the
# right side for a security check; a false alarm is resolved by reading the advisory.
affected_reason() {
  local range="$1" patched="$2" v bound op
  for v in ${patched//,/ }; do
    v="${v#v}"
    if [[ "$v" =~ ^3\.[0-9]+\.[0-9]+$ ]] && version_lt "$pinned" "$v"; then
      echo "fixed in $v"; return
    fi
  done
  while read -r op bound; do
    [[ "$bound" =~ ^3\.[0-9]+\.[0-9]+$ ]] || continue
    case "$op" in
      "<=" | through) { [ "$pinned" = "$bound" ] || version_lt "$pinned" "$bound"; } && { echo "affects up to $bound"; return; } ;;
      "<") version_lt "$pinned" "$bound" && { echo "affects below $bound"; return; } ;;
    esac
  done < <(grep -oE '(<=|<|through)[[:space:]]*v?3\.[0-9]+\.[0-9]+' <<<"$range" \
             | sed -E 's/^(<=|<|through)[[:space:]]*v?/\1 /')
  return 0
}

# One line per vulnerability entry: id, CVE (or -), severity, range, patched versions.
while IFS=$'\t' read -r ghsa cve severity range patched; do
  reason="$(affected_reason "$range" "$patched")"
  if [ -n "$reason" ]; then
    report error "$version_file" "FreeRDP $pinned is affected by $ghsa ($cve, $severity): $reason"
  fi
done < <(jq -r '.[] | . as $a | (.vulnerabilities // [])[]
                | [$a.ghsa_id, ($a.cve_id // "-"), ($a.severity // "unknown"),
                   (.vulnerable_version_range // ""), (.patched_versions // "")] | @tsv' <<<"$advisories")
echo "ok: checked $(jq length <<<"$advisories") published advisories"

# --- freshness -------------------------------------------------------------------------------

releases="$(api_get "https://api.github.com/repos/$repo/releases?per_page=50" /dev/null)" \
  || die "could not read $repo's releases (set GITHUB_TOKEN if this is the API rate limit)"
# Stable 3.x releases newer than the pin, oldest first: "version<TAB>published date".
newer="$(jq -r '.[] | select(.draft | not) | select(.prerelease | not)
                | [.tag_name, (.published_at // "" | .[0:10])] | @tsv' <<<"$releases" \
  | awk -F'\t' '$1 ~ /^3\.[0-9]+\.[0-9]+$/' \
  | while IFS=$'\t' read -r tag date; do
      if version_lt "$pinned" "$tag"; then printf '%s\t%s\n' "$tag" "$date"; fi
    done | sort -V)"

if [ -z "$newer" ]; then
  echo "ok: FreeRDP $pinned is the latest 3.x release"
else
  latest="$(tail -n 1 <<<"$newer" | cut -f1)"
  behind_since="$(head -n 1 <<<"$newer" | cut -f2)"
  [[ "$behind_since" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "unreadable release date '$behind_since'"
  deadline="$(date -u -d "$behind_since +$grace_days days" +%F)"
  today="$(date -u +%F)"
  message="FreeRDP $latest is out (pinned: $pinned, first newer release $behind_since); update $version_file and $flatpak_manifest"
  # ISO dates order the same as strings.
  if [[ "$today" > "$deadline" ]]; then
    report error "$version_file" "$message"
  else
    report warning "$version_file" "$message by $deadline"
  fi
fi

exit "$failed"
