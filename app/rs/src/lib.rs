// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Unified twoyi library that works as both:
//! - A JNI library (cdylib) for Android app use
//! - A standalone server (via twoyi-server binary)

#[cfg(target_os = "android")]
use jni::objects::JValue;
#[cfg(target_os = "android")]
use jni::sys::{jclass, jfloat, jint, jobject, JNI_ERR, jstring};
#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use jni::{JavaVM, NativeMethod};
#[cfg(target_os = "android")]
use log::{error, info, debug, Level};
#[cfg(target_os = "android")]
use std::ffi::c_void;
#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(target_os = "android")]
use std::thread;
#[cfg(target_os = "android")]
use std::fs::File;
#[cfg(target_os = "android")]
use std::process::{Command, Stdio};

// Shared modules (used by both JNI and server)
pub mod input;
pub mod framebuffer;
pub mod gralloc;
pub mod rom_patcher;
pub mod server;
pub mod renderer;
pub mod renderer_bindings;

// ============================================================================
// JNI-specific code (Android only)
// ============================================================================

#[cfg(target_os = "android")]
macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

#[cfg(target_os = "android")]
static RENDERER_STARTED: AtomicBool = AtomicBool::new(false);

#[cfg(target_os = "android")]
#[no_mangle]
pub fn renderer_init(
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

#[cfg(target_os = "android")]
#[no_mangle]
pub fn renderer_reset_window(
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

#[cfg(target_os = "android")]
#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        renderer_bindings::removeSubWindow(window as *mut c_void);
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub fn handle_touch(env: JNIEnv, _clz: jclass, event: jobject) {
    // TODO: cache the field id.
    let ptr = env.get_field(event, "mNativePtr", "J").unwrap();

    if let JValue::Long(p) = ptr {
        let ev = unsafe {
            let nonptr =
            std::ptr::NonNull::new(std::mem::transmute::<i64, *mut ndk_sys::AInputEvent>(p))
                .unwrap();
            ndk::event::MotionEvent::from_ptr(nonptr)
        };
        handle_touch_from_motion_event(ev)
    }
}

/// Handle touch from Android MotionEvent (JNI path)
#[cfg(target_os = "android")]
fn handle_touch_from_motion_event(ev: ndk::event::MotionEvent) {
    use ndk::event::MotionAction;
    
    let action = ev.action();
    let pointer_index = ev.pointer_index();
    let pointer = ev.pointer_at_index(pointer_index);
    let pointer_id = pointer.pointer_id();
    let pressure = pointer.pressure();
    let x = pointer.x();
    let y = pointer.y();

    // Convert MotionAction to our action codes
    let action_code = match action {
        MotionAction::Down => 0,
        MotionAction::Up => 1,
        MotionAction::Move => 2,
        MotionAction::Cancel => 3,
        MotionAction::PointerDown => 5,
        MotionAction::PointerUp => 6,
        _ => return,
    };

    input::handle_touch_event(action_code, pointer_id, x, y, pressure);
}

#[cfg(target_os = "android")]
pub fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

#[cfg(target_os = "android")]
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

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
unsafe fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    debug!("JNI_OnLoad");

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

    register_natives(&jvm, class_name, jni_methods.as_ref())
}
