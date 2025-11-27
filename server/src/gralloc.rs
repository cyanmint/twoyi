// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Software gralloc implementation for the twoyi server.
//! 
//! This module provides a software-based gralloc HAL replacement that allows
//! the container's graphics services to function without libOpenglRender.so.
//! 
//! It creates a shared memory framebuffer that can be read by the server
//! and streamed to connected clients.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::os::unix::io::RawFd;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;

use log::{debug, error, info, warn};

/// Gralloc buffer format - matches Android's HAL_PIXEL_FORMAT values
#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum PixelFormat {
    Rgba8888 = 1,
    Rgbx8888 = 2,
    Rgb888 = 3,
    Rgb565 = 4,
    Bgra8888 = 5,
}

impl PixelFormat {
    pub fn bytes_per_pixel(&self) -> usize {
        match self {
            PixelFormat::Rgba8888 | PixelFormat::Rgbx8888 | PixelFormat::Bgra8888 => 4,
            PixelFormat::Rgb888 => 3,
            PixelFormat::Rgb565 => 2,
        }
    }
    
    pub fn from_u32(value: u32) -> Option<Self> {
        match value {
            1 => Some(PixelFormat::Rgba8888),
            2 => Some(PixelFormat::Rgbx8888),
            3 => Some(PixelFormat::Rgb888),
            4 => Some(PixelFormat::Rgb565),
            5 => Some(PixelFormat::Bgra8888),
            _ => None,
        }
    }
}

/// Buffer usage flags - matches Android's gralloc usage flags
#[allow(dead_code)]
#[repr(u64)]
#[derive(Debug, Clone, Copy)]
pub enum BufferUsage {
    CpuRead = 0x1,
    CpuWrite = 0x2,
    GpuTexture = 0x100,
    GpuRenderTarget = 0x200,
    Composer = 0x800,
    Framebuffer = 0x4000,
}

/// Gralloc buffer descriptor
#[derive(Debug, Clone)]
pub struct BufferDescriptor {
    pub width: u32,
    pub height: u32,
    pub format: PixelFormat,
    #[allow(dead_code)]
    pub usage: u64,
    pub stride: u32,
}

/// A gralloc buffer backed by shared memory
pub struct GrallocBuffer {
    #[allow(dead_code)]
    pub id: u64,
    pub descriptor: BufferDescriptor,
    pub data: Vec<u8>,
    #[allow(dead_code)]
    pub fd: Option<RawFd>,
}

impl GrallocBuffer {
    pub fn new(id: u64, width: u32, height: u32, format: PixelFormat, usage: u64) -> Self {
        let bpp = format.bytes_per_pixel();
        let stride = width; // Simple stride calculation
        let size = (stride as usize) * (height as usize) * bpp;
        
        GrallocBuffer {
            id,
            descriptor: BufferDescriptor {
                width,
                height,
                format,
                usage,
                stride,
            },
            data: vec![0u8; size],
            fd: None,
        }
    }
    
    pub fn size(&self) -> usize {
        self.data.len()
    }
}

/// Gralloc command types for protocol
#[repr(u32)]
#[derive(Debug, Clone, Copy)]
pub enum GrallocCommand {
    Allocate = 1,
    Free = 2,
    Lock = 3,
    Unlock = 4,
    GetInfo = 5,
    ImportBuffer = 6,
    Present = 7,  // Signal that buffer is ready to display
}

impl GrallocCommand {
    pub fn from_u32(value: u32) -> Option<Self> {
        match value {
            1 => Some(GrallocCommand::Allocate),
            2 => Some(GrallocCommand::Free),
            3 => Some(GrallocCommand::Lock),
            4 => Some(GrallocCommand::Unlock),
            5 => Some(GrallocCommand::GetInfo),
            6 => Some(GrallocCommand::ImportBuffer),
            7 => Some(GrallocCommand::Present),
            _ => None,
        }
    }
}

/// Gralloc request structure
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct GrallocRequest {
    pub command: u32,
    pub buffer_id: u64,
    pub width: u32,
    pub height: u32,
    pub format: u32,
    pub usage: u64,
    pub offset: u64,
    pub size: u64,
}

impl GrallocRequest {
    /// Size of the serialized request in bytes
    pub const SIZE: usize = 4 + 8 + 4 + 4 + 4 + 8 + 8 + 8; // 48 bytes
    
