#!/usr/bin/env bash
#
# Fail when the OpenH264 that jterm downloads on Linux is out of date, has a published security
# advisory, or is pinned inconsistently.
#
#   .github/check-openh264.sh
#
# OpenH264 is native code that decodes video the remote server controls, inside the RDP helper.
# jterm does not ship it (Cisco's patent license covers only the binary Cisco delivers; see
# terminal.rdp.OpenH264): it downloads Cisco's build and verifies a pinned SHA-256. That makes a
# vulnerable decoder stay on users' disks until jterm itself is updated, so -- like FreeRDP -- an
# OpenH264 security release means a jterm release, and the Maven dependency scan can't see it.
#
# The pin lives in four places:
#   OpenH264.java            VERSION and the SHA-256 of Cisco's two Linux binaries
#   build-freerdp-linux.sh   OH264_VERSION and the SHA-256 of the header tarball (build-time only)
#   the Flatpak manifest     the same header tarball and hash
#   (CLAUDE.md describes them; it is not machine-checked)
#
# Checks:
#   consistency  the three version pins agree and the two header hashes agree; each pinned hash
#                matches the real bytes (skip the downloads with OPENH264_CHECK_OFFLINE=1)
#   advisories   any published GitHub security advisory for cisco/openh264 that affects the pin
#   freshness    a newer release is a warning for $grace_days days after it appeared, then an error
#
# Needs bash, curl, jq, sha256sum. GITHUB_TOKEN (optional) avoids the API's anonymous rate limit.
# Exit status: 0 fine, 1 something to fix, 2 the check itself could not run -- never a silent pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
java_file="src/main/java/com/katmoda/jterm/terminal/rdp/OpenH264.java"
build_script="native/rdp-helper/build-freerdp-linux.sh"
flatpak_manifest="packaging/flatpak/com.katmoda.jterm.yml"
repo="cisco/openh264"
binary_base="https://ciscobinary.openh264.org"
grace_days=14

failed=0

report() { # report <error|warning|notice> <file> <message>
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$2::$3"
  else
    echo "$1: $2: $3"
  fi
  if [ "$1" = error ]; then failed=1; fi
}

die() {
  echo "check-openh264: $*" >&2
  exit 2
}

for tool in curl jq sha256sum; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done

api_get() { # api_get <url>: the body on stdout
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL --retry 3 --max-time 30 -H "Accept: application/vnd.github+json" "${auth[@]}" "$1"
}

