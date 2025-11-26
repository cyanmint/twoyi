// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use clap::Parser;
use log::{info, error};
use std::fs::File;
use std::io::{Write, BufReader, BufRead};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::path::PathBuf;

mod input;

#[derive(Parser, Debug)]
#[command(name = "twoyi-server")]
#[command(about = "twoyi container server", long_about = None)]
struct Args {
    /// Path to the rootfs directory
    #[arg(short, long)]
    rootfs: PathBuf,

    /// Address and port to bind on (e.g., 0.0.0.0:8765)
    #[arg(short, long, default_value = "0.0.0.0:8765")]
    bind: String,

    /// Screen width
    #[arg(long, default_value_t = 1080)]
    width: i32,

    /// Screen height
    #[arg(long, default_value_t = 1920)]
    height: i32,
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args = Args::parse();

    info!("twoyi-server starting...");
    info!("Rootfs: {:?}", args.rootfs);
    info!("Bind address: {}", args.bind);
    info!("Screen size: {}x{}", args.width, args.height);

    // Validate rootfs exists
    if !args.rootfs.exists() {
        error!("Rootfs directory does not exist: {:?}", args.rootfs);
        std::process::exit(1);
    }

    let init_path = args.rootfs.join("init");
    if !init_path.exists() {
        error!("init binary not found at: {:?}", init_path);
        std::process::exit(1);
    }

    // Start input system
    let rootfs_str = args.rootfs.to_string_lossy().to_string();
    input::start_input_system(args.width, args.height, &rootfs_str);

    // Start container process
    let container_running = Arc::new(AtomicBool::new(true));
    let container_running_clone = container_running.clone();
    
    let rootfs_clone = args.rootfs.clone();
    thread::spawn(move || {
        start_container(&rootfs_clone);
        container_running_clone.store(false, Ordering::SeqCst);
    });

    // Start TCP server for client connections
    let listener = match TcpListener::bind(&args.bind) {
        Ok(l) => l,
        Err(e) => {
            error!("Failed to bind to {}: {}", args.bind, e);
            std::process::exit(1);
        }
    };

    info!("Server listening on {}", args.bind);

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let width = args.width;
                let height = args.height;
                let rootfs = args.rootfs.clone();
                thread::spawn(move || {
                    handle_client(stream, width, height, &rootfs);
                });
            }
            Err(e) => {
                error!("Error accepting connection: {}", e);
            }
        }
    }
}

fn start_container(rootfs: &PathBuf) {
    let working_dir = rootfs.to_string_lossy().to_string();
    let log_path = rootfs.parent()
        .map(|p| p.join("log.txt"))
        .unwrap_or_else(|| PathBuf::from("/tmp/twoyi_log.txt"));
    
    info!("Starting container in {}", working_dir);
    
    let outputs = match File::create(&log_path) {
        Ok(f) => f,
        Err(e) => {
            error!("Failed to create log file: {}", e);
            return;
        }
    };
    let errors = match outputs.try_clone() {
        Ok(f) => f,
        Err(e) => {
            error!("Failed to clone log file handle: {}", e);
            return;
        }
    };
    
    let result = Command::new("./init")
        .current_dir(&working_dir)
        .stdout(Stdio::from(outputs))
        .stderr(Stdio::from(errors))
        .spawn();
    
    match result {
        Ok(mut child) => {
            info!("Container process started with PID: {:?}", child.id());
            match child.wait() {
                Ok(status) => info!("Container exited with status: {}", status),
                Err(e) => error!("Error waiting for container: {}", e),
            }
        }
        Err(e) => {
            error!("Failed to start container: {}", e);
        }
    }
}

fn handle_client(mut stream: TcpStream, width: i32, height: i32, rootfs: &PathBuf) {
    let peer_addr = stream.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "unknown".to_string());
    info!("Client connected from {}", peer_addr);

    // Send initial info to client
    let info = serde_json::json!({
        "width": width,
        "height": height,
        "rootfs": rootfs.to_string_lossy(),
        "status": "running"
    });
    
    if let Ok(info_str) = serde_json::to_string(&info) {
        let _ = stream.write_all(format!("{}\n", info_str).as_bytes());
    }

    // Handle input events from client
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut line = String::new();
    
    loop {
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => {
                info!("Client {} disconnected", peer_addr);
                break;
            }
            Ok(_) => {
                if let Ok(event) = serde_json::from_str::<serde_json::Value>(&line) {
                    handle_input_event(&event);
                }
            }
            Err(e) => {
                error!("Error reading from client {}: {}", peer_addr, e);
                break;
            }
        }
    }
}

fn handle_input_event(event: &serde_json::Value) {
    if let Some(event_type) = event.get("type").and_then(|v| v.as_str()) {
        match event_type {
            "touch" => {
                let action = event.get("action").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
                let pointer_id = event.get("pointer_id").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
                let x = event.get("x").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                let y = event.get("y").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                let pressure = event.get("pressure").and_then(|v| v.as_f64()).unwrap_or(1.0) as f32;
                
                input::handle_touch_event(action, pointer_id, x, y, pressure);
            }
            "key" => {
                let keycode = event.get("keycode").and_then(|v| v.as_i64()).unwrap_or(0) as i32;
                input::send_key_code(keycode);
            }
            _ => {}
        }
    }
}
