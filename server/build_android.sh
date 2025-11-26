#!/bin/bash
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#

# Build the server binary for Android arm64
cargo build --release --target aarch64-linux-android $1

# Check if build succeeded
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi
# Copy to assets directory
mkdir -p ../app/src/main/assets
cp target/aarch64-linux-android/release/twoyi-server ../app/src/main/assets/twoyi-server
