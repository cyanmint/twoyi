# Twoyi Server

A standalone server for managing twoyi containers. This separates the container management functionality from the Android rendering app.

The server is built as a static binary for aarch64 (ARM64) using musl, so it can run on Termux without glibc.

## Building

### For native platform (development)

```bash
cd server
cargo build --release
```

### For aarch64 (ARM64) static binary

```bash
# Install cross
cargo install cross --git https://github.com/cross-rs/cross

# Build static binary
cd server
cross build --release --target aarch64-unknown-linux-musl
```

The binary will be at `target/aarch64-unknown-linux-musl/release/twoyi-server`.

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
- `-h, --help` - Print help
- `-V, --version` - Print version

### Examples

```bash
# Start server with default settings
twoyi-server --rootfs /path/to/rootfs

# Start server on a specific address and port
twoyi-server --rootfs /path/to/rootfs --listen 192.168.1.100:9876 --width 720 --height 1280

# Extract rootfs from archive and start server
twoyi-server --rootfs /path/to/rootfs --extract-rootfs /path/to/rootfs.7z
```

## Running on Termux

The server binary is statically linked and can run directly on Termux:

```bash
# Copy the binary to Termux
cp twoyi-server /data/data/com.termux/files/home/

# Make it executable
chmod +x twoyi-server

# Extract rootfs and start the server
./twoyi-server --rootfs ./rootfs --extract-rootfs ./rootfs.7z --listen 127.0.0.1:9876
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
