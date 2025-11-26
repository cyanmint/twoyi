// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use libc::{c_char, c_int, clock_gettime, timeval, CLOCK_MONOTONIC};
use std::mem;
use std::thread;
use std::io::Write;
use uinput_sys::*;

use std::sync::mpsc::{channel, Sender};
use std::sync::Mutex;
use once_cell::sync::Lazy;

use log::info;

const FF_MAX: u16 = 0x7f;

const TOUCH_DEVICE_NAME: &str = "vtouch";
const TOUCH_DEVICE_UNIQUE_ID: &str = "<vtouch 0>";

const KEY_DEVICE_NAME: &str = "vkey";
const KEY_DEVICE_UNIQUE_ID: &str = "<keyboard 0>";

// Touch actions (matching Android MotionEvent)
const ACTION_DOWN: i32 = 0;
const ACTION_UP: i32 = 1;
const ACTION_MOVE: i32 = 2;
const ACTION_CANCEL: i32 = 3;
const ACTION_POINTER_DOWN: i32 = 5;
const ACTION_POINTER_UP: i32 = 6;

#[repr(C)]
#[derive(Clone, Copy)]
struct DeviceInfo {
    name: [c_char; 80],
    driver_version: c_int,
    id: input_id,
    physical_location: [c_char; 80],
    unique_id: [c_char; 80],
    key_bitmask: [u8; (KEY_MAX as usize + 1) / 8],
    abs_bitmask: [u8; (ABS_MAX as usize + 1) / 8],
    rel_bitmask: [u8; (REL_MAX as usize + 1) / 8],
    sw_bitmask: [u8; (SW_MAX as usize + 1) / 8],
    led_bitmask: [u8; (LED_MAX as usize + 1) / 8],
    ff_bitmask: [u8; (FF_MAX as usize + 1) / 8],
    prop_bitmask: [u8; (INPUT_PROP_MAX as usize + 1) / 8],
    abs_max: [u32; ABS_CNT as usize],
    abs_min: [u32; ABS_CNT as usize],
}

unsafe fn any_as_u8_slice<T: Sized>(p: &T) -> &[u8] {
    ::std::slice::from_raw_parts((p as *const T) as *const u8, ::std::mem::size_of::<T>())
}

fn copy_to_cstr<const COUNT: usize>(data: &str, arr: &mut [c_char; COUNT]) {
    let cstr = std::ffi::CString::new(data).expect("create cstring failed");
    let bytes = cstr.as_bytes_with_nul();
    let mut len = bytes.len();
    if len >= COUNT {
        len = COUNT;
    }
    for i in 0..len {
        arr[i] = bytes[i] as c_char;
    }
}

const MAX_POINTERS: usize = 5;

static INPUT_SENDER: Lazy<Mutex<Option<Sender<input_event>>>> = Lazy::new(|| Mutex::new(None));
static KEY_SENDER: Lazy<Mutex<Option<Sender<input_event>>>> = Lazy::new(|| Mutex::new(None));
static G_INPUT_MT: Lazy<Mutex<[i32; MAX_POINTERS]>> = Lazy::new(|| Mutex::new([0i32; MAX_POINTERS]));

static TOUCH_PATH: Lazy<Mutex<String>> = Lazy::new(|| Mutex::new(String::new()));
static KEY_PATH: Lazy<Mutex<String>> = Lazy::new(|| Mutex::new(String::new()));

pub fn start_input_system(width: i32, height: i32, rootfs_path: &str) {
    // Set paths based on rootfs
    *TOUCH_PATH.lock().unwrap() = format!("{}/dev/input/touch", rootfs_path);
    *KEY_PATH.lock().unwrap() = format!("{}/dev/input/key0", rootfs_path);

    // Ensure input directories exist
    let input_dir = format!("{}/dev/input", rootfs_path);
    let _ = std::fs::create_dir_all(&input_dir);

    let touch_path = TOUCH_PATH.lock().unwrap().clone();
    let key_path = KEY_PATH.lock().unwrap().clone();

    let w = width;
    let h = height;
    let tp = touch_path.clone();
    thread::spawn(move || {
        touch_server(w, h, &tp);
    });
    
    thread::spawn(move || {
        key_server(&key_path);
    });
}

