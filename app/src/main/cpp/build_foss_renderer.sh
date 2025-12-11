#!/bin/bash
# build_foss_renderer.sh
# Script to build libOpenglRender.so from FOSS android-emugl sources

set -e

echo "======================================"
echo "Building FOSS OpenGL Renderer"
echo "======================================"

# Check for Android NDK
if [ -z "$ANDROID_NDK" ]; then
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        # Try to find the latest NDK
        ANDROID_NDK=$(ls -d $HOME/Android/Sdk/ndk/*/ | sort -V | tail -n 1)
        ANDROID_NDK="${ANDROID_NDK%/}"  # Remove trailing slash
        echo "Found Android NDK: $ANDROID_NDK"
    else
        echo "Error: ANDROID_NDK environment variable not set and NDK not found in default location"
        echo "Please set ANDROID_NDK to your NDK installation path"
        exit 1
    fi
fi

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
ANBOX_DIR="$SCRIPT_DIR/anbox"
OUTPUT_DIR="$SCRIPT_DIR/../jniLibs/arm64-v8a"

# Build configuration
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-27"
BUILD_TYPE="Release"

echo "Configuration:"
echo "  Script dir: $SCRIPT_DIR"
echo "  Build dir: $BUILD_DIR"
echo "  Anbox dir: $ANBOX_DIR"
echo "  Output dir: $OUTPUT_DIR"
echo "  Android NDK: $ANDROID_NDK"
echo "  Android ABI: $ANDROID_ABI"
echo "  Platform: $ANDROID_PLATFORM"
echo ""

# Initialize submodules if not already done
if [ ! -d "$ANBOX_DIR/.git" ]; then
    echo "Initializing anbox submodule..."
    cd "$SCRIPT_DIR/../../.."
    git submodule update --init --recursive app/src/main/cpp/anbox
    cd "$SCRIPT_DIR"
fi

# Clean and create build directory
echo "Preparing build directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

cd "$BUILD_DIR"

# Configure with CMake
echo "Configuring CMake..."
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DBUILD_ANANBOX_DEMO=OFF

# Build
echo "Building..."
cmake --build . --target OpenglRender --config "$BUILD_TYPE" -- -j$(nproc --ignore=1 2>/dev/null || echo 4)

# Check if library was built
if [ -f "libOpenglRender.so" ]; then
    echo "Build successful!"
    echo "Copying library to $OUTPUT_DIR..."
    cp libOpenglRender.so "$OUTPUT_DIR/"
    
    # Verify the output
    echo ""
    echo "Library info:"
    ls -lh "$OUTPUT_DIR/libOpenglRender.so"
    file "$OUTPUT_DIR/libOpenglRender.so"
    
    echo ""
    echo "======================================"
    echo "FOSS Renderer build complete!"
    echo "======================================"
    echo "The library has been copied to:"
    echo "  $OUTPUT_DIR/libOpenglRender.so"
    echo ""
    echo "You can now build the app with this FOSS renderer."
else
    echo "Error: Build failed - libOpenglRender.so not found"
    exit 1
fi