    /// Parse a request from a byte buffer using safe methods
    pub fn from_bytes(buf: &[u8]) -> Option<Self> {
        if buf.len() < Self::SIZE {
            return None;
        }
        
        Some(GrallocRequest {
            command: u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]),
            buffer_id: u64::from_le_bytes([buf[4], buf[5], buf[6], buf[7], buf[8], buf[9], buf[10], buf[11]]),
            width: u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]),
            height: u32::from_le_bytes([buf[16], buf[17], buf[18], buf[19]]),
            format: u32::from_le_bytes([buf[20], buf[21], buf[22], buf[23]]),
            usage: u64::from_le_bytes([buf[24], buf[25], buf[26], buf[27], buf[28], buf[29], buf[30], buf[31]]),
            offset: u64::from_le_bytes([buf[32], buf[33], buf[34], buf[35], buf[36], buf[37], buf[38], buf[39]]),
            size: u64::from_le_bytes([buf[40], buf[41], buf[42], buf[43], buf[44], buf[45], buf[46], buf[47]]),
        })
    }
}

/// Gralloc response structure  
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct GrallocResponse {
    pub status: i32,
    pub buffer_id: u64,
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: u32,
    pub size: u64,
}

impl GrallocResponse {
    /// Size of the serialized response in bytes
    pub const SIZE: usize = 4 + 8 + 4 + 4 + 4 + 4 + 8; // 36 bytes
    
    /// Serialize the response to bytes using safe methods
    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        
        buf[0..4].copy_from_slice(&self.status.to_le_bytes());
        buf[4..12].copy_from_slice(&self.buffer_id.to_le_bytes());
        buf[12..16].copy_from_slice(&self.width.to_le_bytes());
        buf[16..20].copy_from_slice(&self.height.to_le_bytes());
        buf[20..24].copy_from_slice(&self.stride.to_le_bytes());
        buf[24..28].copy_from_slice(&self.format.to_le_bytes());
        buf[28..36].copy_from_slice(&self.size.to_le_bytes());
        
        buf
    }
}

/// The main gralloc server that manages buffers and handles client requests
pub struct GrallocServer {
    buffers: Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    next_buffer_id: Arc<AtomicU64>,
    display_buffer_id: Arc<RwLock<Option<u64>>>,
    running: Arc<AtomicBool>,
    socket_path: String,
    width: u32,
    height: u32,
    /// Callback to notify when framebuffer is updated
    framebuffer_callback: Arc<Mutex<Option<Box<dyn Fn(&[u8], u32, u32) + Send + Sync>>>>,
}

impl GrallocServer {
    pub fn new(socket_path: &str, width: u32, height: u32) -> Self {
        GrallocServer {
            buffers: Arc::new(RwLock::new(HashMap::new())),
            next_buffer_id: Arc::new(AtomicU64::new(1)),
            display_buffer_id: Arc::new(RwLock::new(None)),
            running: Arc::new(AtomicBool::new(false)),
            socket_path: socket_path.to_string(),
            width,
            height,
            framebuffer_callback: Arc::new(Mutex::new(None)),
        }
    }
    
    /// Set a callback to be called when the framebuffer is updated
    pub fn set_framebuffer_callback<F>(&self, callback: F) 
    where 
        F: Fn(&[u8], u32, u32) + Send + Sync + 'static 
    {
        let mut cb = self.framebuffer_callback.lock().unwrap();
        *cb = Some(Box::new(callback));
    }
    
    /// Get the current display buffer data
    #[allow(dead_code)]
    pub fn get_display_buffer(&self) -> Option<(Vec<u8>, u32, u32)> {
        let display_id = self.display_buffer_id.read().ok()?;
        let buffer_id = (*display_id)?;
        
        let buffers = self.buffers.read().ok()?;
        let buffer = buffers.get(&buffer_id)?;
        
        Some((
            buffer.data.clone(),
            buffer.descriptor.width,
            buffer.descriptor.height,
        ))
    }
    
    /// Start the gralloc server
    pub fn start(&self) -> std::io::Result<()> {
        if self.running.swap(true, Ordering::SeqCst) {
            return Ok(()); // Already running
        }
        
        // Remove old socket if exists
        let _ = std::fs::remove_file(&self.socket_path);
        
        // Ensure parent directory exists
        if let Some(parent) = Path::new(&self.socket_path).parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        
        let listener = UnixListener::bind(&self.socket_path)?;
        info!("Gralloc server listening on {}", self.socket_path);
        
        let buffers = self.buffers.clone();
        let next_id = self.next_buffer_id.clone();
        let display_id = self.display_buffer_id.clone();
        let running = self.running.clone();
        let callback = self.framebuffer_callback.clone();
        let width = self.width;
        let height = self.height;
        
        thread::spawn(move || {
            for stream in listener.incoming() {
                if !running.load(Ordering::SeqCst) {
                    break;
                }
                
                match stream {
                    Ok(stream) => {
                        let buffers = buffers.clone();
                        let next_id_clone = next_id.clone();
                        let display_id = display_id.clone();
                        let callback = callback.clone();
                        
                        thread::spawn(move || {
                            if let Err(e) = handle_gralloc_client(
                                stream, 
                                buffers, 
                                next_id_clone,
                                display_id,
                                callback,
                                width,
                                height,
                            ) {
                                debug!("Gralloc client error: {}", e);
                            }
                        });
                    }
                    Err(e) => {
                        error!("Error accepting gralloc connection: {}", e);
                    }
                }
            }
            info!("Gralloc server stopped");
        });
        
        Ok(())
    }
    
