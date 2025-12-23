# Copyright Disclaimer: AI-Generated Content
# This file was created by GitHub Copilot, an AI coding assistant.
# AI-generated content is not subject to copyright protection and is provided
# without any warranty, express or implied, including warranties of merchantability,
# fitness for a particular purpose, or non-infringement.
# Use at your own risk.

# Open Source OpenGL Renderer

This directory contains the open source implementation of the OpenGL rendering library for Twoyi, replacing the original prebuilt closed-source `libOpenglRender.so`.

## Overview

The OpenGL renderer provides a bridge between the Android container and the native Android graphics subsystem. It manages EGL contexts, surfaces, and framebuffer operations to render the container's display.

## Architecture

The implementation consists of:

1. **OpenglRender.cpp** - Main implementation with EGL/OpenGL ES 3.0 rendering
2. **OpenglRender.h** - C API header matching the original library interface
3. **CMakeLists.txt** - Build configuration for the native library

## Key Components

### Global Renderer State
- EGL display, surface, and context management
- Window surface handling via ANativeWindow
- Framebuffer objects (FBO) for off-screen rendering
- Dedicated render thread for continuous display updates

### API Functions

The library exports the following C functions required by the Rust bindings:

- `startOpenGLRenderer()` - Initialize the renderer with window and display parameters
- `setNativeWindow()` - Update the native window for rendering
- `resetSubWindow()` - Reconfigure window dimensions and framebuffer size
- `repaintOpenGLDisplay()` - Request a display repaint
- `destroyOpenGLSubwindow()` - Cleanup and shutdown the renderer
- `removeSubWindow()` - Remove a specific window surface

## Implementation Details

### EGL Initialization
- Uses EGL 1.x with OpenGL ES 3.0 context
- Configures RGBA8888 color buffer with 16-bit depth
- Creates window surface from ANativeWindow

### Rendering Loop
- Dedicated pthread for rendering operations
- FPS-based frame timing (configurable)
- Double-buffered swapchain via eglSwapBuffers
- Mutex-protected state for thread safety

### Framebuffer Management
- Off-screen FBO for flexible rendering
- Texture color attachment (RGBA)
- Renderbuffer depth attachment (16-bit)
- Dynamic resize support

## Building

The library is automatically built by Gradle when building the app:

```bash
./gradlew :app:externalNativeBuildDebug
```

The compiled library is output to:
```
app/src/main/jniLibs/arm64-v8a/libOpenglRender.so
```

### Build Requirements
- Android NDK 21.4.7075529 or later
- CMake 3.4.1 or later
- C++17 compiler support

## Differences from Original

The original `libOpenglRender.so` was a modified version from the Android Emulator with extensive additional features:

- GLES1/GLES2 protocol decoders
- Socket-based client-server architecture  
- Full FrameBuffer class implementation
- RenderControl protocol support

This open source version provides a simplified implementation focusing on:

- Direct EGL/GLES rendering (no protocol layer)
- Native window surface management
- Basic framebuffer operations
- Thread-safe state management

## Future Enhancements

Potential improvements for better compatibility:

1. Add GLES command stream decoding
2. Implement socket-based communication layer
3. Add color buffer management API
4. Implement texture/renderbuffer sharing
5. Add support for multiple concurrent windows
6. Implement proper texture blitting for FBO-to-screen rendering

## Testing

To verify the library:

1. Build the app with the new library
2. Launch a container in Twoyi
3. Check logcat for OpenGL renderer messages:
   ```bash
   adb logcat | grep OpenglRender
   ```

## License

This implementation is AI-generated content and is not subject to copyright protection. Use at your own risk without any warranties.

## References

- [Android NDK EGL Reference](https://developer.android.com/ndk/reference/group/native-activity)
- [Android Emulator OpenGL](https://android.googlesource.com/platform/external/qemu/+/master/android/android-emugl/)
- [EGL 1.5 Specification](https://www.khronos.org/registry/EGL/specs/eglspec.1.5.pdf)
