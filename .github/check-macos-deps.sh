#!/usr/bin/env bash
#
# Fail when OpenSSL or cJSON, which the macOS RDP helper builds from pinned sources and bundles,
# is out of date or its pin no longer matches the published tarball.
#
#   .github/check-macos-deps.sh
#
# On Linux both come from the distribution, which patches them; on macOS jterm bundles its own
# (native/rdp-helper/build-freerdp-macos.sh), so a security fix reaches users only with a jterm
# release. The Maven dependency scan can't see either.
#
# Checks, for each library:
#   hash       the pinned sha256 matches the release tarball (skip with MACOS_DEPS_CHECK_OFFLINE=1)
#   freshness  OpenSSL: a newer patch release in the pinned series (3.5.x), which is how OpenSSL
#              ships security fixes; cJSON: any newer release. A warning for $grace_days days
#              after it appeared, then an error.
#
# Needs bash, curl, jq, sha256sum. GITHUB_TOKEN (optional) avoids the API's anonymous rate limit.
# Exit status: 0 fine, 1 something to fix, 2 the check itself could not run -- never a silent pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
script="native/rdp-helper/build-freerdp-macos.sh"
grace_days=14

failed=0

report() { # report <error|warning> <message>
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$script::$2"
  else
    echo "$1: $script: $2"
  fi
  if [ "$1" = error ]; then failed=1; fi
}

die() {
  echo "check-macos-deps: $*" >&2
  exit 2
}

for tool in curl jq sha256sum; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done

version_lt() { # whether version A sorts strictly before version B
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

pin() { # pin <NAME>: the value of NAME=... in the build script
  sed -nE "s/^$1=([^[:space:]]+)$/\1/p" "$root/$script" | head -n 1
}

releases() { # releases <owner/repo>: stable releases as "tag<TAB>published date"
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL --retry 3 --max-time 30 -H "Accept: application/vnd.github+json" "${auth[@]}" \
    "https://api.github.com/repos/$1/releases?per_page=100" \
    | jq -r '.[] | select(.draft | not) | select(.prerelease | not)
             | [.tag_name, (.published_at // "" | .[0:10])] | @tsv'
}

check_hash() { # check_hash <name> <url> <sha256>
  if [ -n "${MACOS_DEPS_CHECK_OFFLINE:-}" ]; then
    echo "skipped: $1 hash (offline)"
    return
  fi
  local actual
  actual="$(curl -fsSL --retry 3 --max-time 120 "$2" | sha256sum | cut -d' ' -f1)" \
    || die "could not download $2"
  if [ "$actual" != "$3" ]; then
    report error "sha256 of $2 is $actual, the script pins $3"
  else
    echo "ok: $1 sha256 matches the release tarball"
  fi
}

# check_fresh <name> <pinned> <newer releases, "version<TAB>date" lines, any order>
check_fresh() {
  local newer latest since deadline message
  newer="$(sort -V <<<"$3" | sed '/^$/d')"
  if [ -z "$newer" ]; then
    echo "ok: $1 $2 is current"
    return
  fi
  latest="$(tail -n 1 <<<"$newer" | cut -f1)"
  since="$(head -n 1 <<<"$newer" | cut -f2)"
  [[ "$since" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "unreadable release date '$since'"
  deadline="$(date -u -d "$since +$grace_days days" +%F)"
  message="$1 $latest is out (pinned: $2, first newer release $since); update the pin and its sha256"
  if [[ "$(date -u +%F)" > "$deadline" ]]; then
    report error "$message"
  else
    report warning "$message by $deadline"
  fi
}

# --- OpenSSL ---------------------------------------------------------------------------------

openssl="${OPENSSL_CHECK_VERSION:-$(pin OPENSSL_VERSION)}"
openssl_sha="$(pin OPENSSL_SHA256)"
[[ "$openssl" =~ ^3\.[0-9]+\.[0-9]+$ ]] || die "no OPENSSL_VERSION 3.x.y in $script: '$openssl'"
[[ "$openssl_sha" =~ ^[0-9a-f]{64}$ ]] || die "no OPENSSL_SHA256 in $script"
series="${openssl%.*}"
echo "pinned: OpenSSL $openssl (series $series)"

[ -n "${OPENSSL_CHECK_VERSION:-}" ] || check_hash OpenSSL \
  "https://github.com/openssl/openssl/releases/download/openssl-$openssl/openssl-$openssl.tar.gz" "$openssl_sha"

list="$(releases openssl/openssl)" || die "could not read OpenSSL's releases (set GITHUB_TOKEN if this is the API rate limit)"
check_fresh OpenSSL "$openssl" "$(while IFS=$'\t' read -r tag date; do
    v="${tag#openssl-}"
    [[ "$v" == "$series".* && "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    if version_lt "$openssl" "$v"; then printf '%s\t%s\n' "$v" "$date"; fi
  done <<<"$list")"

# --- cJSON -----------------------------------------------------------------------------------

cjson="${CJSON_CHECK_VERSION:-$(pin CJSON_VERSION)}"
cjson_sha="$(pin CJSON_SHA256)"
[[ "$cjson" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "no CJSON_VERSION in $script: '$cjson'"
[[ "$cjson_sha" =~ ^[0-9a-f]{64}$ ]] || die "no CJSON_SHA256 in $script"
echo "pinned: cJSON $cjson"

[ -n "${CJSON_CHECK_VERSION:-}" ] || check_hash cJSON \
  "https://github.com/DaveGamble/cJSON/archive/refs/tags/v$cjson.tar.gz" "$cjson_sha"

list="$(releases DaveGamble/cJSON)" || die "could not read cJSON's releases (set GITHUB_TOKEN if this is the API rate limit)"
check_fresh cJSON "$cjson" "$(while IFS=$'\t' read -r tag date; do
    v="${tag#v}"
    [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    if version_lt "$cjson" "$v"; then printf '%s\t%s\n' "$v" "$date"; fi
  done <<<"$list")"

exit "$failed"
