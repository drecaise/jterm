#!/bin/sh
# Builds the FreeRDP version pinned in FREERDP_VERSION (shared libraries, Release) for the RDP
# helper on macOS, for this machine's architecture (arm64 or x86_64). Every target bundles the same
# FreeRDP.
#
# Unlike Linux, nothing comes from the system but libc, zlib and Apple's frameworks: OpenSSL and
# cJSON are built here from pinned sources. Homebrew's libraries are built for the macOS version of
# the machine that built them, so bundling them would make the helper refuse to load on any older
# macOS; everything here targets MACOSX_DEPLOYMENT_TARGET (11.0, what the bundled JRE needs).
#
# Usage: build-freerdp-macos.sh <workdir>
#   <workdir>/src    FreeRDP checkout (cloned if missing)
#   <workdir>/deps   OpenSSL (shared) and cJSON (static), built from source
#   <workdir>/dist   install prefix: lib/, include/  (the helper builds against this)
#
# Needs: Xcode Command Line Tools, git, curl, cmake, ninja (install-build-deps-macos.sh provides the
# last two where they are missing).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${1:?usage: build-freerdp-macos.sh <workdir>}
VERSION=$(tr -d '[:space:]' < "$HERE/FREERDP_VERSION")
ARCH=$(uname -m)
export MACOSX_DEPLOYMENT_TARGET=11.0
JOBS=$(sysctl -n hw.ncpu)
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
DEPS="$WORK/deps"

fetch() { # fetch <url> <file> <sha256>
    curl -fsSL -o "$2" "$1"
    echo "$3  $2" | shasum -a 256 -c -
}

# ---- OpenSSL: shared, so FreeRDP's two libraries share one copy (static would give libwinpr3 and
# libfreerdp3 a libcrypto each). Keep the pin current: it is bundled, so its security fixes ship
# only with a jterm release.
OPENSSL_VERSION=3.5.9
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
if [ ! -f "$DEPS/lib/libssl.3.dylib" ]; then
    case "$ARCH" in
        arm64) target=darwin64-arm64-cc ;;
        x86_64) target=darwin64-x86_64-cc ;;
        *) echo "unsupported architecture: $ARCH" >&2; exit 1 ;;
    esac
    rm -rf "$WORK/openssl-$OPENSSL_VERSION"
    fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
        "$WORK/openssl.tar.gz" "$OPENSSL_SHA256"
    tar -xzf "$WORK/openssl.tar.gz" -C "$WORK"
    rm -f "$WORK/openssl.tar.gz"
    (cd "$WORK/openssl-$OPENSSL_VERSION" &&
        ./Configure "$target" shared no-tests no-docs no-apps --prefix="$DEPS" --libdir=lib \
            "-mmacosx-version-min=$MACOSX_DEPLOYMENT_TARGET" &&
        make -j"$JOBS" >/dev/null &&
        make install_sw >/dev/null)
fi

# ---- cJSON: static, so it disappears into libwinpr3 rather than being one more dylib to bundle.
CJSON_VERSION=1.7.19
CJSON_SHA256=7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562
if [ ! -f "$DEPS/lib/libcjson.a" ]; then
    rm -rf "$WORK/cJSON-$CJSON_VERSION"
    fetch "https://github.com/DaveGamble/cJSON/archive/refs/tags/v$CJSON_VERSION.tar.gz" \
        "$WORK/cjson.tar.gz" "$CJSON_SHA256"
    tar -xzf "$WORK/cjson.tar.gz" -C "$WORK"
    rm -f "$WORK/cjson.tar.gz"
    cmake -S "$WORK/cJSON-$CJSON_VERSION" -B "$WORK/cjson-build" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$DEPS" -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOSX_DEPLOYMENT_TARGET" \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DENABLE_CJSON_TEST=OFF -DENABLE_CJSON_UTILS=OFF -DENABLE_CUSTOM_COMPILER_FLAGS=OFF
    cmake --build "$WORK/cjson-build"
    cmake --install "$WORK/cjson-build"
fi

if [ ! -d "$WORK/src/.git" ]; then
    git clone --depth 1 --branch "$VERSION" https://github.com/FreeRDP/FreeRDP.git "$WORK/src"
fi

