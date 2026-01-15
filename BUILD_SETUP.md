# Build Setup Instructions

This document explains how to build Twoyi from source, including all necessary dependencies.

## Prerequisites

### Required Tools
1. **Java Development Kit (JDK) 11 or later**
   - Required for Android build
   
2. **Android SDK**
   - API Level 31 or higher
   - Build Tools 30.0.3 or higher

3. **Android NDK**
   - Version r27c or compatible
   - Set `ANDROID_NDK_HOME` environment variable

4. **Rust Toolchain**
   - Stable Rust (1.70 or later recommended)
   - Install from https://rustup.rs/

## Rust Dependencies

### 1. Install Android Target
The Rust code targets ARM64 Android devices:
```bash
rustup target add aarch64-linux-android
```

### 2. Install cargo-xdk
This tool handles cross-compilation for Android:
```bash
cargo install cargo-xdk
```

## Building

### Clean Build
```bash
./gradlew clean
```

### Build Release APK
```bash
export ANDROID_NDK_HOME=/path/to/your/ndk
./gradlew assembleRelease
```

The APK will be located at:
```
app/build/outputs/apk/release/twoyi_<version>-release-unsigned.apk
```

### Verify Build
Check that the APK contains all required native libraries:
```bash
unzip -l app/build/outputs/apk/release/*.apk | grep "lib/arm64-v8a"
```

Should show:
- `lib/arm64-v8a/libadb.so` - ADB functionality
- `lib/arm64-v8a/libloader.so` - Native loader
- `lib/arm64-v8a/libtwoyi.so` - Main Rust library (includes QEMU pipe server)

## Troubleshooting

### "library libtwoyi.so not found"
**Cause**: The Rust library wasn't built or included in the APK.

**Solutions**:
1. Ensure `aarch64-linux-android` target is installed: `rustup target list --installed`
2. Ensure `cargo-xdk` is installed: `cargo xdk --version`
3. Clean and rebuild: `./gradlew clean assembleRelease`
4. Check `app/src/main/jniLibs/arm64-v8a/` for `libtwoyi.so`

### "can't find crate for `core`" or "can't find crate for `std`"
**Cause**: The aarch64-linux-android target is not installed.

**Solution**:
```bash
rustup target add aarch64-linux-android
```

### cargo-xdk not found
**Cause**: cargo-xdk is not installed or not in PATH.

**Solution**:
```bash
cargo install cargo-xdk
# Verify: cargo xdk --version
```

## CI/CD Build

The GitHub Actions workflow (`.github/workflows/build.yml`) automatically:
1. Sets up JDK, Rust, and Android NDK
2. Installs cargo-xdk and Android target
3. Builds the APK
4. Verifies all libraries are included
5. Uploads the APK as an artifact

To trigger a CI build:
- Push to `main` or `develop` branch
- Create a pull request
- Use "Run workflow" on the Actions tab
