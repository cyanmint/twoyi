#! /bin/bash

#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#

# Build a PIE (Position Independent Executable) that works both as:
# 1. A JNI library when loaded by Android app
# 2. An executable when run directly (./libtwoyi.so -r rootfs)
#
# This is achieved using linker flags:
# - PIE: Position Independent Executable (default for Android)
# - -rdynamic: Export all symbols to the dynamic symbol table
# - --export-dynamic: Allow dlopen() to see all symbols

# Set up linker flags for the Android target
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-args=-rdynamic -C link-args=-Wl,--export-dynamic"

# Build the binary
cargo xdk -t arm64-v8a build $1

# Get build mode (release or debug)
if [[ "$1" == "--release" ]] || [[ "$1" == "-r" ]]; then
    BUILD_DIR="release"
else
    BUILD_DIR="debug"
fi

# Rename the binary to libtwoyi.so and copy to jniLibs
mkdir -p ../src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/${BUILD_DIR}/twoyi ../src/main/jniLibs/arm64-v8a/libtwoyi.so

echo "Built libtwoyi.so in ../src/main/jniLibs/arm64-v8a/"
