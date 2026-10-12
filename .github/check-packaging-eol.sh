#!/usr/bin/env bash
#
# Fail when the Flatpak runtime or the snap base this repo builds against has
# reached end of life.
#
#   .github/check-packaging-eol.sh
#
# The Maven dependency scan cannot see either: they are not dependencies of the
# jar, they are the userspace the Flatpak and the snap run on (and the source of
# the JRE each one bundles). An end-of-life one stops receiving security fixes
# while every build keeps succeeding -- the only signal is a warning printed on
# the *user's* machine at install time.
#
# Flatpak: Flathub marks a retired ref with an `eol=` option, the same text
#   `flatpak install` prints. Needs `flatpak` and a per-user `flathub` remote
#   (override the name with FLATPAK_REMOTE); the listing is read-only. --user
#   because a name present both system-wide and per-user is ambiguous.
# Snap: the store has no such marker. A coreNN base is Ubuntu NN.04, so the date
#   comes from distro-info-data's ubuntu.csv -- the end of *standard* support,
#   since stage-packages stop getting archive updates then, ESM or not.
#
# Exit status: 0 supported, 1 something is end of life, 2 the check itself could
# not run (unparseable manifest, no data) -- never a silent pass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
flatpak_manifest="packaging/flatpak/com.katmoda.jterm.yml"
snap_manifest="packaging/snap/snapcraft.yaml"
remote="${FLATPAK_REMOTE:-flathub}"
arch="x86_64"
# Warn this long before the snap base's end of standard support.
snap_warn_days=180
ubuntu_csv_url="https://debian.pages.debian.net/distro-info-data/ubuntu.csv"

failed=0

# report <error|warning|notice> <file> <message>: a workflow annotation on
# GitHub, a plain line anywhere else.
report() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::$1 file=$2::$3"
  else
    echo "$1: $2: $3"
  fi
  if [ "$1" = error ]; then failed=1; fi
}

die() {
  echo "check-packaging-eol: $*" >&2
  exit 2
}

# Value of a top-level `key: value` line, minus quotes and trailing comment.
yaml_value() {
  sed -n "s/^$2:[[:space:]]*//p" "$root/$1" | head -n 1 \
    | sed -e 's/[[:space:]]*#.*$//' -e "s/^['\"]//" -e "s/['\"]$//"
}

# --- Flatpak ---------------------------------------------------------------

runtime="$(yaml_value "$flatpak_manifest" runtime)"
branch="$(yaml_value "$flatpak_manifest" runtime-version)"
sdk="$(yaml_value "$flatpak_manifest" sdk)"
[ -n "$runtime" ] && [ -n "$branch" ] && [ -n "$sdk" ] \
  || die "could not read runtime/runtime-version/sdk from $flatpak_manifest"
# An extension follows the SDK's branch unless it names its own (id//branch).
extensions="$(awk '/^sdk-extensions:/ {on=1; next} on && /^[[:space:]]+-/ {print $2; next} on && /^[^[:space:]#]/ {on=0}' \
  "$root/$flatpak_manifest")"

command -v flatpak >/dev/null || die "flatpak is not installed"
# --all is what makes end-of-life refs appear in the listing at all.
listing="$(LC_ALL=C flatpak remote-ls --user --all --runtime --arch="$arch" --columns=ref,options "$remote")" \
  || die "could not list the per-user $remote remote (flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo)"

check_flatpak_ref() {
  local id="${1%%//*}" ref_branch="$branch" line options
  case "$1" in *//*) ref_branch="${1##*//}" ;; esac
  line="$(awk -F'\t' -v ref="runtime/$id/$arch/$ref_branch" '$1 == ref {print; exit}' <<<"$listing")"
  if [ -z "$line" ]; then
    report error "$flatpak_manifest" "$id//$ref_branch is not on $remote any more"
    return
  fi
  options="$(cut -f2- <<<"$line")"
  case "$options" in
    *eol=* | *eol-rebase=*)
      report error "$flatpak_manifest" "$id//$ref_branch is end of life: ${options#*=}"
      ;;
    *)
      echo "ok: $id//$ref_branch is supported"
      ;;
  esac
}

check_flatpak_ref "$runtime"
check_flatpak_ref "$sdk"
for extension in $extensions; do
  check_flatpak_ref "$extension"
done

# A runtime is marked end of life with no notice period, but a newer branch
# appears roughly a year earlier -- say so while there is still time to move.
newer="$(awk -F'\t' -v prefix="runtime/$runtime/$arch/" \
    'index($1, prefix) == 1 && $2 !~ /eol/ {print substr($1, length(prefix) + 1)}' <<<"$listing" \
  | grep -E '^[0-9]+(\.[0-9]+)*$' \
  | { cat; echo "$branch"; } | sort -uV | sed "0,/^$(sed 's/\./\\./g' <<<"$branch")\$/d" | paste -sd' ' -)"
if [ -n "$newer" ]; then
  report notice "$flatpak_manifest" "$runtime $branch has newer supported branches: $newer"
fi

# --- Snap ------------------------------------------------------------------

base="$(yaml_value "$snap_manifest" base)"
case "$base" in
  core) ubuntu="16.04" ;;
  core[0-9][0-9]) ubuntu="${base#core}.04" ;;
  *) die "unrecognised snap base '$base' in $snap_manifest" ;;
esac

# Prefer the live file: the copy an OS image ships is as old as the image.
csv="$(curl -fsSL --retry 3 --max-time 30 "$ubuntu_csv_url" 2>/dev/null || true)"
if ! grep -q "^$ubuntu" <<<"$csv" && [ -r /usr/share/distro-info/ubuntu.csv ]; then
  csv="$(cat /usr/share/distro-info/ubuntu.csv)"
fi
# version,codename,series,created,release,eol,...
eol="$(awk -F, -v v="$ubuntu" '$1 == v || $1 == v " LTS" {print $6; exit}' <<<"$csv")"
[[ "$eol" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
  || die "no end-of-life date for Ubuntu $ubuntu (snap base $base)"

today="$(date -u +%F)"
soon="$(date -u -d "+$snap_warn_days days" +%F)"
# ISO dates order the same as strings.
if [[ ! "$today" < "$eol" ]]; then
  report error "$snap_manifest" "snap base $base (Ubuntu $ubuntu) reached end of standard support on $eol"
elif [[ ! "$soon" < "$eol" ]]; then
  report warning "$snap_manifest" "snap base $base (Ubuntu $ubuntu) reaches end of standard support on $eol"
else
  echo "ok: snap base $base (Ubuntu $ubuntu) is supported until $eol"
fi

exit "$failed"
