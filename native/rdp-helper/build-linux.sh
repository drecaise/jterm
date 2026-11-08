#!/bin/sh
# Builds jterm-rdp-helper against a FreeRDP 3 install and stages it with FreeRDP's libraries.
# Needs patchelf, besides the toolchain build-freerdp-linux.sh lists.
# Usage: build-linux.sh [FREERDP_DIST]   (default: build/freerdp/dist from build-freerdp-linux.sh)
# Output: build/stage/  (jterm-rdp-helper + libfreerdp3, libfreerdp-client3, libwinpr3; RPATH
#         $ORIGIN, so the directory is self-contained apart from the system's OpenSSL and libc)
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
DIST=${1:-$HERE/build/freerdp/dist}

# A clean stage every time: on a re-run ldd would resolve cJSON to the stage's own copy of it.
rm -rf "$HERE/build/stage"

cmake -S "$HERE" -B "$HERE/build/helper" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$DIST" -DCMAKE_INSTALL_PREFIX="$HERE/build/stage"
cmake --build "$HERE/build/helper"
cmake --install "$HERE/build/helper"

for lib in libfreerdp3 libfreerdp-client3 libwinpr3; do
    cp -P "$DIST"/lib/"$lib".so* "$HERE/build/stage/"
    # FreeRDP forces RPATH $ORIGIN/../lib (an internal CMake cache entry, not overridable). Point
    # each library at its own directory, so every binary in the bundle resolves its siblings by
    # itself — at run time and when jpackage runs ldd on each file to compute package
    # dependencies (which would otherwise resolve cJSON to the system's, i.e. EPEL's, copy).
    for f in "$HERE/build/stage/$lib".so.*.*; do
        [ -L "$f" ] || patchelf --set-rpath '$ORIGIN' "$f"
    done
done
# cJSON ships with the helper too: it is not in every distribution's base repositories (EPEL on
# RHEL), and a package must not depend on an optional repo. Copied under its soname.
cjson=$(ldd "$HERE/build/stage/libwinpr3.so.3" | awk '/libcjson\.so/ {print $3}')
if [ -n "$cjson" ]; then
    cp -L "$cjson" "$HERE/build/stage/$(basename "$cjson")"
fi

# License texts of everything bundled travel with the binaries (FreeRDP: Apache-2.0; cJSON: MIT).
# OpenSSL and zlib come from the system here, so they are not bundled.
mkdir -p "$HERE/build/stage/licenses"
if [ -f "$DIST/FreeRDP-LICENSE.txt" ]; then
    cp "$DIST/FreeRDP-LICENSE.txt" "$HERE/build/stage/licenses/FreeRDP-LICENSE.txt"
else
    cp "$DIST/../src/LICENSE" "$HERE/build/stage/licenses/FreeRDP-LICENSE.txt"
fi
if [ -n "$cjson" ]; then
    for f in /usr/share/licenses/cjson/LICENSE /usr/share/doc/libcjson1/copyright; do
        if [ -f "$f" ]; then
            cp "$f" "$HERE/build/stage/licenses/cjson-LICENSE.txt"
            break
        fi
    done
    [ -f "$HERE/build/stage/licenses/cjson-LICENSE.txt" ] || { echo "cJSON license text not found" >&2; exit 1; }
fi
echo "HELPER OK: $HERE/build/stage/jterm-rdp-helper"
