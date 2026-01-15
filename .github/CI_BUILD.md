# CI/CD Build Configuration

This document describes the CI/CD build configuration for the Twoyi project with the open-source libOpenglRender implementation.

## Build Requirements

### Required Tools

1. **JDK 11** - Java Development Kit for Android builds
2. **Rust toolchain** - For building the Rust native library (libtwoyi.so)
3. **cargo-xdk v2.12.6** - Android cross-compilation tool for Rust
4. **Android NDK r21e** - Native Development Kit for C++ compilation

### Build Dependencies

The build process requires two native libraries to be compiled:

1. **libOpenglRender.so** - C++ library built with CMake
   - Built via Gradle's `externalNativeBuild`
   - Uses EGL and OpenGL ES 2.0
   - Size: ~51KB (stripped)

2. **libtwoyi.so** - Rust library built with cargo-xdk
   - Links against libOpenglRender.so
   - Built via Gradle's `cargoBuild` task
   - Size: ~644KB

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

1. **CMake Native Build** (automatic via Gradle)
   - Task: `externalNativeBuildRelease`
   - Builds: `libOpenglRender.so`
   - Output: `app/build/intermediates/cmake/release/obj/arm64-v8a/`

2. **Rust Build** (automatic via Gradle)
   - Task: `cargoBuild`
   - Requires: libOpenglRender.so (from step 1)
   - Builds: `libtwoyi.so`
   - Output: `app/src/main/jniLibs/arm64-v8a/`

3. **APK Assembly**
   - Task: `assembleRelease`
   - Packages all libraries into APK
   - Output: `app/build/outputs/apk/release/`

### 3. Verification Phase

The CI verifies:
- APK file is created
- All required native libraries are included:
  - libOpenglRender.so
  - libtwoyi.so
  - libadb.so
  - libloader.so
  - libc++_shared.so

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

### libOpenglRender.so not found during Rust build

If you see: `ld.lld: error: unable to find library -lOpenglRender`

Solution: Ensure CMake build completes first. The Rust build.rs searches for the library in:
- `../build/intermediates/cmake/release/obj/arm64-v8a/`
- `../build/intermediates/cxx/RelWithDebInfo/*/obj/arm64-v8a/`
- `../src/main/jniLibs/arm64-v8a/`

### NDK version mismatch

The project requires NDK r21e (21.4.7075529). Gradle will auto-download if not present.

## Caching Strategy

The CI caches:
1. **Gradle dependencies** - Via `actions/setup-java` with `cache: 'gradle'`
2. **Rust toolchain** - Via `actions-rust-lang/setup-rust-toolchain` with `cache: true`
3. **cargo-xdk binary** - Via `actions/cache` with specific version key

This reduces build time from ~10 minutes to ~2-3 minutes on cache hit.

## Environment Variables

- `ANDROID_NDK_HOME` - Set to NDK installation path
- `RUSTFLAGS` - Set in `build_rs.sh` for PIE executable configuration

## Build Outputs

Successful build produces:
- APK: `app/build/outputs/apk/release/twoyi_*.apk` (~5.5MB)
- Native libs included in APK:
  - `lib/arm64-v8a/libOpenglRender.so` (51KB)
  - `lib/arm64-v8a/libtwoyi.so` (644KB)
  - `lib/arm64-v8a/libadb.so` (4.3MB)
  - `lib/arm64-v8a/libloader.so` (50KB)
  - `lib/arm64-v8a/libc++_shared.so` (912KB)

## References

- [cargo-xdk Documentation](https://github.com/tiann/cargo-xdk)
- [Android NDK Downloads](https://developer.android.com/ndk/downloads)
- [GitHub Actions Rust Setup](https://github.com/actions-rust-lang/setup-rust-toolchain)
