#!/bin/sh
# Builds the FreeRDP version pinned in FREERDP_VERSION (shared libraries, Release) for the RDP
# helper, on Linux. Every target bundles this same FreeRDP; it links the distribution's OpenSSL,
# so build it on (or in a container of) the distribution you package for.
#
# Usage: build-freerdp-linux.sh <workdir>
#   <workdir>/src    FreeRDP checkout (cloned if missing)
#   <workdir>/dist   install prefix: lib/, include/  (the helper builds against this)
#
# Needs: git curl cmake ninja gcc pkg-config, and the OpenSSL, zlib and cJSON development packages
# (Rocky/RHEL: openssl-devel zlib-ng-compat-devel cjson-devel[EPEL]; Debian/Ubuntu: libssl-dev
# zlib1g-dev libcjson-dev).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${1:?usage: build-freerdp-linux.sh <workdir>}
VERSION=$(tr -d '[:space:]' < "$HERE/FREERDP_VERSION")
mkdir -p "$WORK"

if [ ! -d "$WORK/src/.git" ]; then
    git clone --depth 1 --branch "$VERSION" https://github.com/FreeRDP/FreeRDP.git "$WORK/src"
fi

# H.264: FreeRDP loads Cisco's OpenH264 at run time (WITH_OPENH264_LOADING) and ships none. Only the
# BSD-licensed headers are needed here. The library is never built, linked or bundled: Cisco's patent
# license covers only the binary Cisco itself delivers to the user's machine (see
# docs/rdp-sessions.md, "H.264 on Linux"). FreeRDP's configure insists that an OPENH264_LIBRARY file
# exists, so it gets an empty placeholder; nothing is linked against it.
OH264_VERSION=2.6.0
OH264_SHA256=558544ad358283a7ab2930d69a9ceddf913f4a51ee9bf1bfb9e377322af81a69
OH264="$WORK/openh264-$OH264_VERSION"
if [ ! -f "$OH264/include/wels/codec_api.h" ]; then
    mkdir -p "$OH264/include" "$OH264/lib"
    curl -fsSL -o "$OH264/src.tar.gz" "https://github.com/cisco/openh264/archive/refs/tags/v$OH264_VERSION.tar.gz"
    echo "$OH264_SHA256  $OH264/src.tar.gz" | sha256sum -c -
    tar -xzf "$OH264/src.tar.gz" -C "$OH264/include" --strip-components=3 "openh264-$OH264_VERSION/codec/api/wels"
    rm -f "$OH264/src.tar.gz"
    : > "$OH264/lib/libopenh264.so"
fi

# Client libraries only: no stock clients (so no X11/Wayland/SDL), no server, no audio/printing/
# smart-card/USB redirection backends. RPATH $ORIGIN: the libraries are shipped side by side with
# the helper and must find each other there. Built-in Unicode tables instead of ICU and no systemd
# journal logging, so the bundle needs nothing from the system beyond OpenSSL and libc (ICU's
# versioned soname would pin it to one distribution release).
cmake -S "$WORK/src" -B "$WORK/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK/dist" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_INSTALL_RPATH='$ORIGIN' \
    -DBUILD_SHARED_LIBS=ON \
    -DWITH_CLIENT_COMMON=ON -DWITH_CLIENT=OFF -DWITH_CLIENT_SDL=OFF \
    -DWITH_X11=OFF -DWITH_WAYLAND=OFF \
    -DWITH_SERVER=OFF -DWITH_SHADOW=OFF -DWITH_PROXY=OFF -DWITH_SAMPLE=OFF \
    -DWITH_OPENH264=ON -DWITH_OPENH264_LOADING=ON \
    -DOPENH264_INCLUDE_DIR="$OH264/include" -DOPENH264_LIBRARY="$OH264/lib/libopenh264.so" \
    -DWITH_FFMPEG=OFF -DWITH_SWSCALE=OFF -DWITH_WEBVIEW=OFF -DWITH_MANPAGES=OFF \
    -DWITH_PULSE=OFF -DWITH_ALSA=OFF -DWITH_OSS=OFF -DWITH_CUPS=OFF -DWITH_PCSC=OFF \
    -DWITH_FUSE=OFF -DWITH_KRB5=OFF -DWITH_PKCS11=OFF -DWITH_URIPARSER=OFF \
    -DCHANNEL_URBDRC=OFF \
    -DWITH_CJSON_REQUIRED=ON \
    -DWITH_UNICODE_BUILTIN=ON -DWITH_SYSTEMD=OFF -DWITH_WINPR_TOOLS=OFF \
    -DWITH_VERBOSE_WINPR_ASSERT=OFF -DBUILD_TESTING=OFF \
    -DWITH_INTERNAL_RC4=ON -DWITH_INTERNAL_MD4=ON -DWITH_INTERNAL_MD5=ON
cmake --build "$WORK/build"
cmake --install "$WORK/build"
# Keep FreeRDP's license with the install: CI caches dist/ but not the source tree.
cp "$WORK/src/LICENSE" "$WORK/dist/FreeRDP-LICENSE.txt"
echo "FREERDP OK: $VERSION in $WORK/dist"