    #[allow(dead_code)]
    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }
}

fn handle_gralloc_client(
    mut stream: UnixStream,
    buffers: Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    next_id: Arc<AtomicU64>,
    display_id: Arc<RwLock<Option<u64>>>,
    callback: Arc<Mutex<Option<Box<dyn Fn(&[u8], u32, u32) + Send + Sync>>>>,
    default_width: u32,
    default_height: u32,
) -> std::io::Result<()> {
    info!("Gralloc client connected");
    
    let mut request_buf = [0u8; GrallocRequest::SIZE];
    
    loop {
        // Read request
        match stream.read_exact(&mut request_buf) {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                debug!("Gralloc client disconnected");
                break;
            }
            Err(e) => return Err(e),
        }
        
        // Parse request using safe method
        let request = match GrallocRequest::from_bytes(&request_buf) {
            Some(req) => req,
            None => {
                error!("Failed to parse gralloc request");
                continue;
            }
        };
        
        let response = match GrallocCommand::from_u32(request.command) {
            Some(GrallocCommand::Allocate) => {
                handle_allocate(&request, &buffers, &next_id, default_width, default_height)
            }
            Some(GrallocCommand::Free) => {
                handle_free(&request, &buffers)
            }
            Some(GrallocCommand::Lock) => {
                handle_lock(&request, &buffers, &mut stream)
            }
            Some(GrallocCommand::Unlock) => {
                // Handle unlock with potential buffer data
                handle_unlock_with_data(&request, &buffers, &mut stream)?
            }
            Some(GrallocCommand::GetInfo) => {
                handle_get_info(&request, &buffers)
            }
            Some(GrallocCommand::Present) => {
                handle_present(&request, &display_id, &buffers, &callback)
            }
            _ => {
                let cmd = request.command;
                warn!("Unknown gralloc command: {}", cmd);
                GrallocResponse {
                    status: -1,
                    buffer_id: 0,
                    width: 0,
                    height: 0,
                    stride: 0,
                    format: 0,
                    size: 0,
                }
            }
        };
        
        // Skip sending response for commands that already sent their own
        if response.status == -2 {
            continue;
        }
        
        // Send response using safe serialization
        let response_bytes = response.to_bytes();
        stream.write_all(&response_bytes)?;
    }
    
    Ok(())
}

fn handle_allocate(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    next_id: &Arc<AtomicU64>,
    default_width: u32,
    default_height: u32,
) -> GrallocResponse {
    let width = if request.width > 0 { request.width } else { default_width };
    let height = if request.height > 0 { request.height } else { default_height };
    let format = PixelFormat::from_u32(request.format).unwrap_or(PixelFormat::Rgba8888);
    
    let buffer_id = next_id.fetch_add(1, Ordering::SeqCst);
    let buffer = GrallocBuffer::new(buffer_id, width, height, format, request.usage);
    let size = buffer.size() as u64;
    let stride = buffer.descriptor.stride;
    
    info!("Allocating buffer {}: {}x{} format={:?}", buffer_id, width, height, format);
    
    if let Ok(mut bufs) = buffers.write() {
        bufs.insert(buffer_id, buffer);
    }
    
    GrallocResponse {
        status: 0,
        buffer_id,
        width,
        height,
        stride,
        format: format as u32,
        size,
    }
}

fn handle_free(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
) -> GrallocResponse {
    let buffer_id = request.buffer_id;
    
    if let Ok(mut bufs) = buffers.write() {
        if bufs.remove(&buffer_id).is_some() {
            debug!("Freed buffer {}", buffer_id);
            return GrallocResponse {
                status: 0,
                buffer_id,
                width: 0,
                height: 0,
                stride: 0,
                format: 0,
                size: 0,
            };
        }
    }
    
    GrallocResponse {
        status: -1,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    }
}

