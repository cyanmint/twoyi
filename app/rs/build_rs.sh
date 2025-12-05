#! /bin/bash

#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#

# Build the cdylib (JNI library) for Android
cargo xdk -t arm64-v8a -o ../src/main/jniLibs build $1

# Also build the standalone binary for Termux use
# The binary will be at target/aarch64-linux-android/release/twoyi
cargo xdk -t arm64-v8a build --bin twoyi $1
