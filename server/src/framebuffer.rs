// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use log::info;

const FRAME_HEADER: &[u8] = b"FRAME";
const FRAME_FPS: u64 = 30; // Target FPS for streaming

/// Framebuffer streaming manager
pub struct FrameStreamer {
    width: i32,
    height: i32,
    clients: Arc<Mutex<Vec<TcpStream>>>,
    running: Arc<AtomicBool>,
    framebuffer_path: String,
}

impl FrameStreamer {
    pub fn new(width: i32, height: i32, rootfs_path: &str) -> Self {
        // The framebuffer is typically at /dev/graphics/fb0 in the container
        let framebuffer_path = format!("{}/dev/graphics/fb0", rootfs_path);
        Self::new_with_path(width, height, &framebuffer_path)
    }

    /// Create a new FrameStreamer with a custom framebuffer path
    pub fn new_with_path(width: i32, height: i32, framebuffer_path: &str) -> Self {
        FrameStreamer {
            width,
            height,
            clients: Arc::new(Mutex::new(Vec::new())),
            running: Arc::new(AtomicBool::new(false)),
            framebuffer_path: framebuffer_path.to_string(),
        }
    }
    
    pub fn add_client(&self, stream: TcpStream) {
        if let Ok(mut clients) = self.clients.lock() {
            info!("Adding framebuffer client");
            clients.push(stream);
        }
    }
    
    pub fn start(&self) {
        if self.running.swap(true, Ordering::SeqCst) {
            return; // Already running
        }
        
        let clients = self.clients.clone();
        let running = self.running.clone();
        let width = self.width;
        let height = self.height;
        let fb_path = self.framebuffer_path.clone();
        
        thread::spawn(move || {
            info!("Framebuffer streamer started");
            let frame_duration = Duration::from_millis(1000 / FRAME_FPS);
            
            // Create a test pattern if framebuffer is not available
            let frame_size = (width * height * 4) as usize; // RGBA
            let mut frame_data = vec![0u8; frame_size];
            let mut frame_counter: u32 = 0;
            
            while running.load(Ordering::SeqCst) {
                // Try to read from framebuffer, otherwise generate test pattern
                let frame = if let Ok(mut fb) = std::fs::File::open(&fb_path) {
                    let mut data = vec![0u8; frame_size];
                    if fb.read_exact(&mut data).is_ok() {
                        data
                    } else {
                        generate_test_pattern(&mut frame_data, width, height, frame_counter);
                        frame_data.clone()
                    }
                } else {
                    generate_test_pattern(&mut frame_data, width, height, frame_counter);
                    frame_data.clone()
                };
                
                // Send to all connected clients
                if let Ok(mut clients) = clients.lock() {
                    let mut i = 0;
                    while i < clients.len() {
                        let result = send_frame(&mut clients[i], &frame, width, height).is_ok();
                        if !result {
                            info!("Client disconnected from framebuffer stream");
                            clients.remove(i);
                        } else {
                            i += 1;
                        }
                    }
                }
                
                frame_counter = frame_counter.wrapping_add(1);
                thread::sleep(frame_duration);
            }
            
            info!("Framebuffer streamer stopped");
        });
    }
    
    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }
}

fn generate_test_pattern(data: &mut [u8], width: i32, height: i32, frame: u32) {
    let w = width as usize;
    let h = height as usize;
    
    // Create a cleaner test pattern - solid color with time indicator
    let phase = (frame / 30) % 6; // Change color every second
    
    let (base_r, base_g, base_b) = match phase {
        0 => (50, 50, 50),    // Dark gray
        1 => (100, 50, 50),   // Dark red
        2 => (50, 100, 50),   // Dark green
        3 => (50, 50, 100),   // Dark blue
        4 => (100, 100, 50),  // Dark yellow
        _ => (50, 100, 100),  // Dark cyan
    };
    
    for y in 0..h {
        for x in 0..w {
            let idx = (y * w + x) * 4;
            if idx + 3 < data.len() {
                // Draw a border
                let border = 20;
                let is_border = x < border || x >= w - border || y < border || y >= h - border;
                
                // Draw center crosshair
                let center_x = w / 2;
                let center_y = h / 2;
                let is_crosshair = (x > center_x - 2 && x < center_x + 2 && y > h/4 && y < 3*h/4) ||
                                   (y > center_y - 2 && y < center_y + 2 && x > w/4 && x < 3*w/4);
                
                // Draw animation indicator at top
                let indicator_y = 100;
                let indicator_size = 50;
                let anim_x = ((frame * 5) as usize) % (w - indicator_size);
                let is_indicator = y > indicator_y && y < indicator_y + indicator_size && 
                                   x > anim_x && x < anim_x + indicator_size;
                
                let (r, g, b) = if is_border {
                    (255, 255, 255) // White border
                } else if is_crosshair {
                    (255, 0, 0) // Red crosshair
                } else if is_indicator {
                    (0, 255, 0) // Green moving indicator
                } else {
                    (base_r, base_g, base_b)
                };
                
                data[idx] = r;     // R
                data[idx + 1] = g; // G
                data[idx + 2] = b; // B
                data[idx + 3] = 255; // A
            }
        }
    }
}

fn send_frame(stream: &mut TcpStream, data: &[u8], width: i32, height: i32) -> std::io::Result<()> {
    
    
    // Simple frame protocol: HEADER + width(4) + height(4) + length(4) + data
    stream.write_all(FRAME_HEADER)?;
    stream.write_all(&width.to_le_bytes())?;
    stream.write_all(&height.to_le_bytes())?;
    stream.write_all(&(data.len() as u32).to_le_bytes())?;
    stream.write_all(data)?;
    stream.flush()?;
    
    Ok(())
}
