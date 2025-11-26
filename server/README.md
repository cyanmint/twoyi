# Twoyi Server

A standalone server for managing twoyi containers. This separates the container management functionality from the Android rendering app.

## Building

```bash
cd server
cargo build --release
```

The binary will be at `target/release/twoyi-server`.

## Usage

```bash
twoyi-server --rootfs <PATH_TO_ROOTFS> [OPTIONS]
```

### Options

- `-r, --rootfs <ROOTFS>` - Path to the container rootfs directory (required)
- `-l, --listen <LISTEN>` - Address and port to listen on (default: `0.0.0.0:9876`)
- `--width <WIDTH>` - Screen width (default: 1080)
- `--height <HEIGHT>` - Screen height (default: 1920)
- `-h, --help` - Print help
- `-V, --version` - Print version

### Example

```bash
# Start server with default settings
twoyi-server --rootfs /path/to/rootfs

# Start server on a specific address and port
twoyi-server --rootfs /path/to/rootfs --listen 192.168.1.100:9876 --width 720 --height 1280
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
