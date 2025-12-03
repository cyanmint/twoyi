<div align="center">
    <p>
    <h3>
      <b>
        Threetwi Platform
      </b>
    </h3>
  </p>
  <p>
    <b>
      A lightweight Android container - Fork of twoyi (three-body)
    </b>
    <br/>
  </p>
  <p>

[![contributions welcome](https://img.shields.io/badge/Contributions-welcome-brightgreen?logo=github)](CODE_OF_CONDUCT.md)
  </p>
  <p>
    <sub>
      Originally made by
      <a href="https://github.com/tiann">
        weishu
      </a>
      - Forked and maintained by
      <a href="https://github.com/cyanmint">
        cyanmint
      </a>
    </sub>
  </p>
</div>

[README 中文版](README_CN.md)

## About Threetwi

Threetwi is a fork of [twoyi](https://github.com/twoyi/twoyi), a lightweight Android container. The name "threetwi" comes from "three-body" - a reference to the famous science fiction novel.

## Introduction

Threetwi (based on Twoyi) is a lightweight Android container. It runs a nearly complete Android system as a normal app (no root required) on Android. Additionally, it supports Android 8.1 ~ 12.

## Changes from Original Twoyi (since commit d3ce306)

### Version 3.5.7

#### Fixed Profile Switching Container Crashes
- **ServerManager uses profile-specific boot files**: `ensureBootFiles()` now uses the profile's rootfs directory instead of the default path
- **LogEvents uses profile-specific paths**: Bug reports now correctly collect tombstones and dropbox files from the active profile's rootfs directory
- **LogEvents includes profile information**: Boot failure tracking now includes profile name and ID in error reports

### Version 3.5.6

#### Profile-Specific Rootfs Initialization
- **Fixed rootfs initialization for new profiles**: New profiles now automatically initialize their rootfs directories on first start
- **All RomManager methods support profile-specific directories**: Added overloaded methods for `romExist()`, `needsUpgrade()`, `extractRootfs()`, `extractRootfsInAssets()`, `initRootfs()`, `ensureBootFiles()`, `getVendorDir()`, `getVendorPropFile()`, `getRomSdcardDir()`
- **Render2Activity uses profile-specific paths**: Legacy mode now extracts and initializes rootfs in the active profile's directory
- **SettingsActivity uses profile-specific paths**: Both server mode and legacy mode use the active profile's rootfs directory

### Version 3.5.5

#### Server Component (NEW)
- Complete twoyi-server written in Rust for headless container operation
- Scrcpy integration for display rendering
- Fake gralloc device for capturing graphics from legacy ROMs
- Input system for touch and keyboard events

#### Profile Management System
- Added multi-profile support for managing different container configurations
- Each profile can have its own:
  - **Rootfs path**: Custom rootfs directory location (including internal storage paths like `/data/user/0/io.twoyi/*`)
  - **Control port**: Server control connection port
  - **ADB port**: ADB connection port for scrcpy
  - **Container mode**: Legacy (OpenGL) or Server (scrcpy) display mode
  - **Verbose debug**: Per-profile debug logging
  - **3rd party ROM**: Per-profile ROM configuration

#### Native Library Updates
- `Renderer.init()` now accepts a rootfs path parameter
- Input system uses dynamic paths based on profile's rootfs directory
- Removed hardcoded `/data/data/io.twoyi/rootfs` paths

## Files NOT APPLICABLE for Copyright (AI-Generated since d3ce306)

The following files were created by AI (GitHub Copilot) and are NOT APPLICABLE for copyright:

**Server:**
- `server/src/main.rs` - Main server binary
- `server/src/framebuffer.rs` - Framebuffer streaming
- `server/src/gralloc.rs` - Fake gralloc device
- `server/src/input.rs` - Input handling
- `server/Cargo.toml` - Rust dependencies
- `server/build_android.sh` - Build script

**App - Activities:**
- `app/src/main/java/io/twoyi/RemoteRenderActivity.java` - Remote rendering
- `app/src/main/java/io/twoyi/ScrcpyRenderActivity.java` - Scrcpy display
- `app/src/main/java/io/twoyi/ui/ProfileListActivity.java` - Profile list UI
- `app/src/main/java/io/twoyi/ui/ProfileEditActivity.java` - Profile editor UI
- `app/src/main/java/io/twoyi/ui/ServerConsoleActivity.java` - Server console

**App - Utilities:**
- `app/src/main/java/io/twoyi/utils/Profile.java` - Profile data model
- `app/src/main/java/io/twoyi/utils/ProfileManager.java` - Profile CRUD operations
- `app/src/main/java/io/twoyi/utils/ServerManager.java` - Server management
- `app/src/main/java/io/twoyi/utils/ScrcpyClient.java` - Scrcpy client

**App - Resources:**
- `app/src/main/res/layout/activity_profile_list.xml`
- `app/src/main/res/layout/activity_profile_edit.xml`
- `app/src/main/res/layout/item_profile.xml`
- `app/src/main/res/menu/menu_profile_list.xml`
- `app/src/main/res/menu/menu_profile_edit.xml`
- `app/src/main/res/menu/menu_profile_item.xml`

**Scripts:**
- `scripts/build_rom.sh` - ROM building script
- `.github/workflows/android.yml` - CI/CD workflow

**Tests:**
- `app/src/test/java/io/twoyi/utils/ProfileTest.java` - Unit tests

## Capability

1. Use Taichi·Yang without unlocking the bootloader. Xposed, EdXposed and LSPosed will be supported.
2. Use root on non-rooted devices.
3. Use a few Magisk modules.
4. Implement additional system components such as virtual camera by virtualizing the HAL layer.
5. Do security research such as shelling.

## Features

1. Threetwi is a rootless Android system-level container, which runs a nearly complete Android system as a normal app and is mostly isolated from the main system.
2. The internal Android version is Android 8.1 and Android 10 will be supported.
3. Booting up threetwi is very fast (within three seconds) except for the initialization process.
4. Threetwi is an open source project.
5. The internal system of threetwi will be fully customizable. Because its system is open source, you can fork the project to compile your own system. You can also customize the system components, such as the HAL layer to implement virtual cameras, virtual sensors and other special features.
6. **NEW: Profile management** - Create and manage multiple container profiles with different configurations.

## Using ROMs in Custom Paths

The ROM's `init` binary has hardcoded paths that only work at `/data/data/io.twoyi/rootfs`. To use ROMs in custom locations (e.g., when using different profiles or running from Termux), you need to patch the ROM.

### Automatic Patching (via App)

When using the threetwi app with profiles that have custom rootfs paths, the ROM is automatically patched during extraction. The app handles:
- Patching the `init` binary to use the profile's rootfs path
- Setting up the loader symlinks
- Creating required directories

### Manual Patching (via Script)

For manual ROM setup or use outside the app, use the `scripts/prepare_rom.sh` script:

```bash
# Extract the ROM (using tar)
tar -xzf rootfs.tar.gz

# Patch the ROM for a custom path
./scripts/prepare_rom.sh /data/data/com.termux/files/home/rootfs

# Or with custom loader paths
./scripts/prepare_rom.sh \
    --loader /data/data/com.termux/files/home/loader64 \
    /data/data/com.termux/files/home/rootfs
```

**Important Path Length Limits:**
- Rootfs path: max 26 characters (same as `/data/data/io.twoyi/rootfs`)
- Loader paths: max 28 characters

If your paths are longer, create symlinks:
```bash
ln -s /data/data/com.termux/files/home/rootfs_1 /data/ty1
./scripts/prepare_rom.sh /data/ty1
```

## Building

Threetwi contains two parts:

1. The threetwi app, which is actually a UI rendering engine.
2. The internal ROM of threetwi.

This repository contains the threetwi app, and the threetwi ROM is currently being turned into open-source.  Therefore, at this moment, the ROM cannot be compiled from source yet.

### Build the App manually

#### Install Rust

Threetwi is partially written in Rust, so it's nessesary to [install Rust and Cargo](https://www.rust-lang.org/tools/install) first.

#### Install cargo-xdk

Please refer to [cargo-xdk](https://github.com/tiann/cargo-xdk).

You can check if it is installed by running `./gradlew cargoBuild`. If it succeeded, you will see libtwoyi.so in `app/src/main/jniLibs/arm64-v8a`.

PS. Please use ndk v22 or lower, otherwise it may fail.

#### Integrating rootfs

Currently you cannot build the ROM yourself, instead you can use the prebuilt ROM.
To do that, extract rootfs.tar.gz from the official release apk and copy it to `app/src/main/assets`.

### Build the app with Android Studio

Build it with Android Studio normally.

### Build the ROM

WIP

## Discussion

[Telegram Group](https://t.me/twoyi)

## Contact

Original author: twsxtd@gmail.com
