// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

//! Dynamic Renderer Loader
//! 
//! This module provides the ability to switch between the new open-source
//! Rust renderer and the legacy closed-source renderer at runtime.

use log::{info, warn};
use std::ffi::c_void;
use std::sync::{Mutex, Once};

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
pub struct RendererFunctions {
    pub start_opengl_renderer: StartOpenGLRendererFn,
    pub set_native_window: SetNativeWindowFn,
    pub reset_sub_window: ResetSubWindowFn,
    pub remove_sub_window: RemoveSubWindowFn,
    pub destroy_opengl_subwindow: DestroyOpenGLSubwindowFn,
    pub repaint_opengl_display: RepaintOpenGLDisplayFn,
}

static INIT: Once = Once::new();
static RENDERER_FUNCTIONS: Mutex<Option<RendererFunctions>> = Mutex::new(None);
static mut LEGACY_LIB_HANDLE: Option<*mut c_void> = None;

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
        
        *RENDERER_FUNCTIONS.lock().unwrap() = Some(functions);
    });
}

/// Get the active renderer functions
pub fn get_renderer_functions() -> RendererFunctions {
    let guard = RENDERER_FUNCTIONS.lock().unwrap();
    match &*guard {
        Some(funcs) => RendererFunctions {
            start_opengl_renderer: funcs.start_opengl_renderer,
            set_native_window: funcs.set_native_window,
            reset_sub_window: funcs.reset_sub_window,
            remove_sub_window: funcs.remove_sub_window,
            destroy_opengl_subwindow: funcs.destroy_opengl_subwindow,
            repaint_opengl_display: funcs.repaint_opengl_display,
        },
        None => {
            warn!("Renderer not initialized, using new renderer as fallback");
            load_new_renderer()
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
    info!("Loading legacy closed-source renderer");
    
    unsafe {
        // Load the legacy library
        let lib_path = b"/data/data/io.twoyi/lib/libOpenglRender_legacy.so\0";
        let handle = libc::dlopen(lib_path.as_ptr() as *const i8, libc::RTLD_NOW | libc::RTLD_LOCAL);
        
        if handle.is_null() {
            let error = libc::dlerror();
            let error_str = if !error.is_null() {
                std::ffi::CStr::from_ptr(error).to_string_lossy()
            } else {
                "Unknown error".into()
            };
            warn!("Failed to load legacy renderer: {}, falling back to new renderer", error_str);
            return load_new_renderer();
        }
        
        LEGACY_LIB_HANDLE = Some(handle);
        
        // Load function pointers
        let start_fn = load_symbol::<StartOpenGLRendererFn>(handle, b"startOpenGLRenderer\0");
        let set_window_fn = load_symbol::<SetNativeWindowFn>(handle, b"setNativeWindow\0");
        let reset_fn = load_symbol::<ResetSubWindowFn>(handle, b"resetSubWindow\0");
        let remove_fn = load_symbol::<RemoveSubWindowFn>(handle, b"removeSubWindow\0");
        let destroy_fn = load_symbol::<DestroyOpenGLSubwindowFn>(handle, b"destroyOpenGLSubwindow\0");
        let repaint_fn = load_symbol::<RepaintOpenGLDisplayFn>(handle, b"repaintOpenGLDisplay\0");
        
        if let (Some(start), Some(set_win), Some(reset), Some(remove), Some(destroy), Some(repaint)) =
            (start_fn, set_window_fn, reset_fn, remove_fn, destroy_fn, repaint_fn) {
            info!("Successfully loaded all legacy renderer functions");
            RendererFunctions {
                start_opengl_renderer: start,
                set_native_window: set_win,
                reset_sub_window: reset,
                remove_sub_window: remove,
                destroy_opengl_subwindow: destroy,
                repaint_opengl_display: repaint,
            }
        } else {
            warn!("Failed to load some legacy renderer functions, falling back to new renderer");
            load_new_renderer()
        }
    }
}

/// Load a symbol from a dynamic library
unsafe fn load_symbol<T>(handle: *mut c_void, name: &[u8]) -> Option<T> {
    let sym = libc::dlsym(handle, name.as_ptr() as *const i8);
    if sym.is_null() {
        let error = libc::dlerror();
        let error_str = if !error.is_null() {
            std::ffi::CStr::from_ptr(error).to_string_lossy()
        } else {
            "Unknown error".into()
        };
        warn!("Failed to load symbol {}: {}", 
              std::str::from_utf8(name).unwrap_or("invalid"), error_str);
        None
    } else {
        Some(std::mem::transmute_copy(&sym))
    }
}

/// Cleanup function to unload legacy library (call on shutdown)
pub fn cleanup_renderer_loader() {
    unsafe {
        if let Some(handle) = LEGACY_LIB_HANDLE.take() {
            info!("Unloading legacy renderer library");
            libc::dlclose(handle);
        }
    }
}
