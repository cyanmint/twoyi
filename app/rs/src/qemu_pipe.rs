// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! QEMU Pipe Implementation
//! 
//! Provides a userspace implementation of the goldfish pipe device
//! that gralloc.goldfish.so expects. This allows the containerized
//! Android system to work without actual QEMU emulation.

use log::{debug, info, warn, error};
use std::io::{Read, Write};
use std::sync::Mutex;
use std::thread;
use std::collections::HashMap;
use once_cell::sync::Lazy;

const PIPE_PATH: &str = "/data/data/io.twoyi/rootfs/dev/qemu_pipe";
const GOLDFISH_PIPE_PATH: &str = "/data/data/io.twoyi/rootfs/dev/goldfish_pipe";

// OpenGL service paths (Unix sockets in rootfs)
const OPENGLES_PATH: &str = "/data/data/io.twoyi/rootfs/opengles";
const OPENGLES2_PATH: &str = "/data/data/io.twoyi/rootfs/opengles2";
const OPENGLES3_PATH: &str = "/data/data/io.twoyi/rootfs/opengles3";

// Protocol constants
const MAX_SERVICE_NAME_LENGTH: usize = 256;
const COMMAND_BUFFER_SIZE: usize = 8192;
const DEFAULT_FB_WIDTH: u32 = 1920;
const DEFAULT_FB_HEIGHT: u32 = 1080;

// QEMU pipe service names (WITHOUT leading slash - the pipe protocol expects plain names)
const OPENGLES_SERVICE: &str = "opengles";
const OPENGLES2_SERVICE: &str = "opengles2"; 
const OPENGLES3_SERVICE: &str = "opengles3";
const PIPE_SERVICE: &str = "pipe";

// OpenGL ES renderControl command opcodes (from goldfish-opengl)
const OP_rcGetRendererVersion: u32 = 10000;
const OP_rcGetEGLVersion: u32 = 10001;
const OP_rcQueryEGLString: u32 = 10002;
const OP_rcGetGLString: u32 = 10003;
const OP_rcGetNumConfigs: u32 = 10004;
const OP_rcGetConfigs: u32 = 10005;
const OP_rcChooseConfig: u32 = 10006;
const OP_rcGetFBParam: u32 = 10007;
const OP_rcCreateContext: u32 = 10008;
const OP_rcDestroyContext: u32 = 10009;
const OP_rcCreateWindowSurface: u32 = 10010;
const OP_rcDestroyWindowSurface: u32 = 10011;
const OP_rcCreateColorBuffer: u32 = 10012;
const OP_rcOpenColorBuffer: u32 = 10013;
const OP_rcCloseColorBuffer: u32 = 10014;
const OP_rcSetWindowColorBuffer: u32 = 10015;
const OP_rcFlushWindowColorBuffer: u32 = 10016;
const OP_rcMakeCurrent: u32 = 10017;
const OP_rcFBPost: u32 = 10018;
const OP_rcFBSetSwapInterval: u32 = 10019;
const OP_rcBindTexture: u32 = 10020;
const OP_rcBindRenderbuffer: u32 = 10021;

/// Color buffer information
#[derive(Debug, Clone)]
struct ColorBuffer {
    id: u32,
    width: u32,
    height: u32,
    format: u32,
}

static COLOR_BUFFERS: Lazy<Mutex<HashMap<u32, ColorBuffer>>> = Lazy::new(|| {
    Mutex::new(HashMap::new())
});

static NEXT_BUFFER_ID: Lazy<Mutex<u32>> = Lazy::new(|| Mutex::new(1));

/// Start the QEMU pipe server
/// This creates Unix sockets for OpenGL ES services that gralloc expects
pub fn start_qemu_pipe_server() {
    // Start listeners for the OpenGL service sockets
    // These are what gralloc.goldfish.so actually connects to
    start_pipe_listener(OPENGLES_PATH);
    start_pipe_listener(OPENGLES2_PATH);
    start_pipe_listener(OPENGLES3_PATH);
    
    // Also create traditional pipe devices for compatibility
    start_pipe_listener(PIPE_PATH);
    start_pipe_listener(GOLDFISH_PIPE_PATH);
}

