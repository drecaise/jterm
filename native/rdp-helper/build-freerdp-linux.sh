#!/bin/sh
# Builds the FreeRDP version pinned in FREERDP_VERSION (shared libraries, Release) for the RDP
# helper, on Linux. Every target bundles this same FreeRDP; it links the distribution's OpenSSL,
# so build it on (or in a container of) the distribution you package for.
#
# Usage: build-freerdp-linux.sh <workdir>
#   <workdir>/src    FreeRDP checkout (cloned if missing)
#   <workdir>/dist   install prefix: lib/, include/  (the helper builds against this)
#
# Needs: git cmake ninja gcc pkg-config, and the OpenSSL, zlib and cJSON development packages
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