version_lt() { # version_lt A B: whether A sorts strictly before B
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

# --- the pins --------------------------------------------------------------------------------

java_version="$(sed -nE 's/.*String VERSION = "([0-9.]+)".*/\1/p' "$root/$java_file")"
script_version="$(sed -nE 's/^OH264_VERSION=([0-9.]+).*/\1/p' "$root/$build_script")"
script_sha="$(sed -nE 's/^OH264_SHA256=([0-9a-f]{64}).*/\1/p' "$root/$build_script")"
manifest_url="$(grep -oE 'https://github.com/cisco/openh264/archive/refs/tags/v[0-9.]+\.tar\.gz' \
  "$root/$flatpak_manifest" | head -n 1)"
manifest_version="$(sed -E 's#.*/tags/v([0-9.]+)\.tar\.gz#\1#' <<<"$manifest_url")"
manifest_sha="$(grep -A1 -F "$manifest_url" "$root/$flatpak_manifest" \
  | sed -nE 's/^[[:space:]]*sha256:[[:space:]]*([0-9a-f]{64}).*/\1/p')"
sha_x64="$(grep -A1 -E '"amd64", "x86_64"' "$root/$java_file" | grep -oE '[0-9a-f]{64}' | head -n 1)"
sha_arm="$(grep -A1 -E '"aarch64", "arm64"' "$root/$java_file" | grep -oE '[0-9a-f]{64}' | head -n 1)"

for pin in java_version script_version script_sha manifest_url manifest_sha sha_x64 sha_arm; do
  [ -n "${!pin}" ] || die "could not read $pin (did the layout of the pin sites change?)"
done
pinned="${OPENH264_CHECK_VERSION:-$java_version}"
[[ "$pinned" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "not an OpenH264 version: '$pinned'"
echo "pinned: OpenH264 $pinned"

# --- consistency -----------------------------------------------------------------------------

if [ -n "${OPENH264_CHECK_VERSION:-}" ]; then
  echo "skipped: consistency (OPENH264_CHECK_VERSION overrides the pin)"
else
  if [ "$java_version" = "$script_version" ] && [ "$java_version" = "$manifest_version" ]; then
    echo "ok: OpenH264.java, build-freerdp-linux.sh and the Flatpak manifest all pin $java_version"
  else
    report error "$java_file" "version pins disagree: OpenH264.java $java_version, $build_script $script_version, $flatpak_manifest $manifest_version"
  fi
  if [ "$script_sha" = "$manifest_sha" ]; then
    echo "ok: the two header tarball hashes agree"
  else
    report error "$flatpak_manifest" "header tarball sha256 differs from $build_script"
  fi

  if [ -z "${OPENH264_CHECK_OFFLINE:-}" ]; then
    # Cisco's binaries are what users run: a hash that does not match them breaks every download.
    for arch in "linux64:$sha_x64" "linux-arm64:$sha_arm"; do
      file="libopenh264-$java_version-${arch%%:*}.8.so.bz2"
      actual="$(curl -fsSL --retry 3 --max-time 120 "$binary_base/$file" | sha256sum | cut -d' ' -f1)" \
        || die "could not download $binary_base/$file"
      if [ "$actual" = "${arch#*:}" ]; then
        echo "ok: $file matches its pinned sha256"
      else
        report error "$java_file" "sha256 of $file is $actual, the pin says ${arch#*:}"
      fi
    done
    actual="$(curl -fsSL --retry 3 --max-time 180 "$manifest_url" | sha256sum | cut -d' ' -f1)" \
      || die "could not download $manifest_url"
    if [ "$actual" = "$manifest_sha" ]; then
      echo "ok: the header tarball matches its pinned sha256"
    else
      report error "$flatpak_manifest" "sha256 of $manifest_url is $actual, the manifest says $manifest_sha"
    fi
  fi
fi

# --- advisories ------------------------------------------------------------------------------

advisories="$(api_get "https://api.github.com/repos/$repo/security-advisories?state=published&per_page=100")" \
  || die "could not read $repo's security advisories (set GITHUB_TOKEN if this is the API rate limit)"

# A version counts as affected when a listed patched version is newer than it, or it falls under an
# upper bound stated in the range ("<= X", "< X", "through X"). Erring towards flagging is the right
# side for a security check; a false alarm is resolved by reading the advisory.
affected_reason() {
  local range="$1" patched="$2" v bound op
  for v in ${patched//,/ }; do
    v="${v#v}"
    if [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && version_lt "$pinned" "$v"; then
      echo "fixed in $v"; return
    fi
  done
  while read -r op bound; do
    [[ "$bound" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    case "$op" in
      "<=" | through) { [ "$pinned" = "$bound" ] || version_lt "$pinned" "$bound"; } && { echo "affects up to $bound"; return; } ;;
      "<") version_lt "$pinned" "$bound" && { echo "affects below $bound"; return; } ;;
    esac
  done < <(grep -oE '(<=|<|through)[[:space:]]*v?[0-9]+\.[0-9]+\.[0-9]+' <<<"$range" \
             | sed -E 's/^(<=|<|through)[[:space:]]*v?/\1 /')
  return 0
}

while IFS=$'\t' read -r ghsa cve severity range patched; do
  reason="$(affected_reason "$range" "$patched")"
  if [ -n "$reason" ]; then
    report error "$java_file" "OpenH264 $pinned is affected by $ghsa ($cve, $severity): $reason"
  fi
done < <(jq -r '.[] | . as $a | (.vulnerabilities // [])[]
                | [$a.ghsa_id, ($a.cve_id // "-"), ($a.severity // "unknown"),
                   (.vulnerable_version_range // ""), (.patched_versions // "")] | @tsv' <<<"$advisories")
echo "ok: checked $(jq length <<<"$advisories") published advisories"

# --- freshness -------------------------------------------------------------------------------

releases="$(api_get "https://api.github.com/repos/$repo/releases?per_page=50")" \
  || die "could not read $repo's releases (set GITHUB_TOKEN if this is the API rate limit)"
# Stable releases newer than the pin, oldest first: "version<TAB>published date". Tags come both as
# "v2.7.0" and "2.5.1".
newer="$(jq -r '.[] | select(.draft | not) | select(.prerelease | not)
                | [(.tag_name | ltrimstr("v")), (.published_at // "" | .[0:10])] | @tsv' <<<"$releases" \
  | awk -F'\t' '$1 ~ /^[0-9]+\.[0-9]+\.[0-9]+$/' \
  | while IFS=$'\t' read -r tag date; do
      if version_lt "$pinned" "$tag"; then printf '%s\t%s\n' "$tag" "$date"; fi
    done | sort -V)"

if [ -z "$newer" ]; then
  echo "ok: OpenH264 $pinned is the latest release"
else
  latest="$(tail -n 1 <<<"$newer" | cut -f1)"
  behind_since="$(head -n 1 <<<"$newer" | cut -f2)"
  [[ "$behind_since" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "unreadable release date '$behind_since'"
  deadline="$(date -u -d "$behind_since +$grace_days days" +%F)"
  today="$(date -u +%F)"
  message="OpenH264 $latest is out (pinned: $pinned, first newer release $behind_since); update the pin in $java_file, $build_script and $flatpak_manifest"
  if [[ "$today" > "$deadline" ]]; then
    report error "$java_file" "$message"
  else
    report warning "$java_file" "$message by $deadline"
  fi
fi

exit "$failed"
