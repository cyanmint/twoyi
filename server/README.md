# Twoyi Server

A standalone server for managing twoyi containers. This separates the container management functionality from the Android rendering app.

The server is built for aarch64 Android (`aarch64-linux-android`), compatible with Android/Termux environments.

## Building

### For native platform (development)

```bash
cd server
cargo build --release
```

### For aarch64 Android

Requires Android NDK. Set up environment variables first:

```bash
export ANDROID_NDK_HOME=/path/to/ndk
export CC_aarch64_linux_android=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
export AR_aarch64_linux_android=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang

cd server
cargo build --release --target aarch64-linux-android
```

The binary will be at `target/aarch64-linux-android/release/twoyi-server`.

## Usage

```bash
twoyi-server --rootfs <PATH_TO_ROOTFS> [OPTIONS]
```

### Options

- `-r, --rootfs <ROOTFS>` - Path to the container rootfs directory (required)
- `-l, --listen <LISTEN>` - Address and port to listen on (default: `0.0.0.0:9876`)
- `--width <WIDTH>` - Screen width (default: 1080)
- `--height <HEIGHT>` - Screen height (default: 1920)
- `--extract-rootfs <PATH>` - Extract rootfs from a .7z archive before starting
- `--loader <PATH>` - Path to libloader.so (required for container to communicate with host)
- `-h, --help` - Print help
- `-V, --version` - Print version

### Examples

```bash
# Start server with loader library
twoyi-server --rootfs /path/to/rootfs --loader /path/to/libloader.so

# Start server on a specific address and port
twoyi-server --rootfs /path/to/rootfs --loader /path/to/libloader.so --listen 192.168.1.100:9876 --width 720 --height 1280

# Extract rootfs from archive and start server
twoyi-server --rootfs /path/to/rootfs --loader /path/to/libloader.so --extract-rootfs /path/to/rootfs.7z
```

## Running on Termux

The server binary is built for Android and can run directly on Termux:

```bash
# Copy the binary and loader to Termux
cp twoyi-server /data/data/com.termux/files/home/
cp libloader.so /data/data/com.termux/files/home/

# Make them executable
chmod +x twoyi-server

# Extract rootfs and start the server
./twoyi-server --rootfs ./rootfs --loader ./libloader.so --extract-rootfs ./rootfs.7z --listen 127.0.0.1:9876
```

Note: The `libloader.so` library is required for the container to work properly. You can extract it from the twoyi APK:

```bash
unzip twoyi.apk -d apk_contents
cp apk_contents/lib/arm64-v8a/libloader.so ./
```

## Protocol

The server uses a JSON-based protocol over TCP. Each message is a single line of JSON.

### Client Messages

#### StartContainer
Starts the container in the rootfs directory.
```json
{"type":"StartContainer"}
```

#### GetStatus
Gets the current server status.
```json
{"type":"GetStatus"}
```

#### TouchEvent
Sends a touch event.
```json
{"type":"TouchEvent","action":0,"x":100.0,"y":200.0,"pointer_id":0,"pressure":1.0}
```
- `action`: 0=DOWN, 1=UP, 2=MOVE, 3=CANCEL/POINTER_UP
- `x`, `y`: Touch coordinates
- `pointer_id`: Pointer ID for multi-touch
- `pressure`: Touch pressure (0.0 to 1.0)

#### KeyEvent
Sends a key event.
```json
{"type":"KeyEvent","keycode":3,"pressed":true}
```
- `keycode`: Android keycode
- `pressed`: true for key down, false for key up

#### Ping
Ping for connection keep-alive.
```json
{"type":"Ping"}
```

### Server Responses

#### ContainerStarted
Container started successfully.
```json
{"type":"ContainerStarted"}
```

#### Status
Current server status.
```json
{"type":"Status","container_running":true,"rootfs_path":"/path/to/rootfs","width":1080,"height":1920}
```

#### Ok
Generic success response.
```json
{"type":"Ok"}
```

#### Pong
Response to Ping.
```json
{"type":"Pong"}
```

#### Error
Error response.
```json
{"type":"Error","message":"Error description"}
```

## Getting a Rootfs

You can get a rootfs by downloading the official twoyi release APK and extracting it:

```bash
# Download APK
wget https://github.com/twoyi/twoyi/releases/download/0.5.4/twoyi_0.5.4-03211927-release.apk -O twoyi.apk

# Unpack APK
unzip twoyi.apk -d apk_contents

# Extract rootfs
7z x apk_contents/assets/rootfs.7z -o./

# Now you can use ./rootfs as the rootfs directory
twoyi-server --rootfs ./rootfs
```

Or use the built-in extraction feature:
```bash
# Server will extract rootfs.7z automatically
twoyi-server --rootfs ./rootfs --extract-rootfs apk_contents/assets/rootfs.7z
```
