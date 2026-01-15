# Open Source libOpenglRender Implementation

This directory contains an open-source implementation of the OpenGL rendering library for Twoyi, replacing the previous proprietary binary.

## Overview

The library provides OpenGL ES rendering support for the containerized Android system by handling communication through QEMU pipes (`/opengles`, `/opengles2`, `/opengles3`).

## Architecture

The implementation is based on the [Anbox project](https://github.com/Ananbox/anbox) architecture and consists of:

### Core Components

1. **Renderer (`renderer.cpp`)**
   - Manages EGL context and rendering operations
   - Handles window surface creation and management
   - Provides OpenGL ES 2.0 context

2. **Pipe Connection Handler (`pipe_connection.cpp`)**
   - Listens for QEMU pipe connections from the container
   - Identifies client types (opengles, opengles2, opengles3)
   - Routes OpenGL commands to the renderer

3. **API Layer (`openglrender.cpp`)**
   - Implements the public C API used by the Rust bindings
   - Manages global renderer and connection state
   - Thread-safe access to rendering resources

## API

The library exports the following C API functions:

- `startOpenGLRenderer()` - Initialize and start the renderer
- `setNativeWindow()` - Set the native window for rendering
- `resetSubWindow()` - Reset/resize the rendering window
- `removeSubWindow()` - Remove the rendering window
- `destroyOpenGLSubwindow()` - Cleanup and destroy renderer
- `repaintOpenGLDisplay()` - Trigger a display repaint

## Building

The library is built using CMake and is automatically compiled as part of the Android build process:

```bash
./gradlew assembleRelease
```

To build the native library separately:

```bash
cd app/native/openglrender
mkdir build && cd build
cmake .. -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
         -DANDROID_ABI=arm64-v8a \
         -DANDROID_PLATFORM=android-27 \
         -DCMAKE_BUILD_TYPE=Release
make
```

## Dependencies

- Android NDK (for building)
- EGL library (runtime)
- OpenGL ES 2.0 library (runtime)
- Android log library (runtime)

## Implementation Notes

### QEMU Pipe Communication

The implementation listens for QEMU pipe connections that the container uses to communicate OpenGL commands. The pipe identifier format is:

```
pipe:opengles\0
pipe:opengles2\0
pipe:opengles3\0
```

### Thread Safety

The API implementation uses mutexes to ensure thread-safe access to the global renderer state, as calls may come from multiple threads.

### Current Limitations

This is a foundational implementation. Future enhancements may include:

- Full OpenGL ES command stream processing
- Advanced rendering features
- Performance optimizations
- Support for additional OpenGL ES versions

## References

- [Anbox QEMU Pipe Connection](https://github.com/anbox/anbox/blob/master/src/anbox/qemu/pipe_connection_creator.h)
- [Anbox Graphics](https://github.com/anbox/anbox/tree/master/src/anbox/graphics)

## License

This implementation is provided under the same license as the Twoyi project (Mozilla Public License 2.0).

The AI-generated portions of this code are in the public domain and carry no copyright restrictions.
