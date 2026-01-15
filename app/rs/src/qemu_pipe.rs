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

// OpenGL ES renderControl command opcodes (from goldfish-opengl)
// Using original goldfish naming for easier correlation with protocol docs
#[allow(non_upper_case_globals)]
const OP_rcGetRendererVersion: u32 = 10000;
#[allow(non_upper_case_globals)]
const OP_rcGetEGLVersion: u32 = 10001;
#[allow(non_upper_case_globals, dead_code)]
const OP_rcQueryEGLString: u32 = 10002;
#[allow(non_upper_case_globals, dead_code)]
const OP_rcGetGLString: u32 = 10003;
#[allow(non_upper_case_globals)]
const OP_rcGetNumConfigs: u32 = 10004;
#[allow(non_upper_case_globals, dead_code)]
const OP_rcGetConfigs: u32 = 10005;
#[allow(non_upper_case_globals, dead_code)]
const OP_rcChooseConfig: u32 = 10006;
#[allow(non_upper_case_globals)]
const OP_rcGetFBParam: u32 = 10007;
#[allow(non_upper_case_globals)]
const OP_rcCreateContext: u32 = 10008;
#[allow(non_upper_case_globals)]
const OP_rcDestroyContext: u32 = 10009;
#[allow(non_upper_case_globals)]
const OP_rcCreateWindowSurface: u32 = 10010;
#[allow(non_upper_case_globals)]
const OP_rcDestroyWindowSurface: u32 = 10011;
#[allow(non_upper_case_globals)]
const OP_rcCreateColorBuffer: u32 = 10012;
#[allow(non_upper_case_globals)]
const OP_rcOpenColorBuffer: u32 = 10013;
#[allow(non_upper_case_globals)]
const OP_rcCloseColorBuffer: u32 = 10014;
#[allow(non_upper_case_globals)]
const OP_rcSetWindowColorBuffer: u32 = 10015;
#[allow(non_upper_case_globals)]
const OP_rcFlushWindowColorBuffer: u32 = 10016;
#[allow(non_upper_case_globals)]
const OP_rcMakeCurrent: u32 = 10017;
#[allow(non_upper_case_globals)]
const OP_rcFBPost: u32 = 10018;
#[allow(non_upper_case_globals)]
const OP_rcFBSetSwapInterval: u32 = 10019;
#[allow(non_upper_case_globals)]
const OP_rcBindTexture: u32 = 10020;
#[allow(non_upper_case_globals)]
const OP_rcBindRenderbuffer: u32 = 10021;