fn handle_lock(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    stream: &mut UnixStream,
) -> GrallocResponse {
    let buffer_id = request.buffer_id;
    
    if let Ok(bufs) = buffers.read() {
        if let Some(buffer) = bufs.get(&buffer_id) {
            // Send buffer data after response
            let response = GrallocResponse {
                status: 0,
                buffer_id,
                width: buffer.descriptor.width,
                height: buffer.descriptor.height,
                stride: buffer.descriptor.stride,
                format: buffer.descriptor.format as u32,
                size: buffer.size() as u64,
            };
            
            // Send response first using safe serialization
            let response_bytes = response.to_bytes();
            if stream.write_all(&response_bytes).is_ok() {
                // Then send buffer data
                let _ = stream.write_all(&buffer.data);
            }
            
            // Return a dummy response since we already sent it
            return GrallocResponse {
                status: -2, // Special code meaning "already sent"
                buffer_id: 0,
                width: 0,
                height: 0,
                stride: 0,
                format: 0,
                size: 0,
            };
        }
    }
    
    GrallocResponse {
        status: -1,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    }
}

fn handle_unlock_with_data(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    stream: &mut UnixStream,
) -> std::io::Result<GrallocResponse> {
    let buffer_id = request.buffer_id;
    let data_size = request.size as usize;
    
    // If there's data to receive (size > 0 means client is sending updated buffer data)
    if data_size > 0 {
        let mut data = vec![0u8; data_size];
        stream.read_exact(&mut data)?;
        
        if let Ok(mut bufs) = buffers.write() {
            if let Some(buffer) = bufs.get_mut(&buffer_id) {
                let copy_len = std::cmp::min(data.len(), buffer.data.len());
                buffer.data[..copy_len].copy_from_slice(&data[..copy_len]);
                debug!("Buffer {} updated with {} bytes", buffer_id, copy_len);
            }
        }
    }
    
    if let Ok(bufs) = buffers.read() {
        if bufs.contains_key(&buffer_id) {
            return Ok(GrallocResponse {
                status: 0,
                buffer_id,
                width: 0,
                height: 0,
                stride: 0,
                format: 0,
                size: 0,
            });
        }
    }
    
    Ok(GrallocResponse {
        status: -1,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    })
}

#[allow(dead_code)]
fn handle_unlock(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
) -> GrallocResponse {
    let buffer_id = request.buffer_id;
    
    // Check if there's data to write (size > 0 means client is sending updated buffer data)
    if request.size > 0 {
        if let Ok(mut bufs) = buffers.write() {
            if let Some(_buffer) = bufs.get_mut(&buffer_id) {
                // The client should send buffer data after unlock request
                // This is handled in the caller
                debug!("Buffer {} unlocked with pending data", buffer_id);
            }
        }
    }
    
    if let Ok(bufs) = buffers.read() {
        if bufs.contains_key(&buffer_id) {
            return GrallocResponse {
                status: 0,
                buffer_id,
                width: 0,
                height: 0,
                stride: 0,
                format: 0,
                size: 0,
            };
        }
    }
    
    GrallocResponse {
        status: -1,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    }
}

fn handle_get_info(
    request: &GrallocRequest,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
) -> GrallocResponse {
    let buffer_id = request.buffer_id;
    
    if let Ok(bufs) = buffers.read() {
        if let Some(buffer) = bufs.get(&buffer_id) {
            return GrallocResponse {
                status: 0,
                buffer_id,
                width: buffer.descriptor.width,
                height: buffer.descriptor.height,
                stride: buffer.descriptor.stride,
                format: buffer.descriptor.format as u32,
                size: buffer.size() as u64,
            };
        }
    }
    
    GrallocResponse {
        status: -1,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    }
}

fn handle_present(
    request: &GrallocRequest,
    display_id: &Arc<RwLock<Option<u64>>>,
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    callback: &Arc<Mutex<Option<Box<dyn Fn(&[u8], u32, u32) + Send + Sync>>>>,
) -> GrallocResponse {
    let buffer_id = request.buffer_id;
    
    // Update display buffer
    if let Ok(mut id) = display_id.write() {
        *id = Some(buffer_id);
    }
    
    // Call framebuffer callback if set
    if let Ok(bufs) = buffers.read() {
        if let Some(buffer) = bufs.get(&buffer_id) {
            if let Ok(cb) = callback.lock() {
                if let Some(ref callback_fn) = *cb {
                    callback_fn(&buffer.data, buffer.descriptor.width, buffer.descriptor.height);
                }
            }
        }
    }
    
    GrallocResponse {
        status: 0,
        buffer_id,
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        size: 0,
    }
}

/// Write buffer data to a gralloc buffer
#[allow(dead_code)]
pub fn write_buffer_data(
    buffers: &Arc<RwLock<HashMap<u64, GrallocBuffer>>>,
    buffer_id: u64,
    data: &[u8],
) -> bool {
    if let Ok(mut bufs) = buffers.write() {
        if let Some(buffer) = bufs.get_mut(&buffer_id) {
            let len = std::cmp::min(data.len(), buffer.data.len());
            buffer.data[..len].copy_from_slice(&data[..len]);
            return true;
        }
    }
    false
}