fn start_pipe_listener(path: &'static str) {
    thread::spawn(move || {
        info!("Starting pipe server at {}", path);
        
        // Remove existing socket if it exists
        let _ = std::fs::remove_file(path);
        
        // Create the Unix socket
        let listener = match unix_socket::UnixListener::bind(path) {
            Ok(l) => l,
            Err(e) => {
                error!("Failed to bind pipe socket at {}: {}", path, e);
                return;
            }
        };
        
        info!("Pipe server listening at {}", path);
        
        // Determine if this is a direct OpenGL service path or a generic pipe
        let is_direct_opengl = path.contains("opengles");
        
        for stream in listener.incoming() {
            match stream {
                Ok(mut stream) => {
                    debug!("Pipe client connected to {}", path);
                    
                    // Spawn a thread to handle this client
                    let is_gl = is_direct_opengl;
                    thread::spawn(move || {
                        if is_gl {
                            // Direct OpenGL service - no service name negotiation needed
                            handle_opengl_service_direct(&mut stream);
                        } else {
                            // Generic pipe - need to read service name first
                            handle_pipe_client(&mut stream);
                        }
                    });
                }
                Err(e) => {
                    warn!("Pipe accept error at {}: {}", path, e);
                    break;
                }
            }
        }
        
        info!("Pipe server stopped at {}", path);
    });
}

/// Handle a single pipe client connection
fn handle_pipe_client(stream: &mut unix_socket::UnixStream) {
    // Read the service name (null-terminated string)
    let mut service_name = Vec::new();
    let mut buf = [0u8; 1];
    
    loop {
        match stream.read(&mut buf) {
            Ok(0) => {
                debug!("Client disconnected during service name read");
                return;
            }
            Ok(_) => {
                if buf[0] == 0 {
                    // Null terminator found
                    break;
                }
                service_name.push(buf[0]);
            }
            Err(e) => {
                warn!("Error reading service name: {}", e);
                return;
            }
        }
        
        // Prevent infinite read
        if service_name.len() > MAX_SERVICE_NAME_LENGTH {
            warn!("Service name too long, disconnecting client");
            return;
        }
    }
    
    let service = match String::from_utf8(service_name) {
        Ok(s) => s,
        Err(_) => {
            warn!("Invalid UTF-8 in service name");
            return;
        }
    };
    
    info!("Client requesting service: '{}'", service);
    
    // Handle different services
    // Service names can be:
    // - "opengles", "opengles2", "opengles3" for OpenGL ES
    // - "pipe:opengles", "pipe:opengles2", "pipe:opengles3" (alternative format)
    let service_name = if service.starts_with("pipe:") {
        &service[5..] // Strip "pipe:" prefix
    } else {
        &service
    };
    
    if service_name == OPENGLES_SERVICE || 
       service_name == OPENGLES2_SERVICE || 
       service_name == OPENGLES3_SERVICE {
        handle_opengl_service(stream, service_name);
    } else {
        warn!("Unknown service requested: {}", service);
        // Send error response
        let _ = stream.write_all(&[0xff, 0xff, 0xff, 0xff]);
    }
}

/// Handle OpenGL ES service requests for direct connections (no service name exchange)
fn handle_opengl_service_direct(stream: &mut unix_socket::UnixStream) {
    debug!("Handling direct OpenGL service connection");
    
    // For direct connections, we don't send an initial success response
    // Just start processing commands immediately
    handle_opengl_commands(stream);
}

/// Handle OpenGL ES service requests (after service name negotiation)
fn handle_opengl_service(stream: &mut unix_socket::UnixStream, service: &str) {
    debug!("Handling OpenGL service: {}", service);
    
    // Send success response (0x00000000) to indicate service is available
    if let Err(e) = stream.write_all(&[0x00, 0x00, 0x00, 0x00]) {
        warn!("Failed to send success response: {}", e);
        return;
    }
    
    handle_opengl_commands(stream);
}

/// Process OpenGL command stream
fn handle_opengl_commands(stream: &mut unix_socket::UnixStream) {
    // Now handle OpenGL command stream
    let mut buffer = vec![0u8; COMMAND_BUFFER_SIZE];
    
    loop {
        match stream.read(&mut buffer) {
            Ok(0) => {
                debug!("OpenGL service client disconnected");
                break;
            }
            Ok(n) => {
                debug!("Received {} bytes from OpenGL service", n);
                
                // Process the command and send response
                let response = process_opengl_command(&buffer[..n]);
                
                if let Err(e) = stream.write_all(&response) {
                    warn!("Failed to send response: {}", e);
                    break;
                }
            }
            Err(e) => {
                warn!("Error reading from OpenGL service: {}", e);
                break;
            }
        }
    }
}