pub fn input_event_write(
    tx: &Sender<input_event>,
    kind: i32,
    code: i32,
    val: i32,
) {
    let mut tp = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let _ = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut tp) };
    let tv = timeval {
        tv_sec: tp.tv_sec,
        tv_usec: tp.tv_nsec / 1000,
    };

    let ev = input_event {
        kind: kind as u16,
        code: code as u16,
        value: val,
        time: tv,
    };
    let _ = tx.send(ev);
}

pub fn handle_touch_event(action: i32, pointer_id: i32, x: f32, y: f32, pressure: f32) {
    let opt = INPUT_SENDER.lock().unwrap();
    if let Some(ref fd) = *opt {
        match action {
            ACTION_DOWN | ACTION_POINTER_DOWN => {
                let mut mt = G_INPUT_MT.lock().unwrap();
                mt[pointer_id as usize] = 1;

                input_event_write(fd, EV_ABS, ABS_MT_SLOT, pointer_id);
                input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, pointer_id + 1);

                if pointer_id == 0 {
                    input_event_write(fd, EV_KEY, BTN_TOUCH, 108);
                    input_event_write(fd, EV_KEY, BTN_TOOL_FINGER, 108);
                }

                input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, x as i32);
                input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, y as i32);
                input_event_write(fd, EV_ABS, ABS_MT_PRESSURE, pressure as i32);
                input_event_write(fd, EV_SYN, SYN_REPORT, SYN_REPORT);
            }
            ACTION_UP => {
                let mut mt = G_INPUT_MT.lock().unwrap();
                for index in 0..MAX_POINTERS {
                    if mt[index] != 0 {
                        mt[index] = 0;
                        input_event_write(fd, EV_ABS, ABS_MT_SLOT, index as i32);
                        input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                        input_event_write(fd, EV_SYN, SYN_REPORT, SYN_REPORT);
                    }
                }
            }
            ACTION_MOVE => {
                let mt = G_INPUT_MT.lock().unwrap();
                if mt[pointer_id as usize] != 0 {
                    input_event_write(fd, EV_ABS, ABS_MT_SLOT, pointer_id);
                    input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, x as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, y as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_PRESSURE, pressure as i32);
                    input_event_write(fd, EV_SYN, SYN_REPORT, SYN_REPORT);
                }
            }
            ACTION_CANCEL | ACTION_POINTER_UP => {
                let mut mt = G_INPUT_MT.lock().unwrap();
                if mt[pointer_id as usize] == 0 {
                    return;
                }

                mt[pointer_id as usize] = 0;
                input_event_write(fd, EV_ABS, ABS_MT_SLOT, pointer_id);
                input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                input_event_write(fd, EV_SYN, SYN_REPORT, SYN_REPORT);
            }
            _ => {}
        }
    }
}

