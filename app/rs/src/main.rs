// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! libtwoyi - twoyi container server
//!
//! This is a PIE (Position Independent Executable) that works both as:
//! - A JNI library when loaded by Android app (via System.loadLibrary)
//! - An executable when run directly (./libtwoyi.so -r rootfs)
//!
//! Similar to libanbox.so in the ananbox project.

use jni::objects::JValue;
use jni::sys::{jclass, jfloat, jint, jobject, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, Level, debug};
use ndk_sys;
use std::ffi::c_void;

use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use android_logger::Config;

use std::fs::File;
use std::process::{Command, Stdio};

// Original renderer modules
mod input;
mod renderer_bindings;

// Server modules
mod framebuffer;
mod gralloc;
mod rom_patcher;
mod server;
mod cli;
mod server_jni;

// Re-export server functionality for potential future use
pub use server::{ServerConfig, TwoyiServer};

/// ## Examples
/// ```
/// let method:NativeMethod = jni_method!(native_method, "(Ljava/lang/String;)V");
/// ```
macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

static RENDERER_STARTED: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "C" fn renderer_init(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    loader: jstring,
    rootfs: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
) {
    debug!("renderer_init");
    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };

    let window = match std::ptr::NonNull::new(window) {
        Some(x) => x,
        None => {
            error!("ANativeWindow_fromSurface was null!");
            return;
        }
    };

    let window = unsafe { ndk::native_window::NativeWindow::from_ptr(window) };

    let width = window.width();
    let height = window.height();

    info!(
        "renderer_init width: {}, height: {}, fps: {}",
        width, height, fps
    );

    // Get the rootfs path from the Java string
    let rootfs_path: String = match env.get_string(rootfs.into()) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get rootfs string: {:?}", e);
            return;
        }
    };
    info!("rootfs path: {}", rootfs_path);
    
    if RENDERER_STARTED.compare_exchange(false, true, 
        Ordering::Acquire, Ordering::Relaxed).is_err() {
        let win = window.ptr().as_ptr() as *mut c_void;
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(win, 0, 0, width, height, width, height, 1.0, 0.0);
        }
    } else {
        input::start_input_system(width, height, &rootfs_path);

        thread::spawn(move || {
            let win = window.ptr().as_ptr() as *mut c_void;
            info!("win: {:#?}", win);
            unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    width,
                    height,
                    xdpi as i32,
                    ydpi as i32,
                    fps as i32,
                );
            }
        });

        let loader_path: String = env.get_string(loader.into()).unwrap().into();
        let working_dir = rootfs_path.clone();
        // Log file should be in parent directory of rootfs
        let log_path = format!("{}/log.txt", std::path::Path::new(&rootfs_path).parent().unwrap_or(std::path::Path::new("/data/data/io.twoyi")).display());
        info!("starting container in {}, log to {}", working_dir, log_path);
        let outputs = File::create(&log_path).unwrap();
        let errors = outputs.try_clone().unwrap();
        let _ = Command::new("./init")
            .current_dir(&working_dir)
            .env("TYLOADER", loader_path)
            .stdout(Stdio::from(outputs))
            .stderr(Stdio::from(errors))
            .spawn();
    }
}

#[no_mangle]
pub extern "C" fn renderer_reset_window(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    _top: jint,
    _left: jint,
    _width: jint,
    _height: jint,
) {
    debug!("reset_window");
    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        renderer_bindings::resetSubWindow(window as *mut c_void, 0, 0, _width, _height, _width, _height, 1.0, 0.0);
    }
}

#[no_mangle]
pub extern "C" fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        renderer_bindings::removeSubWindow(window as *mut c_void);
    }
}

#[no_mangle]
pub extern "C" fn handle_touch(env: JNIEnv, _clz: jclass, event: jobject) {
    // TODO: cache the field id.
    let ptr = env.get_field(event, "mNativePtr", "J").unwrap();

    if let JValue::Long(p) = ptr {
        let ev = unsafe {
            let nonptr =
            std::ptr::NonNull::new(std::mem::transmute::<i64, *mut ndk_sys::AInputEvent>(p))
                .unwrap();
            ndk::event::MotionEvent::from_ptr(nonptr)
        };
        input::handle_touch(ev)
    }
}

#[no_mangle]
pub extern "C" fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = jvm.get_env().unwrap();
    let jni_version = env.get_version().unwrap();
    let version: jint = jni_version.into();

    debug!("JNI Version : {:#?} ", jni_version);

    let clazz = match env.find_class(class_name) {
        Ok(clazz) => clazz,
        Err(e) => {
            error!("java class not found : {:?}", e);
            return JNI_ERR;
        }
    };
    debug!("clazz: {:#?}", clazz);

    let result = env.register_native_methods(clazz, &methods);

    if result.is_ok() {
        debug!("register_natives : succeed");
        version
    } else {
        error!("register_natives : failed ");
        JNI_ERR
    }
}

/// JNI_OnLoad - called when the library is loaded by System.loadLibrary()
#[no_mangle]
#[allow(non_snake_case)]
pub extern "C" fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    debug!("JNI_OnLoad");
    
    // Initialize the renderer library (loads libOpenglRender.so dynamically)
    if renderer_bindings::init_renderer() {
        info!("OpenGL renderer initialized");
    } else {
        info!("OpenGL renderer not available (this is OK for server-only mode)");
    }

    let class_name: &str = "io/twoyi/Renderer";
    let jni_methods = [
        jni_method!(init, renderer_init, "(Landroid/view/Surface;Ljava/lang/String;Ljava/lang/String;FFI)V"),
        jni_method!(
            resetWindow,
            renderer_reset_window,
            "(Landroid/view/Surface;IIII)V"
        ),
        jni_method!(
            removeWindow,
            renderer_remove_window,
            "(Landroid/view/Surface;)V"
        ),
        jni_method!(handleTouch, handle_touch, "(Landroid/view/MotionEvent;)V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
    ];

    // Register renderer methods
    let result = unsafe { register_natives(&jvm, class_name, jni_methods.as_ref()) };
    
    // Also register server JNI methods
    server_jni::register_server_natives(&jvm);
    
    result
}

/// Main entry point for standalone execution
/// Usage: ./libtwoyi.so -r $(realpath rootfs)
fn main() {
    let args: Vec<String> = std::env::args().collect();
    
    // Convert args to C-style CStrings
    // We need to keep c_args alive for the duration of run_cli
    let c_args: Vec<std::ffi::CString> = args
        .iter()
        .filter_map(|arg| {
            match std::ffi::CString::new(arg.as_str()) {
                Ok(cstr) => Some(cstr),
                Err(e) => {
                    eprintln!("Warning: skipping argument with invalid characters: {}", e);
                    None
                }
            }
        })
        .collect();
    
    // Create pointer array - c_args must remain in scope
    let c_argv: Vec<*const libc::c_char> = c_args.iter().map(|s| s.as_ptr()).collect();
    
    let exit_code = cli::run_cli(c_argv.len() as libc::c_int, c_argv.as_ptr());
    
    // c_args is still alive here, so pointers in c_argv are valid
    drop(c_args);
    
    std::process::exit(exit_code);
}
