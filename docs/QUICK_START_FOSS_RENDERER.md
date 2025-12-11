# Quick Start: Using the FOSS OpenGL Renderer

This guide helps you quickly get started with building and using the FOSS (Free and Open Source) OpenGL renderer instead of the prebuilt binary.

## ⚠️ Current Status

This is a **foundational framework** for building a FOSS renderer. The infrastructure is ready, but the wrapper code needs to be adapted to the actual anbox API. See [BUILD_FOSS_RENDERER.md](BUILD_FOSS_RENDERER.md) for details.

**For Developers:** This provides the build system and documentation. Additional work is needed to integrate with the anbox/android-emugl API.

## Why Use the FOSS Renderer?

- ✅ **100% Open Source**: Apache 2.0 licensed code from AOSP
- ✅ **Transparent**: Audit and understand the rendering implementation
- ✅ **Customizable**: Modify to meet your specific needs
- ✅ **No "Magic"**: No proprietary or obfuscated code
- ✅ **Community Support**: Backed by AOSP and Anbox projects

## Quick Build (3 Steps)

### 1. Initialize the Submodule

```bash
git submodule update --init --recursive
```

### 2. Run the Build Script

```bash
cd app/src/main/cpp
./build_foss_renderer.sh
```

### 3. Build the App

```bash
cd ../../..
./gradlew assembleRelease
```

That's it! The app will now use the FOSS-built `libOpenglRender.so`.

## What Gets Built?

The build script compiles:
- **android-emugl**: OpenGL ES translation layer from AOSP
- **GLESv1/v2 decoders**: Protocol decoders for OpenGL ES
- **RenderControl**: Render command processor
- **OpenglRender**: Main rendering library

Output: `app/src/main/jniLibs/arm64-v8a/libOpenglRender.so`

## Verify the Build

Check the library was built correctly:

```bash
file app/src/main/jniLibs/arm64-v8a/libOpenglRender.so
ls -lh app/src/main/jniLibs/arm64-v8a/libOpenglRender.so
```

You should see output like:
```
libOpenglRender.so: ELF 64-bit LSB shared object, ARM aarch64...
-rwxr-xr-x 1 user user 1.2M Dec 11 12:00 libOpenglRender.so
```

## Automated Builds with GitHub Actions

You can also use GitHub Actions to build the FOSS renderer:

1. Go to your repository's **Actions** tab
2. Select **Build FOSS Renderer** workflow
3. Click **Run workflow**
4. Download the artifact when complete

## Switching Back to Prebuilt

If you need to switch back to the prebuilt version:

```bash
git checkout app/src/main/jniLibs/arm64-v8a/libOpenglRender.so
```

## Troubleshooting

### "anbox submodule not found"

Run: `git submodule update --init --recursive`

### "ANDROID_NDK not set"

Set the environment variable:
```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/22.1.7171670
```

### Build fails with Boost errors

Install Boost:
```bash
# Ubuntu/Debian
sudo apt-get install libboost-all-dev

# macOS
brew install boost
```

## Need Help?

- 📖 **Full Documentation**: [BUILD_FOSS_RENDERER.md](BUILD_FOSS_RENDERER.md)
- 📄 **License Info**: [RENDERER_SOURCES.md](RENDERER_SOURCES.md)
- 🐛 **Issues**: Open a GitHub issue
- 💬 **Discussion**: [Telegram Group](https://t.me/twoyi)

## Performance Comparison

The FOSS renderer should perform similarly to the prebuilt version. If you notice differences:

1. Build in Release mode: `cmake -DCMAKE_BUILD_TYPE=Release`
2. Check compiler optimizations in CMakeLists.txt
3. Report performance issues on GitHub

## Contributing

Found a bug or have improvements? Contributions welcome!

1. Fork the repository
2. Make your changes
3. Test thoroughly
4. Submit a pull request

## License

The FOSS renderer is based on:
- **android-emugl**: Apache License 2.0 (AOSP)
- **Anbox modifications**: Apache License 2.0
- **This integration**: Mozilla Public License 2.0

All licenses are compatible and permit use, modification, and distribution.
