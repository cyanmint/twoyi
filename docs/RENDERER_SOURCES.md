# OpenGL Renderer Sources and Licensing

## Current State

The repository includes a prebuilt `libOpenglRender.so` binary in `app/src/main/jniLibs/arm64-v8a/`. This file is provided for convenience but its source provenance is unclear.

## FOSS Alternative

A **Free and Open Source Software (FOSS)** version of the OpenGL renderer can now be built from source using the android-emugl implementation from the Ananbox project.

### Source Repository

The FOSS renderer is built from: [Ananbox/anbox](https://github.com/Ananbox/anbox)

This repository contains:
- **android-emugl**: The Android Emulator OpenGL ES translation layer
- **Source**: Originally from Android Open Source Project (AOSP)
  - Repository: https://android.googlesource.com/platform/external/qemu/
  - Branch: emu-2.0-release
  - Commit: 9a21e8c61517ca9aa8fc244810fea96b361e383c

### License

The android-emugl library and all associated code is licensed under:
- **Apache License, Version 2.0**
- Compatible with Mozilla Public License 2.0 (used by twoyi/threetwi)
- Full license text: http://www.apache.org/licenses/LICENSE-2.0

### Key Components

1. **GLESv1_dec**: OpenGL ES 1.x decoder
2. **GLESv2_dec**: OpenGL ES 2.x/3.x decoder  
3. **renderControl_dec**: Render control protocol decoder
4. **OpenglRender**: Main rendering library
5. **Translators**: GLES to desktop GL translation layers

### Why FOSS?

Building from FOSS sources provides:
- **Transparency**: Complete visibility into the rendering implementation
- **Security**: Ability to audit the code for vulnerabilities
- **Customization**: Freedom to modify and optimize for specific needs
- **Licensing clarity**: Clear Apache 2.0 license terms
- **Community support**: Backed by AOSP and Anbox communities

### Building the FOSS Renderer

See [BUILD_FOSS_RENDERER.md](BUILD_FOSS_RENDERER.md) for complete build instructions.

Quick start:
```bash
cd app/src/main/cpp
./build_foss_renderer.sh
```

## References

- [Ananbox Project](https://github.com/Ananbox/ananbox) - FOSS Android container on Android
- [Anbox Source](https://github.com/Ananbox/anbox) - Fork with android-emugl
- [AOSP Emulator](https://android.googlesource.com/platform/external/qemu/) - Original source
- [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## Migration Path

For users wanting to move to the FOSS renderer:

1. **Build from source** using `build_foss_renderer.sh`
2. **Test thoroughly** to ensure compatibility with your ROM
3. **Report issues** if you encounter any problems
4. **Contribute back** improvements to the community

The FOSS renderer aims to be a drop-in replacement for the prebuilt version, maintaining the same API and functionality.
