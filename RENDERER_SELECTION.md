# Renderer Selection Feature

## Overview

This feature allows users to choose between two OpenGL renderer implementations:

1. **New Renderer (Default)** - Open-source Rust implementation built into libtwoyi.so
2. **Legacy Renderer** - Closed-source proprietary renderer (libOpenglRender_legacy.so)

## Architecture

### Components

#### 1. UI Layer (Java)
- **ProfileSettings.java**: Stores renderer preference per profile
  - `RENDERER_TYPE`: Key for storing renderer selection
  - `RENDERER_NEW`: Constant for new renderer ("new")
  - `RENDERER_LEGACY`: Constant for legacy renderer ("legacy")
  - `getRendererType()`: Get current renderer selection
  - `setRendererType()`: Set renderer selection
  - `isLegacyRendererEnabled()`: Check if legacy renderer is selected

- **SettingsActivity.java**: UI for renderer selection
  - Added "Renderer Type" preference in Advanced settings
  - Shows dialog with two options
  - Displays current selection in summary
  - Shows toast notification that reboot is required

- **Renderer.java**: JNI bridge
  - Updated `init()` signature to accept `useLegacyRenderer` boolean parameter

- **Render2Activity.java**: Main renderer activity
  - Passes renderer selection to native code via `ProfileSettings.isLegacyRendererEnabled()`

#### 2. Native Layer (Rust)

- **renderer_loader.rs**: Dynamic renderer loading module
  - `RendererType`: Enum for New/Legacy renderer selection
  - `RendererFunctions`: Struct holding function pointers for active renderer
  - `init_renderer_loader()`: Initialize with selected renderer type
  - `get_renderer_functions()`: Get active renderer function pointers
  - `load_new_renderer()`: Load built-in Rust renderer functions
  - `load_legacy_renderer()`: Dynamically load legacy .so and resolve symbols

- **lib.rs**: Updated JNI entry point
  - `renderer_init()`: Now accepts `use_legacy_renderer` boolean parameter
  - Calls `renderer_loader::init_renderer_loader()` on initialization

- **core.rs**: Renderer core logic
  - Updated to use `renderer_loader::get_renderer_functions()` instead of direct calls
  - All OpenGL calls go through function pointers from renderer_loader

#### 3. Binary Assets

- **libOpenglRender_legacy.so**: Closed-source legacy renderer (1.1 MB)
  - Located in `app/src/main/jniLibs/arm64-v8a/`
  - Loaded dynamically at runtime when legacy renderer is selected
  - Original proprietary implementation extracted from git history

## Usage

### User Workflow

1. Open Settings
2. Navigate to Advanced section
3. Tap "Renderer Type"
4. Select desired renderer:
   - "New Renderer (Open Source)" - Modern Rust implementation
   - "Legacy Renderer (Closed Source)" - Original proprietary renderer
5. Reboot the container for changes to take effect

### Default Behavior

- New installs default to the **New Renderer**
- Existing profiles default to **New Renderer** if no preference is set

## Implementation Details

### Dynamic Loading

The legacy renderer is loaded at runtime using `dlopen()`:

```rust
let lib_path = "/data/data/io.twoyi/lib/libOpenglRender_legacy.so";
let handle = libc::dlopen(lib_path, RTLD_NOW | RTLD_LOCAL);
```

Function symbols are resolved via `dlsym()`:

```rust
let start_fn = libc::dlsym(handle, "startOpenGLRenderer");
```

If loading fails, the system automatically falls back to the new renderer.

### Function Pointer Table

Both renderers expose the same API:

```rust
pub struct RendererFunctions {
    pub start_opengl_renderer: StartOpenGLRendererFn,
    pub set_native_window: SetNativeWindowFn,
    pub reset_sub_window: ResetSubWindowFn,
    pub remove_sub_window: RemoveSubWindowFn,
    pub destroy_opengl_subwindow: DestroyOpenGLSubwindowFn,
    pub repaint_opengl_display: RepaintOpenGLDisplayFn,
}
```

### Thread Safety

- Renderer initialization uses `Once` to ensure single initialization
- Function pointers are protected by `Mutex`
- Multiple threads can safely call renderer functions

## Benefits

### For Users

1. **Choice**: Select the renderer that works best for their device
2. **Compatibility**: Fall back to legacy renderer if new one has issues
3. **Testing**: Compare performance and compatibility between renderers
4. **Flexibility**: Switch renderers without reinstalling the app

### For Developers

1. **Migration Path**: Smooth transition from proprietary to open-source
2. **Debugging**: Ability to compare behavior between implementations
3. **Testing**: Validate new renderer against known-good legacy version
4. **Extensibility**: Easy to add more renderer options in the future

## Comparison

| Feature | New Renderer | Legacy Renderer |
|---------|-------------|-----------------|
| License | Open Source (MPL 2.0) | Proprietary/Closed Source |
| Size | Built into libtwoyi.so | 1.1 MB separate library |
| Source Code | Available | Not Available |
| Maintainability | High | Low |
| Performance | Modern Rust | Optimized C++ |
| Security | Auditable | Black Box |
| Future Updates | Active Development | Frozen |

## Troubleshooting

### Legacy Renderer Fails to Load

**Symptoms**: App logs show "Failed to load legacy renderer", falls back to new renderer

**Causes**:
- libOpenglRender_legacy.so not found in /data/data/io.twoyi/lib/
- Library incompatible with device architecture
- Missing dependencies

**Solutions**:
1. Verify APK includes libOpenglRender_legacy.so
2. Check device is arm64-v8a
3. Switch to new renderer

### Renderer Selection Not Taking Effect

**Symptoms**: Changed renderer but behavior unchanged

**Causes**:
- Container not rebooted after changing setting
- Settings not persisted

**Solutions**:
1. Use "Reboot" from Settings menu
2. Verify ProfileSettings stores the selection
3. Check logs for renderer initialization message

## Future Enhancements

1. **Performance Metrics**: Add telemetry to compare renderer performance
2. **Auto-Selection**: Automatically choose best renderer for device
3. **Renderer Plugins**: Allow third-party renderer implementations
4. **Hot-Swap**: Switch renderers without rebooting (if possible)
5. **Diagnostics**: Built-in renderer compatibility testing tool

## References

- Original implementation: [OPENGLRENDER_IMPLEMENTATION.md](OPENGLRENDER_IMPLEMENTATION.md)
- Legacy renderer source: git tag `original`
- Profile settings: [PROFILE_MANAGER.md](PROFILE_MANAGER.md)

---

**Author**: GitHub Copilot  
**Date**: January 2026  
**License**: Mozilla Public License 2.0
