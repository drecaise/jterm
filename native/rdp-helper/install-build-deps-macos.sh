#!/bin/sh
# Puts CMake and Ninja on a macOS machine that lacks them, without Homebrew or administrator
# rights: pinned official binaries, checked against SHA-256, unpacked into <toolsdir>. CI runners
# already have both and skip this. The compiler (Xcode Command Line Tools) must be installed first:
# `xcode-select --install`.
#
# Usage: install-build-deps-macos.sh <toolsdir>
# Then:  export PATH="<toolsdir>/bin:$PATH"
set -eu
TOOLS=${1:?usage: install-build-deps-macos.sh <toolsdir>}

xcode-select -p >/dev/null 2>&1 || {
    echo "Xcode Command Line Tools are missing: run 'xcode-select --install' first." >&2
    exit 1
}

CMAKE_VERSION=3.31.12
CMAKE_SHA256=799af7fd545db9bf1b9cfe72f8095880e727a2d4e0df0e3dffc3bc7b95c2d3b0
NINJA_VERSION=1.13.2
NINJA_SHA256=c99048673aa765960a99cf10c6ddb9f1fad506099ff0a0e137ad8960a88f321b

mkdir -p "$TOOLS/bin" "$TOOLS/dl"

fetch() { # fetch <url> <file> <sha256>
    curl -fsSL -o "$2" "$1"
    echo "$3  $2" | shasum -a 256 -c -
}

if [ ! -x "$TOOLS/bin/cmake" ]; then
    fetch "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/cmake-$CMAKE_VERSION-macos-universal.tar.gz" \
        "$TOOLS/dl/cmake.tar.gz" "$CMAKE_SHA256"
    tar -xzf "$TOOLS/dl/cmake.tar.gz" -C "$TOOLS"
    # The .app's bin/ also holds ctest and cpack; link the ones the build uses.
    for t in cmake ctest; do
        ln -sf "$TOOLS/cmake-$CMAKE_VERSION-macos-universal/CMake.app/Contents/bin/$t" "$TOOLS/bin/$t"
    done
fi

if [ ! -x "$TOOLS/bin/ninja" ]; then
    fetch "https://github.com/ninja-build/ninja/releases/download/v$NINJA_VERSION/ninja-mac.zip" \
        "$TOOLS/dl/ninja.zip" "$NINJA_SHA256"
    unzip -o -q "$TOOLS/dl/ninja.zip" -d "$TOOLS/bin"
    chmod +x "$TOOLS/bin/ninja"
fi

rm -rf "$TOOLS/dl"
"$TOOLS/bin/cmake" --version | head -1
"$TOOLS/bin/ninja" --version
echo "TOOLS OK: export PATH=\"$TOOLS/bin:\$PATH\""