# jterm's own changes to FreeRDP (patches/*.patch; each says why it exists). A tree that already has
# one is left alone, so a re-run is harmless; one that no longer applies fails the build here.
for p in "$HERE"/patches/*.patch; do
    if git -C "$WORK/src" apply --reverse --check "$p" 2>/dev/null; then
        continue
    fi
    git -C "$WORK/src" apply "$p"
done

# The same client-only option set as Linux (see build-freerdp-linux.sh; Apple's toolchain already
# gives PIE, and _FORTIFY_SOURCE=2 through the SDK headers, so only the stack protector is added),
# plus: H.264 through Apple's
# VideoToolbox (no codec to download or bundle on macOS), no macOS audio backend (audio is not
# redirected), no native Mac client. Install names are @rpath, and @loader_path finds the siblings.
cmake -S "$WORK/src" -B "$WORK/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK/dist" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$DEPS" -DOPENSSL_ROOT_DIR="$DEPS" \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOSX_DEPLOYMENT_TARGET" \
    -DCMAKE_INSTALL_NAME_DIR=@rpath -DCMAKE_INSTALL_RPATH=@loader_path \
    -DCMAKE_C_FLAGS="-fstack-protector-strong" -DCMAKE_CXX_FLAGS="-fstack-protector-strong" \
    -DBUILD_SHARED_LIBS=ON \
    -DWITH_CLIENT_COMMON=ON -DWITH_CLIENT=OFF -DWITH_CLIENT_SDL=OFF -DWITH_CLIENT_MAC=OFF \
    -DWITH_X11=OFF -DWITH_WAYLAND=OFF \
    -DWITH_SERVER=OFF -DWITH_SHADOW=OFF -DWITH_PROXY=OFF -DWITH_SAMPLE=OFF \
    -DWITH_APPLE_VIDEOTOOLBOX=ON -DWITH_OPENH264=OFF \
    -DWITH_FFMPEG=OFF -DWITH_SWSCALE=OFF -DWITH_WEBVIEW=OFF -DWITH_MANPAGES=OFF \
    -DWITH_MACAUDIO=OFF -DWITH_CUPS=OFF -DWITH_PCSC=OFF \
    -DWITH_FUSE=OFF -DWITH_KRB5=OFF -DWITH_PKCS11=OFF -DWITH_URIPARSER=OFF \
    -DCHANNEL_URBDRC=OFF \
    -DWITH_CJSON_REQUIRED=ON \
    -DWITH_UNICODE_BUILTIN=ON -DWITH_WINPR_TOOLS=OFF \
    -DWITH_VERBOSE_WINPR_ASSERT=OFF -DBUILD_TESTING=OFF \
    -DWITH_INTERNAL_RC4=ON -DWITH_INTERNAL_MD4=ON -DWITH_INTERNAL_MD5=ON
cmake --build "$WORK/build"
cmake --install "$WORK/build"
# The patch that removes the system-wide certificates.json must have taken: that file can switch
# certificate checking off (see patches/0001-*.patch), and this string exists only in its parser.
if grep -q "deny-userconfig" "$WORK"/dist/lib/libfreerdp3*.dylib; then
    echo "FreeRDP still reads certificates.json: the certificate-config patch did not take" >&2
    exit 1
fi
# Keep the licenses with the install: CI caches dist/ but not the source trees.
cp "$WORK/src/LICENSE" "$WORK/dist/FreeRDP-LICENSE.txt"
cp "$WORK/openssl-$OPENSSL_VERSION/LICENSE.txt" "$WORK/dist/OpenSSL-LICENSE.txt" 2>/dev/null ||
    curl -fsSL -o "$WORK/dist/OpenSSL-LICENSE.txt" "https://raw.githubusercontent.com/openssl/openssl/openssl-$OPENSSL_VERSION/LICENSE.txt"
cp "$WORK/cJSON-$CJSON_VERSION/LICENSE" "$WORK/dist/cJSON-LICENSE.txt" 2>/dev/null ||
    curl -fsSL -o "$WORK/dist/cJSON-LICENSE.txt" "https://raw.githubusercontent.com/DaveGamble/cJSON/v$CJSON_VERSION/LICENSE"
# The OpenSSL dylibs live in deps/; the stage step takes them from dist/ like FreeRDP's own.
cp -P "$DEPS"/lib/libssl*.dylib "$DEPS"/lib/libcrypto*.dylib "$WORK/dist/lib/"
echo "FREERDP OK: $VERSION ($ARCH, macOS $MACOSX_DEPLOYMENT_TARGET+) in $WORK/dist"
