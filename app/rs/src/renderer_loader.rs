// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Dynamic Renderer Loader
//! 
//! This module provides the ability to switch between the new open-source
//! Rust renderer and the legacy closed-source renderer at runtime.

use log::{info, warn};
use std::ffi::c_void;
use std::sync::{Arc, Mutex, Once};

// Function pointer types matching the OpenGL renderer API
type StartOpenGLRendererFn = extern "C" fn(*mut c_void, i32, i32, i32, i32, i32) -> i32;
type SetNativeWindowFn = extern "C" fn(*mut c_void) -> i32;
type ResetSubWindowFn = extern "C" fn(*mut c_void, i32, i32, i32, i32, i32, i32, f32, f32) -> i32;
type RemoveSubWindowFn = extern "C" fn(*mut c_void) -> i32;
type DestroyOpenGLSubwindowFn = extern "C" fn() -> i32;
type RepaintOpenGLDisplayFn = extern "C" fn();

/// Renderer implementation variant
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum RendererType {
    New,    // Built-in Rust implementation
    Legacy, // Closed-source binary
}

/// Holds function pointers for the active renderer
#[derive(Clone)]
pub struct RendererFunctions {
    pub start_opengl_renderer: StartOpenGLRendererFn,
    pub set_native_window: SetNativeWindowFn,
    pub reset_sub_window: ResetSubWindowFn,
    pub remove_sub_window: RemoveSubWindowFn,
    #[allow(dead_code)]
    pub destroy_opengl_subwindow: DestroyOpenGLSubwindowFn,
    #[allow(dead_code)]
    pub repaint_opengl_display: RepaintOpenGLDisplayFn,
}

static INIT: Once = Once::new();
static RENDERER_FUNCTIONS: Mutex<Option<Arc<RendererFunctions>>> = Mutex::new(None);

/// Initialize the renderer loader with the specified renderer type
pub fn init_renderer_loader(use_legacy: bool) {
    INIT.call_once(|| {
        let renderer_type = if use_legacy {
            RendererType::Legacy
        } else {
            RendererType::New
        };
        
        info!("Initializing renderer loader with type: {:?}", renderer_type);
        
        let functions = match renderer_type {
            RendererType::New => load_new_renderer(),
            RendererType::Legacy => load_legacy_renderer(),
        };
        
        *RENDERER_FUNCTIONS.lock().unwrap() = Some(Arc::new(functions));
    });
}

/// Get the active renderer functions
/// Returns an Arc to avoid copying function pointers on each call
pub fn get_renderer_functions() -> Arc<RendererFunctions> {
    let guard = RENDERER_FUNCTIONS.lock().unwrap();
    match &*guard {
        Some(funcs) => Arc::clone(funcs),
        None => {
            warn!("Renderer not initialized, using new renderer as fallback");
            Arc::new(load_new_renderer())
        }
    }
}

/// Load the new built-in Rust renderer
fn load_new_renderer() -> RendererFunctions {
    info!("Loading new open-source Rust renderer");
    
    // Use the built-in functions from opengl_renderer module
    RendererFunctions {
        start_opengl_renderer: crate::opengl_renderer::startOpenGLRenderer,
        set_native_window: crate::opengl_renderer::setNativeWindow,
        reset_sub_window: crate::opengl_renderer::resetSubWindow,
        remove_sub_window: crate::opengl_renderer::removeSubWindow,
        destroy_opengl_subwindow: crate::opengl_renderer::destroyOpenGLSubwindow,
        repaint_opengl_display: crate::opengl_renderer::repaintOpenGLDisplay,
    }
}

/// Load the legacy closed-source renderer
fn load_legacy_renderer() -> RendererFunctions {
    info!("Loading legacy closed-source renderer (static linking)");
    
    // Use static FFI bindings to the legacy renderer library
    // This works exactly like the original twoyi repository
    unsafe {
        RendererFunctions {
            start_opengl_renderer: std::mem::transmute(
                crate::renderer_bindings::startOpenGLRenderer as *const ()
            ),
            set_native_window: std::mem::transmute(
                crate::renderer_bindings::setNativeWindow as *const ()
            ),
            reset_sub_window: std::mem::transmute(
                crate::renderer_bindings::resetSubWindow as *const ()
            ),
            remove_sub_window: std::mem::transmute(
                crate::renderer_bindings::removeSubWindow as *const ()
            ),
            destroy_opengl_subwindow: std::mem::transmute(
                crate::renderer_bindings::destroyOpenGLSubwindow as *const ()
            ),
            repaint_opengl_display: std::mem::transmute(
                crate::renderer_bindings::repaintOpenGLDisplay as *const ()
            ),
        }
    }
}