fn generate_touch_device(width: i32, height: i32, touch_path: &str) -> DeviceInfo {
    let iid = input_id {
        product: 0x1,
        version: 0,
        vendor: 0,
        bustype: 0,
    };

    let mut info = DeviceInfo {
        name: unsafe { mem::zeroed() },
        driver_version: 0x1,
        id: iid,
        physical_location: unsafe { mem::zeroed() },
        unique_id: unsafe { mem::zeroed() },
        key_bitmask: unsafe { mem::zeroed() },
        abs_bitmask: unsafe { mem::zeroed() },
        rel_bitmask: unsafe { mem::zeroed() },
        sw_bitmask: unsafe { mem::zeroed() },
        led_bitmask: unsafe { mem::zeroed() },
        ff_bitmask: unsafe { mem::zeroed() },
        prop_bitmask: unsafe { mem::zeroed() },
        abs_max: unsafe { mem::zeroed() },
        abs_min: unsafe { mem::zeroed() },
    };

    copy_to_cstr(TOUCH_DEVICE_NAME, &mut info.name);
    copy_to_cstr(touch_path, &mut info.physical_location);
    copy_to_cstr(TOUCH_DEVICE_UNIQUE_ID, &mut info.unique_id);

    info.prop_bitmask[0] = INPUT_PROP_BUTTONPAD as u8;

    info.abs_bitmask[ABS_RZ as usize] = 0x80;
    info.abs_bitmask[ABS_THROTTLE as usize] = 0x60;
    info.abs_bitmask[ABS_RUDDER as usize] = 0x2;

    info.abs_min[ABS_MT_POSITION_X as usize] = 0;
    info.abs_max[ABS_MT_POSITION_X as usize] = width as u32;

    info.abs_min[ABS_MT_POSITION_Y as usize] = 0;
    info.abs_max[ABS_MT_POSITION_Y as usize] = height as u32;

    info.abs_min[ABS_MT_TOUCH_MAJOR as usize] = 0;
    info.abs_min[ABS_MT_TOUCH_MINOR as usize] = 15;

    info.abs_min[ABS_MT_SLOT as usize] = 4;
    info.abs_min[ABS_MT_PRESSURE as usize] = 0;
    info.abs_max[ABS_MT_PRESSURE as usize] = 80;

    info
}

fn touch_server(width: i32, height: i32, touch_path: &str) {
    let device = generate_touch_device(width, height, touch_path);
    let _ = std::fs::remove_file(touch_path);
    
    let listener = match unix_socket::UnixListener::bind(touch_path) {
        Ok(l) => l,
        Err(e) => {
            log::error!("Failed to bind touch socket at {}: {}", touch_path, e);
            return;
        }
    };
    
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("Touch client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                let (tx, rx) = channel::<input_event>();
                *INPUT_SENDER.lock().unwrap() = Some(tx);

                thread::spawn(move || loop {
                    let ret = rx.recv();
                    if let Ok(ev) = ret {
                        let data = unsafe { any_as_u8_slice(&ev) };
                        let _ = stream.write_all(data);
                    }
                });
            }
            Err(_) => {
                info!("Touch server error happened!");
                break;
            }
        }
    }

    info!("Drop touch listener!");
}

fn generate_key_device(key_path: &str) -> DeviceInfo {
    let mut info: DeviceInfo = unsafe { std::mem::MaybeUninit::zeroed().assume_init() };

    info.driver_version = 0x1;
    info.id.product = 0x1;

    copy_to_cstr(KEY_DEVICE_NAME, &mut info.name);
    copy_to_cstr(key_path, &mut info.physical_location);
    copy_to_cstr(KEY_DEVICE_UNIQUE_ID, &mut info.unique_id);

    info.key_bitmask[14] = 0x1C;

    info
}

pub fn send_key_code(keycode: i32) {
    if let Some(ref tx) = *KEY_SENDER.lock().unwrap() {
        input_event_write(tx, EV_KEY, keycode, 1);
        input_event_write(tx, EV_SYN, SYN_REPORT, SYN_REPORT);
        input_event_write(tx, EV_KEY, keycode, 0);
    }
}

fn key_server(key_path: &str) {
    let device = generate_key_device(key_path);
    let _ = std::fs::remove_file(key_path);
    
    let listener = match unix_socket::UnixListener::bind(key_path) {
        Ok(l) => l,
        Err(e) => {
            log::error!("Failed to bind key socket at {}: {}", key_path, e);
            return;
        }
    };
    
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("Key client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                let (tx, rx) = channel::<input_event>();
                *KEY_SENDER.lock().unwrap() = Some(tx);

                thread::spawn(move || loop {
                    let ret = rx.recv();
                    if let Ok(ev) = ret {
                        let data = unsafe { any_as_u8_slice(&ev) };
                        if stream.write_all(data).is_err() {
                            break; // Exit on write failure
                        }
                    } else {
                        break; // Exit on channel close/error
                    }
                });
            }
            Err(_) => {
                info!("Key server error happened!");
                break;
            }
        }
    }
}
