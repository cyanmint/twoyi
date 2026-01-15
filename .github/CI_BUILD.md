# CI/CD Build Configuration

This document describes the CI/CD build configuration for the Twoyi project with the **pure Rust** OpenGL renderer implementation.

## Build Requirements

### Required Tools

1. **JDK 11** - Java Development Kit for Android builds
2. **Rust toolchain** - For building the unified Rust native library (libtwoyi.so)
3. **cargo-xdk v2.12.6** - Android cross-compilation tool for Rust
4. **Android NDK r21e** - Native Development Kit for Rust compilation

### Build Dependencies

The build process compiles a single native library:

**libtwoyi.so** - Unified Rust library built with cargo-xdk
   - Includes JNI interface for Android app
   - Includes input system
   - Includes OpenGL renderer (pure Rust implementation)
   - Includes container integration
   - Built via Gradle's `cargoBuild` task
   - Size: ~673KB (optimized)

## Build Process

### 1. Setup Phase

```yaml
- Checkout code
- Setup JDK 11
- Setup Rust toolchain with aarch64-linux-android target
- Install cargo-xdk (cached)
- Setup Android NDK r21e
```

### 2. Build Phase

The build happens in this order:

1. **Gradle Clean** (ensures fresh build)
   - Task: `clean`
   - Removes previous build artifacts

2. **Rust Build** (automatic via Gradle)
   - Task: `cargoBuild`
   - Builds: `libtwoyi.so` with integrated OpenGL renderer
   - Output: `app/src/main/jniLibs/arm64-v8a/`
   - Tool: cargo-xdk

3. **APK Assembly**
   - Task: `assembleRelease`
   - Packages all libraries into APK
   - Output: `app/build/outputs/apk/release/`

### 3. Verification Phase

The CI verifies:
- APK file is created
- Native library is included:
  - libtwoyi.so (unified library with OpenGL renderer)
  - libadb.so (ADB daemon)
  - libloader.so (loader)
- All OpenGL renderer functions are exported from libtwoyi.so:
  - startOpenGLRenderer
  - setNativeWindow
  - resetSubWindow
  - removeSubWindow
  - destroyOpenGLSubwindow
  - repaintOpenGLDisplay

## Local Build

To build locally, ensure you have:

```bash
# Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Install Android target
rustup target add aarch64-linux-android

# Install cargo-xdk
cargo install cargo-xdk --version 2.12.6

# Build
./gradlew clean assembleRelease
```

## Troubleshooting

### cargo-xdk not found

If you see: `error: no such command: 'xdk'`

Solution: Install cargo-xdk
```bash
cargo install cargo-xdk --version 2.12.6
```

### Compilation warnings about unsafe blocks

The pure Rust implementation should not produce unsafe block warnings. If you see:
`error: unnecessary 'unsafe' block`

Solution: The OpenGL renderer functions are now safe Rust functions, not FFI. Remove unnecessary `unsafe` blocks.

### Android target not installed

If you see: `error: the 'aarch64-linux-android' target may not be installed`

Solution: Install the target
```bash
rustup target add aarch64-linux-android
```

## Caching Strategy

The CI caches:
1. **Gradle dependencies** - Via `actions/setup-java` with `cache: 'gradle'`
2. **Rust toolchain** - Via `actions-rust-lang/setup-rust-toolchain` with `cache: true`
3. **cargo-xdk binary** - Via `actions/cache` with specific version key

This reduces build time from ~10 minutes to ~3-4 minutes on cache hit.

## Environment Variables

- `ANDROID_NDK_HOME` - Set to NDK installation path for cargo-xdk

## Build Outputs

Successful build produces:
- APK: `app/build/outputs/apk/release/twoyi_*.apk` (~5.5MB)
- Native libs included in APK:
  - `lib/arm64-v8a/libtwoyi.so` (673KB) - **Unified library with OpenGL renderer**
  - `lib/arm64-v8a/libadb.so` (4.3MB)
  - `lib/arm64-v8a/libloader.so` (50KB)

## Architecture Notes

**Pure Rust Implementation**
- No C/C++ code or compilation required
- All OpenGL renderer functionality implemented in Rust
- Single unified native library
- Simpler build process with fewer dependencies
- Faster compilation (no CMake step)

## References

- [cargo-xdk Documentation](https://github.com/tiann/cargo-xdk)
- [Android NDK Downloads](https://developer.android.com/ndk/downloads)
- [GitHub Actions Rust Setup](https://github.com/actions-rust-lang/setup-rust-toolchain)
