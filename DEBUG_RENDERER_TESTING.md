# Debug Renderer Testing Guide

This document describes how to test the new Debug Renderer feature.

## Prerequisites

- Twoyi app installed on device
- Container set up and working
- Access to device storage (/sdcard)

## Test Steps

### 1. Enable Debug Renderer

1. Open Twoyi app
2. Go to **Settings** (three dots menu)
3. Scroll to **Advanced** section
4. Find **Debug Renderer** checkbox
5. ✅ Verify checkbox is **unchecked** by default
6. Check the **Debug Renderer** checkbox
7. ✅ Verify summary text warns about huge logs
8. Note that reboot is required

### 2. Enable New Renderer (if not already enabled)

The debug renderer works with both old and new renderers, but socket monitoring is most useful with the new renderer.

1. In Settings → Advanced
2. Check **Use New Renderer** if not already checked
3. Reboot container

### 3. Launch Container

1. Go back to main screen
2. Tap **Launch Container**
3. Wait for container to boot
4. ✅ Check logcat for debug messages:
   ```bash
   adb logcat | grep -E "CLIENT_EGL|NEW_RENDERER|SOCKET_MONITOR"
   ```
5. ✅ Expected log entries:
   - `[CORE] Debug renderer set to: true`
   - `[NEW_RENDERER] Debug mode set to: true`
   - `[NEW_RENDERER] Starting socket monitoring for debug mode`
   - `[SOCKET_MONITOR] Starting socket monitoring for debug renderer`

### 4. Verify Log Files Created

1. Connect device to computer or use file manager
2. Navigate to `/sdcard/twoyi_renderer_debug/`
3. ✅ Verify directory exists
4. ✅ Verify log files are being created:
   - `pipe_*_write.log` - QEMU pipe writes
   - `pipe_*_read.log` - QEMU pipe reads (if any)
   - `opengles_commands.log` - GL commands
   - `gralloc_buffers.log` - Buffer operations
   - `gralloc_events.log` - Gralloc events
   - `socket_*.log` - Socket monitoring (multiple files)

### 5. Verify Log Contents

#### Check QEMU Pipe Logs
```bash
adb shell cat /sdcard/twoyi_renderer_debug/pipe__opengles2_write.log | head -50
```
✅ Should contain:
- Timestamps in milliseconds
- "WRITE X bytes" entries
- Hex data: `Hex: [...]`
- ASCII representation: `ASCII: ...`
- Integer values: `i32: [...]`

#### Check OpenGL ES Commands Log
```bash
adb shell cat /sdcard/twoyi_renderer_debug/opengles_commands.log | head -20
```
✅ Should contain:
- Initialize command with width, height, DPI, FPS parameters
- SetWindowSize commands
- Repaint commands
- Timestamps

#### Check Gralloc Logs
```bash
adb shell cat /sdcard/twoyi_renderer_debug/gralloc_buffers.log | head -20
```
✅ Should contain:
- BUFFER_LOCK entries
- Width, height, stride, format values

#### Check Socket Logs
```bash
adb shell ls /sdcard/twoyi_renderer_debug/socket_*.log | wc -l
```
✅ Should show multiple socket log files (those that exist and were connectable)

### 6. Interact with Container

1. Perform actions in the container:
   - Touch screen
   - Launch apps
   - Navigate UI
2. ✅ Verify log files grow in size
3. ✅ Verify new entries are added with updated timestamps

### 7. Disable Debug Renderer

1. Go back to Settings → Advanced
2. Uncheck **Debug Renderer**
3. Reboot container
4. ✅ Verify no new log files are created
5. ✅ Verify existing logs are preserved

### 8. Profile-Specific Testing

1. Create a new profile (Settings → Profile Manager)
2. Switch to new profile
3. ✅ Verify Debug Renderer is OFF by default
4. Enable it for this profile
5. ✅ Verify separate log directory or separate logs per profile

## Expected Results

### When Debug Renderer is OFF (default)
- ✅ No debug log files created
- ✅ No performance impact
- ✅ Normal container operation

### When Debug Renderer is ON
- ✅ Log directory `/sdcard/twoyi_renderer_debug/` created
- ✅ Multiple log files generated
- ✅ Log files contain detailed data
- ✅ Logs grow as container is used
- ✅ May notice slight performance degradation due to file I/O

## Common Issues

### No Log Files Created
- Check if debug mode was actually enabled
- Check logcat for error messages
- Verify `/sdcard` is writable
- Try using new renderer (more comprehensive logging)

### Socket Logs Missing
- Socket monitoring depends on socket availability
- Not all sockets exist in all container configurations
- Some sockets may not accept connections
- This is normal - only connectable sockets are logged

### Log Files Too Large
- This is expected behavior with debug mode
- Log files can grow to hundreds of MB quickly
- Disable debug mode when not needed
- Manually delete old logs: `adb shell rm -rf /sdcard/twoyi_renderer_debug/`

## Performance Notes

Debug mode has significant performance overhead:
- File I/O for every pipe operation
- Thread overhead for socket monitoring
- Disk space consumption

**Use only for debugging, not for normal operation.**

## Cleanup

To remove all debug logs:
```bash
adb shell rm -rf /sdcard/twoyi_renderer_debug/
```

Or use the device file manager to delete the folder.