/// Color buffer information
#[derive(Debug, Clone)]
#[allow(dead_code)]
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
                    let client_id = std::process::id() as u64 * 1000000 + std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_micros() as u64;
                    info!("[CLIENT_{}] Pipe client connected to {}", client_id, path);
                    
                    // Spawn a thread to handle this client
                    let is_gl = is_direct_opengl;
                    thread::spawn(move || {
                        if is_gl {
                            // Direct OpenGL service - no service name negotiation needed
                            info!("[CLIENT_{}] Using direct OpenGL service mode", client_id);
                            handle_opengl_service_direct(&mut stream, client_id);
                        } else {
                            // Generic pipe - need to read service name first
                            info!("[CLIENT_{}] Using generic pipe mode with service negotiation", client_id);
                            handle_pipe_client(&mut stream, client_id);
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
        info!("[CLIENT_{}] Matched OpenGL service, starting handler", client_id);
        handle_opengl_service(stream, service_name, client_id);
    } else {
        error!("[CLIENT_{}] Unknown service requested: '{}', sending error response", client_id, service);
        // Send error response
        let _ = stream.write_all(&[0xff, 0xff, 0xff, 0xff]);
    }
}

/// Handle OpenGL ES service requests for direct connections (no service name exchange)
fn handle_opengl_service_direct(stream: &mut unix_socket::UnixStream, client_id: u64) {
    info!("[CLIENT_{}] ========== DIRECT OPENGL SERVICE CONNECTION ==========", client_id);
    
    // For direct connections, start processing commands immediately
    handle_opengl_commands(stream, client_id);
}

/// Handle OpenGL ES service requests (after service name negotiation)
fn handle_opengl_service(stream: &mut unix_socket::UnixStream, service: &str, client_id: u64) {
    info!("[CLIENT_{}] ========== OPENGL SERVICE AFTER NEGOTIATION: {} ==========", client_id, service);
    
    // Send success response (0x00000000) to indicate service is available
    info!("[CLIENT_{}] Sending success response (4 bytes: 0x00000000)", client_id);
    if let Err(e) = stream.write_all(&[0x00, 0x00, 0x00, 0x00]) {
        error!("[CLIENT_{}] Failed to send success response: {}", client_id, e);
        return;
    }
    
    info!("[CLIENT_{}] Success response sent successfully, starting command loop", client_id);
    handle_opengl_commands(stream, client_id);
}

/// Process OpenGL command stream
fn handle_opengl_commands(stream: &mut unix_socket::UnixStream, client_id: u64) {
    info!("[CLIENT_{}] ========== STARTING COMMAND PROCESSING LOOP ==========", client_id);
    let mut buffer = vec![0u8; COMMAND_BUFFER_SIZE];
    let mut command_count = 0u64;
    
    loop {
        info!("[CLIENT_{}] Waiting for command #{} ...", client_id, command_count);
        match stream.read(&mut buffer) {
            Ok(0) => {
                info!("[CLIENT_{}] ========== CLIENT DISCONNECTED GRACEFULLY ==========", client_id);
                info!("[CLIENT_{}] Total commands processed: {}", client_id, command_count);
                break;
            }
            Ok(n) => {
                command_count += 1;
                info!("[CLIENT_{}] ========== COMMAND #{} RECEIVED ({} bytes) ==========", client_id, command_count, n);
                
                // Log the raw data in hex
                if n <= 64 {
                    info!("[CLIENT_{}] Raw data (all {} bytes): {:02x?}", client_id, n, &buffer[..n]);
                } else {
                    info!("[CLIENT_{}] Raw data (first 64 of {} bytes): {:02x?}...", client_id, n, &buffer[..64]);
                }
                
                // Process the command and send response
                let response = process_opengl_command(&buffer[..n], client_id, command_count);
                
                info!("[CLIENT_{}] ========== SENDING RESPONSE FOR COMMAND #{} ({} bytes) ==========", 
                      client_id, command_count, response.len());
                if response.len() <= 64 {
                    info!("[CLIENT_{}] Response data (all {} bytes): {:02x?}", client_id, response.len(), &response);
                } else {
                    info!("[CLIENT_{}] Response data (first 64 of {} bytes): {:02x?}...", client_id, response.len(), &response[..64]);
                }
                
                if let Err(e) = stream.write_all(&response) {
                    error!("[CLIENT_{}] ========== FAILED TO SEND RESPONSE: {} ==========", client_id, e);
                    break;
                }
                info!("[CLIENT_{}] Response sent successfully for command #{}", client_id, command_count);
            }
            Err(e) => {
                error!("[CLIENT_{}] ========== ERROR READING COMMAND: {} ==========", client_id, e);
                break;
            }
        }
    }
    info!("[CLIENT_{}] ========== EXITING COMMAND PROCESSING LOOP ==========", client_id);
}

/// Process OpenGL command data and return response
#[allow(non_upper_case_globals)]
fn process_opengl_command(data: &[u8], client_id: u64, command_num: u64) -> Vec<u8> {
    if data.len() < 4 {
        error!("[CLIENT_{}][CMD_{}] Command too short: {} bytes (need at least 4 for opcode)", 
               client_id, command_num, data.len());
        return vec![0x00, 0x00, 0x00, 0x00]; // Error response
    }
    
    let opcode = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    info!("[CLIENT_{}][CMD_{}] ========== PROCESSING OPCODE: 0x{:08x} ({}) ==========", 
          client_id, command_num, opcode, opcode);
    
    // Log data size if present
    if data.len() >= 8 {
        let size = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
        info!("[CLIENT_{}][CMD_{}] Command size field: {} bytes", client_id, command_num, size);
    }
    
    match opcode {
        OP_rcGetRendererVersion => {
            // Return a fake renderer version
            info!("[CLIENT_{}][CMD_{}] rcGetRendererVersion -> returning version 1", client_id, command_num);
            let version: u32 = 1; // Version 1
            let response = version.to_le_bytes().to_vec();
            info!("[CLIENT_{}][CMD_{}] rcGetRendererVersion response: {:02x?}", client_id, command_num, response);
            response
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
                let value: u32 = match param {
                    0 => {
                        info!("[CLIENT_{}][CMD_{}] rcGetFBParam: param={} (WIDTH) -> {}", 
                              client_id, command_num, param, DEFAULT_FB_WIDTH);
                        DEFAULT_FB_WIDTH
                    },
                    1 => {
                        info!("[CLIENT_{}][CMD_{}] rcGetFBParam: param={} (HEIGHT) -> {}", 
                              client_id, command_num, param, DEFAULT_FB_HEIGHT);
                        DEFAULT_FB_HEIGHT
                    },
                    _ => {
                        info!("[CLIENT_{}][CMD_{}] rcGetFBParam: param={} (UNKNOWN) -> 0", 
                              client_id, command_num, param);
                        0
                    },
                };
                let response = value.to_le_bytes().to_vec();
                info!("[CLIENT_{}][CMD_{}] rcGetFBParam response: {:02x?}", client_id, command_num, response);
                response
            } else {
                error!("[CLIENT_{}][CMD_{}] rcGetFBParam: insufficient data ({} bytes)", 
                       client_id, command_num, data.len());
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
                
                info!("[CLIENT_{}][CMD_{}] Created color buffer with ID: {} (total buffers: {})", 
                      client_id, command_num, buffer_id, COLOR_BUFFERS.lock().unwrap().len());
                let response = buffer_id.to_le_bytes().to_vec();
                info!("[CLIENT_{}][CMD_{}] rcCreateColorBuffer response: buffer_id={}, bytes={:02x?}", 
                      client_id, command_num, buffer_id, response);
                response
            } else {
                vec![0x00, 0x00, 0x00, 0x00]
            }
        }
        
        OP_rcOpenColorBuffer => {
            if data.len() >= 8 {
                let buffer_id = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                
                // Check if buffer exists
                let buffers = COLOR_BUFFERS.lock().unwrap();
                let result: u32 = if buffers.contains_key(&buffer_id) { 0 } else { 1 };
                info!("[CLIENT_{}][CMD_{}] rcOpenColorBuffer: buffer_id={}, exists={}, result={}", 
                      client_id, command_num, buffer_id, buffers.contains_key(&buffer_id), result);
                let response = result.to_le_bytes().to_vec();
                info!("[CLIENT_{}][CMD_{}] rcOpenColorBuffer response: {:02x?}", client_id, command_num, response);
                response
            } else {
                error!("[CLIENT_{}][CMD_{}] rcOpenColorBuffer: insufficient data ({} bytes)", 
                       client_id, command_num, data.len());
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
            let op_name = match opcode {
                OP_rcCreateContext => "rcCreateContext",
                OP_rcDestroyContext => "rcDestroyContext",
                OP_rcCreateWindowSurface => "rcCreateWindowSurface",
                OP_rcDestroyWindowSurface => "rcDestroyWindowSurface",
                _ => "Unknown",
            };
            info!("[CLIENT_{}][CMD_{}] {} (0x{:08x}) - returning fake handle/success", 
                  client_id, command_num, op_name, opcode);
            // Return success/fake handle
            let response = vec![0x01, 0x00, 0x00, 0x00];
            info!("[CLIENT_{}][CMD_{}] {} response: {:02x?}", client_id, command_num, op_name, response);
            response
        }
        
        _ => {
            error!("[CLIENT_{}][CMD_{}] !!!!! UNKNOWN OPCODE: 0x{:08x} ({}) !!!!!", 
                   client_id, command_num, opcode, opcode);
            error!("[CLIENT_{}][CMD_{}] Data dump: {:02x?}", client_id, command_num, data);
            error!("[CLIENT_{}][CMD_{}] Returning generic success response", client_id, command_num);
            // Return success for unknown commands to keep things moving
            vec![0x00, 0x00, 0x00, 0x00]
        }
    }
}
