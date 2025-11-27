#!/bin/bash
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#

# Build the server binary for Android arm64 with static linking
# The binary will be completely statically linked using musl or static libc

# Set RUSTFLAGS for static linking
export RUSTFLAGS="-C target-feature=+crt-static"

if ! cargo build --release --target aarch64-linux-android $1; then
    echo "Build failed!"
    exit 1
fi

# Verify the binary is statically linked (or has minimal dynamic deps)
echo "Checking binary dependencies..."
if command -v readelf &> /dev/null; then
    readelf -d target/aarch64-linux-android/release/twoyi-server 2>/dev/null | grep NEEDED || echo "No dynamic dependencies found (fully static)"
fi

# Copy to assets directory
mkdir -p ../app/src/main/assets
cp target/aarch64-linux-android/release/twoyi-server ../app/src/main/assets/twoyi-server || { echo "Copy failed!"; exit 1; }

echo "Build successful! Binary copied to ../app/src/main/assets/twoyi-server"