/// Process OpenGL command data and return response
fn process_opengl_command(data: &[u8]) -> Vec<u8> {
    if data.len() < 4 {
        debug!("Command too short: {} bytes", data.len());
        return vec![0x00, 0x00, 0x00, 0x00]; // Error response
    }
    
    let opcode = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    debug!("Processing OpenGL opcode: 0x{:08x}", opcode);
    
    match opcode {
        OP_rcGetRendererVersion => {
            // Return a fake renderer version
            info!("rcGetRendererVersion");
            let version: u32 = 1; // Version 1
            version.to_le_bytes().to_vec()
        }
        
        OP_rcGetEGLVersion => {
            info!("rcGetEGLVersion");
            // Return EGL 1.4
            let major: u32 = 1;
            let minor: u32 = 4;
            let mut response = Vec::new();
            response.extend_from_slice(&major.to_le_bytes());
            response.extend_from_slice(&minor.to_le_bytes());
            response
        }
        
        OP_rcGetNumConfigs => {
            info!("rcGetNumConfigs");
            // Return number of configs
            let num_configs: u32 = 1;
            num_configs.to_le_bytes().to_vec()
        }
        
        OP_rcGetFBParam => {
            if data.len() >= 8 {
                let param = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                info!("rcGetFBParam: param={}", param);
                // Return framebuffer parameters
                // TODO: Get actual dimensions from display configuration
                let value: u32 = match param {
                    0 => DEFAULT_FB_WIDTH,  // width
                    1 => DEFAULT_FB_HEIGHT, // height
                    _ => 0,
                };
                value.to_le_bytes().to_vec()
            } else {
                vec![0x00, 0x00, 0x00, 0x00]
            }
        }
        
        OP_rcCreateColorBuffer => {
            if data.len() >= 16 {
                let width = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                let height = u32::from_le_bytes([data[8], data[9], data[10], data[11]]);
                let format = u32::from_le_bytes([data[12], data[13], data[14], data[15]]);
                
                info!("rcCreateColorBuffer: {}x{}, format={}", width, height, format);
                
                // Allocate a new color buffer ID
                let mut next_id = NEXT_BUFFER_ID.lock().unwrap();
                let buffer_id = *next_id;
                *next_id += 1;
                drop(next_id);
                
                // Store buffer info
                let buffer = ColorBuffer {
                    id: buffer_id,
                    width,
                    height,
                    format,
                };
                COLOR_BUFFERS.lock().unwrap().insert(buffer_id, buffer);
                
                info!("Created color buffer with ID: {}", buffer_id);
                buffer_id.to_le_bytes().to_vec()
            } else {
                vec![0x00, 0x00, 0x00, 0x00]
            }
        }
        
        OP_rcOpenColorBuffer => {
            if data.len() >= 8 {
                let buffer_id = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                info!("rcOpenColorBuffer: {}", buffer_id);
                
                // Check if buffer exists
                let buffers = COLOR_BUFFERS.lock().unwrap();
                let result: u32 = if buffers.contains_key(&buffer_id) { 0 } else { 1 };
                result.to_le_bytes().to_vec()
            } else {
                vec![0x01, 0x00, 0x00, 0x00] // Error
            }
        }
        
        OP_rcCloseColorBuffer => {
            if data.len() >= 8 {
                let buffer_id = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                info!("rcCloseColorBuffer: {}", buffer_id);
                // Just acknowledge - don't actually delete the buffer yet
                vec![0x00, 0x00, 0x00, 0x00]
            } else {
                vec![0x00, 0x00, 0x00, 0x00]
            }
        }
        
        OP_rcSetWindowColorBuffer | OP_rcFlushWindowColorBuffer => {
            info!("rcSetWindowColorBuffer or rcFlushWindowColorBuffer");
            // Acknowledge the operation
            vec![0x00, 0x00, 0x00, 0x00]
        }
        
        OP_rcMakeCurrent => {
            if data.len() >= 16 {
                let context = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                let draw = u32::from_le_bytes([data[8], data[9], data[10], data[11]]);
                let read = u32::from_le_bytes([data[12], data[13], data[14], data[15]]);
                info!("rcMakeCurrent: context={}, draw={}, read={}", context, draw, read);
                // Return success
                vec![0x00, 0x00, 0x00, 0x00]
            } else {
                vec![0x00, 0x00, 0x00, 0x00]
            }
        }
        
        OP_rcFBPost | OP_rcFBSetSwapInterval | OP_rcBindTexture | OP_rcBindRenderbuffer => {
            debug!("Framebuffer/Bind operation: 0x{:08x}", opcode);
            // Acknowledge the operation
            vec![0x00, 0x00, 0x00, 0x00]
        }
        
        OP_rcCreateContext | OP_rcDestroyContext | 
        OP_rcCreateWindowSurface | OP_rcDestroyWindowSurface => {
            debug!("Context/Surface operation: 0x{:08x}", opcode);
            // Return success/fake handle
            vec![0x01, 0x00, 0x00, 0x00]
        }
        
        _ => {
            debug!("Unknown opcode: 0x{:08x}, returning success", opcode);
            // Return success for unknown commands to keep things moving
            vec![0x00, 0x00, 0x00, 0x00]
        }
    }
}
