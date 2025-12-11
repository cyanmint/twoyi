# Building the FOSS OpenGL Renderer

This document explains how to build `libOpenglRender.so` from source using the FOSS android-emugl implementation from the Ananbox project, instead of using the prebuilt binary.

## Background

The original twoyi project includes a prebuilt `libOpenglRender.so` library that is not built from source. This document provides instructions for building a FOSS (Free and Open Source Software) version of this renderer using the android-emugl library from the Anbox project.

## Prerequisites

- Android NDK r21 or later
- CMake 3.22 or later  
- Boost libraries (1.70 or later)
- Git
- C++14 compatible compiler

## Build Instructions

### 1. Clone the Anbox Repository

The anbox repository is already included as a git submodule:

```bash
git submodule update --init --recursive
```

### 2. Install Dependencies

#### On Ubuntu/Debian:

```bash
sudo apt-get install -y \
    cmake \
    libboost-dev \
    libboost-filesystem-dev \
    libboost-log-dev \
    libboost-serialization-dev \
    libboost-system-dev \
    libboost-thread-dev \
    libboost-program-options-dev \
    libprotobuf-dev \
    protobuf-compiler \
    libsdl2-dev \
    libegl1-mesa-dev \
    libgles2-mesa-dev \
    libglm-dev
```

#### On macOS:

```bash
brew install boost cmake sdl2
```

### 3. Build the Renderer

Use the provided build script:

```bash
cd app/src/main/cpp
./build_foss_renderer.sh
```

This will:
1. Configure CMake for Android cross-compilation
2. Build the android-emugl library from source
3. Create `libOpenglRender.so` in the appropriate jniLibs directory

### 4. Manual Build (Alternative)

If you prefer to build manually:

```bash
cd app/src/main/cpp
mkdir -p build && cd build

# Configure for arm64-v8a
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-27 \
    -DCMAKE_BUILD_TYPE=Release

# Build
cmake --build . --target OpenglRender

# Copy to jniLibs
cp libOpenglRender.so ../../jniLibs/arm64-v8a/
```

## Differences from Prebuilt Version

The FOSS-built renderer:
- Is compiled from open source code (Apache 2.0 licensed)
- Can be customized and debugged
- May have slightly different performance characteristics
- Provides full transparency of the rendering implementation

## Architecture

The FOSS renderer is based on:
- **android-emugl**: The Android Emulator OpenGL ES translation layer from AOSP
- **Anbox modifications**: Optimizations for running Android in containers
- **Native window integration**: Direct rendering to Android SurfaceView

## Troubleshooting

### Build fails with Boost errors

Ensure you have all required Boost components installed:
```bash
sudo apt-get install libboost-all-dev
```

### Symbols not found at runtime

Make sure the library was built for the correct ABI (arm64-v8a) and Android API level (27+).

### Performance issues

The FOSS renderer may require different optimization flags. Edit CMakeLists.txt to adjust:
```cmake
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math")
```

## References

- [Ananbox Project](https://github.com/Ananbox/ananbox) - FOSS Android container
- [Anbox Fork](https://github.com/Ananbox/anbox) - Contains the android-emugl implementation
- [Android Emulator GL](https://android.googlesource.com/platform/external/qemu/) - Original AOSP implementation
- [Anbox Project](https://github.com/anbox/anbox) - Original Anbox project

## License

The android-emugl library is licensed under the Apache License 2.0, which is compatible with the MPL 2.0 license used by twoyi/threetwi.
