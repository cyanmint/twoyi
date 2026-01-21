// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! FFI exports for OpenGL renderer
//! 
//! This module provides C-compatible function exports that can be called
//! from the C wrapper to forward calls to the Rust renderer implementation.

use std::ffi::c_void;
use log::{info, error};

use crate::renderer_new::renderer;

/// Start the OpenGL renderer
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_start_renderer(
    window: *mut c_void,
    width: i32,
    height: i32,
    xdpi: i32,
    ydpi: i32,
    fps: i32,
) -> i32 {
    info!("[FFI] rust_start_renderer called");
    info!("[FFI] window={:?}, size={}x{}, dpi={}x{}, fps={}", 
          window, width, height, xdpi, ydpi, fps);
    
    let result = renderer::start_renderer(window, width, height, xdpi, ydpi, fps);
    info!("[FFI] rust_start_renderer result: {}", result);
    result
}

/// Set or update the native window
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_set_native_window(window: *mut c_void) -> i32 {
    info!("[FFI] rust_set_native_window called with window={:?}", window);
    
    let result = renderer::set_native_window(window);
    info!("[FFI] rust_set_native_window result: {}", result);
    result
}

/// Reset subwindow parameters
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_reset_window(
    window: *mut c_void,
    wx: i32,
    wy: i32,
    ww: i32,
    wh: i32,
    fbw: i32,
    fbh: i32,
    dpr: f32,
    z_rot: f32,
) -> i32 {
    info!("[FFI] rust_reset_window called");
    info!("[FFI] window={:?}, pos=({},{}), surface={}x{}, fb={}x{}, dpr={}, z_rot={}", 
          window, wx, wy, ww, wh, fbw, fbh, dpr, z_rot);
    
    let result = renderer::reset_window(window, wx, wy, ww, wh, fbw, fbh, dpr, z_rot);
    info!("[FFI] rust_reset_window result: {}", result);
    result
}

/// Remove a window
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_remove_window(window: *mut c_void) -> i32 {
    info!("[FFI] rust_remove_window called with window={:?}", window);
    
    let result = renderer::remove_window(window);
    info!("[FFI] rust_remove_window result: {}", result);
    result
}

/// Destroy the OpenGL subwindow
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_destroy_subwindow() -> i32 {
    info!("[FFI] rust_destroy_subwindow called");
    
    let result = renderer::destroy_subwindow();
    info!("[FFI] rust_destroy_subwindow result: {}", result);
    result
}

/// Repaint the OpenGL display
/// 
/// C-compatible function that forwards to the Rust implementation
#[no_mangle]
pub extern "C" fn rust_repaint_display() {
    info!("[FFI] rust_repaint_display called");
    
    renderer::repaint_display();
    info!("[FFI] rust_repaint_display completed");
}
