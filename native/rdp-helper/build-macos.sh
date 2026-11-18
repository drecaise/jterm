#!/bin/sh
# Builds jterm-rdp-helper on macOS against a FreeRDP 3 install and stages it with its libraries.
# Usage: build-macos.sh [FREERDP_DIST]   (default: build/freerdp/dist from build-freerdp-macos.sh)
# Output: build/stage/  jterm-rdp-helper + libfreerdp3, libfreerdp-client3, libwinpr3, libssl,
#         libcrypto, all loading each other through @rpath = @loader_path, so the directory works
#         wherever it is copied (the installer puts it in the .app). Only libc, zlib and Apple's
#         frameworks come from the system.
#
# Every binary is re-signed ad hoc at the end. Apple Silicon refuses to run an executable or load a
# library without a valid signature, and install_name_tool invalidates the one the linker made:
# skip it and the helper is killed at launch on arm64, while the same steps work on Intel.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
DIST=${1:-$HERE/build/freerdp/dist}
DIST=$(cd "$DIST" && pwd)
ARCH=$(uname -m)
export MACOSX_DEPLOYMENT_TARGET=11.0
STAGE="$HERE/build/stage"

rm -rf "$STAGE"
cmake -S "$HERE" -B "$HERE/build/helper" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$DIST" -DCMAKE_INSTALL_PREFIX="$STAGE" \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOSX_DEPLOYMENT_TARGET"
cmake --build "$HERE/build/helper"
cmake --install "$HERE/build/helper"

for lib in libfreerdp3 libfreerdp-client3 libwinpr3 libssl libcrypto; do
    cp -P "$DIST"/lib/"$lib".*dylib "$STAGE/" 2>/dev/null || cp -P "$DIST"/lib/"$lib"*.dylib "$STAGE/"
done

# Mach-O files in the stage (symlinks excluded: they point at these).
binaries() {
    for f in "$STAGE"/jterm-rdp-helper "$STAGE"/*.dylib; do
        [ -L "$f" ] || [ ! -f "$f" ] || echo "$f"
    done
}
system_path() { # whether a load path is the OS's own (never bundled)
    case "$1" in /usr/lib/* | /System/*) return 0 ;; *) return 1 ;; esac
}

for f in $(binaries); do
    base=$(basename "$f")
    # A library's own name: @rpath/<its current id's file name> (FreeRDP's already is; OpenSSL's
    # is the absolute path of the prefix it was built into).
    case "$base" in
        *.dylib)
            id=$(otool -D "$f" | tail -n +2)
            install_name_tool -id "@rpath/$(basename "$id")" "$f"
            ;;
    esac
    # Every dependency that is not the system's is a sibling in the stage.
    otool -L "$f" | tail -n +2 | awk '{print $1}' | while read -r dep; do
        case "$dep" in @rpath/*) continue ;; esac
        if ! system_path "$dep"; then
            install_name_tool -change "$dep" "@rpath/$(basename "$dep")" "$f"
        fi
    done
    # One rpath, @loader_path; drop any that point into a build tree (e.g. @loader_path/../lib).
    otool -l "$f" | awk '/cmd LC_RPATH/ {getline; getline; print $2}' | while read -r rp; do
        [ "$rp" = "@loader_path" ] || install_name_tool -delete_rpath "$rp" "$f"
    done
    otool -l "$f" | grep -q "path @loader_path " || install_name_tool -add_rpath @loader_path "$f"
done

for f in $(binaries); do
    codesign --force --sign - --timestamp=none "$f"
done

# ---- Verify the bundle is self-contained, for this architecture, loadable on macOS 11, signed.
fail=0
for f in $(binaries); do
    base=$(basename "$f")
    lipo -info "$f" | grep -q "$ARCH" || { echo "not $ARCH: $base" >&2; fail=1; }
    otool -L "$f" | tail -n +2 | awk '{print $1}' | while read -r dep; do
        case "$dep" in
            @rpath/*)
                [ -e "$STAGE/${dep#@rpath/}" ] || { echo "$base needs missing $dep" >&2; exit 1; } ;;
            *)
                system_path "$dep" || { echo "$base loads from outside the bundle: $dep" >&2; exit 1; } ;;
        esac
    done || fail=1
    minos=$(otool -l "$f" | awk '/LC_BUILD_VERSION/ {b=1} b && /minos/ {print $2; exit}')
    [ -z "$minos" ] && minos=$(otool -l "$f" | awk '/LC_VERSION_MIN_MACOSX/ {b=1} b && /version/ {print $2; exit}')
    case "$minos" in
        10.* | 11 | 11.0) ;;
        *) echo "$base requires macOS $minos, newer than $MACOSX_DEPLOYMENT_TARGET" >&2; fail=1 ;;
    esac
    codesign --verify --strict "$f" 2>/dev/null || { echo "invalid signature: $base" >&2; fail=1; }
done
[ "$fail" = 0 ] || { echo "HELPER STAGE FAILED VERIFICATION" >&2; exit 1; }

# License texts of everything bundled travel with the binaries (FreeRDP and OpenSSL: Apache-2.0;
# cJSON, linked in statically: MIT).
mkdir -p "$STAGE/licenses"
for l in FreeRDP OpenSSL cJSON; do
    cp "$DIST/$l-LICENSE.txt" "$STAGE/licenses/$l-LICENSE.txt"
done
echo "HELPER OK: $STAGE/jterm-rdp-helper ($ARCH, macOS $MACOSX_DEPLOYMENT_TARGET+)"
