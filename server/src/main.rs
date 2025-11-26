// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use clap::Parser;
use log::{info, error, debug};
use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

mod input;
mod protocol;

use protocol::{ServerMessage, ClientMessage};

/// Twoyi standalone server for container management
#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Path to the container rootfs directory
    #[arg(short, long)]
    rootfs: PathBuf,

    /// Address and port to listen on (e.g., 0.0.0.0:9876)
    #[arg(short, long, default_value = "0.0.0.0:9876")]
    listen: String,

    /// Screen width
    #[arg(long, default_value = "1080")]
    width: i32,

    /// Screen height
    #[arg(long, default_value = "1920")]
    height: i32,

    /// Extract rootfs from a .7z archive before starting
    /// If specified, the rootfs.7z will be extracted to the rootfs directory
    #[arg(long)]
    extract_rootfs: Option<PathBuf>,
}

static CONTAINER_STARTED: AtomicBool = AtomicBool::new(false);

fn extract_rootfs(archive_path: &PathBuf, target_dir: &PathBuf) -> Result<(), String> {
    info!("Extracting rootfs from {:?} to {:?}", archive_path, target_dir);

    if !archive_path.exists() {
        return Err(format!("Archive file not found: {:?}", archive_path));
    }

    // Create target directory if it doesn't exist
    std::fs::create_dir_all(target_dir)
        .map_err(|e| format!("Failed to create target directory: {}", e))?;

    // Extract using sevenz-rust
    sevenz_rust::decompress_file(archive_path, target_dir)
        .map_err(|e| format!("Failed to extract archive: {}", e))?;

    info!("Rootfs extraction completed successfully");
    Ok(())
}

fn start_container(rootfs: &PathBuf, width: i32, height: i32) -> Result<(), String> {
    if CONTAINER_STARTED.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
        return Err("Container already started".to_string());
    }

    let working_dir = rootfs.to_string_lossy().to_string();
    let log_path = rootfs.join("../log.txt");
    
    info!("Starting container in: {}", working_dir);
    info!("Log file: {:?}", log_path);

    // Start input system
    input::start_input_system(rootfs, width, height);

    let outputs = File::create(&log_path).map_err(|e| format!("Failed to create log file: {}", e))?;
    let errors = outputs.try_clone().map_err(|e| format!("Failed to clone log file: {}", e))?;
    
    let result = Command::new("./init")
        .current_dir(&working_dir)
        .stdout(Stdio::from(outputs))
        .stderr(Stdio::from(errors))
        .spawn();

    match result {
        Ok(child) => {
            info!("Container started with PID: {}", child.id());
            Ok(())
        }
        Err(e) => {
            CONTAINER_STARTED.store(false, Ordering::Release);
            Err(format!("Failed to start container: {}", e))
        }
    }
}

fn handle_client(mut stream: TcpStream, rootfs: Arc<PathBuf>, width: i32, height: i32) {
    let peer_addr = stream.peer_addr().ok();
    info!("Client connected: {:?}", peer_addr);

    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut line = String::new();

    loop {
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => {
                info!("Client disconnected: {:?}", peer_addr);
                break;
            }
            Ok(_) => {
                let line = line.trim();
                debug!("Received: {}", line);

                match serde_json::from_str::<ClientMessage>(line) {
                    Ok(msg) => {
                        let response = handle_message(msg, &rootfs, width, height);
                        let response_str = serde_json::to_string(&response).unwrap() + "\n";
                        if let Err(e) = stream.write_all(response_str.as_bytes()) {
                            error!("Failed to send response: {}", e);
                            break;
                        }
                    }
                    Err(e) => {
                        error!("Failed to parse message: {}", e);
                        let response = ServerMessage::Error {
                            message: format!("Invalid message format: {}", e),
                        };
                        let response_str = serde_json::to_string(&response).unwrap() + "\n";
                        let _ = stream.write_all(response_str.as_bytes());
                    }
                }
            }
            Err(e) => {
                error!("Error reading from client: {}", e);
                break;
            }
        }
    }
}

fn handle_message(msg: ClientMessage, rootfs: &PathBuf, width: i32, height: i32) -> ServerMessage {
    match msg {
        ClientMessage::StartContainer => {
            match start_container(rootfs, width, height) {
                Ok(()) => ServerMessage::ContainerStarted,
                Err(e) => ServerMessage::Error { message: e },
            }
        }
        ClientMessage::GetStatus => {
            ServerMessage::Status {
                container_running: CONTAINER_STARTED.load(Ordering::Relaxed),
                rootfs_path: rootfs.to_string_lossy().to_string(),
                width,
                height,
            }
        }
        ClientMessage::TouchEvent { action, x, y, pointer_id, pressure } => {
            input::handle_touch_event(action, x, y, pointer_id, pressure);
            ServerMessage::Ok
        }
        ClientMessage::KeyEvent { keycode, pressed } => {
            input::send_key_code(keycode, pressed);
            ServerMessage::Ok
        }
        ClientMessage::Ping => ServerMessage::Pong,
    }
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args = Args::parse();

    info!("Twoyi Server starting...");
    info!("Rootfs path: {:?}", args.rootfs);
    info!("Listen address: {}", args.listen);
    info!("Screen size: {}x{}", args.width, args.height);

    // Extract rootfs if requested
    if let Some(archive_path) = &args.extract_rootfs {
        info!("Extracting rootfs from archive...");
        if let Err(e) = extract_rootfs(archive_path, &args.rootfs) {
            error!("Failed to extract rootfs: {}", e);
            std::process::exit(1);
        }
    }

    // Validate rootfs path
    if !args.rootfs.exists() {
        error!("Rootfs path does not exist: {:?}", args.rootfs);
        std::process::exit(1);
    }

    let init_path = args.rootfs.join("init");
    if !init_path.exists() {
        error!("Init binary not found in rootfs: {:?}", init_path);
        std::process::exit(1);
    }

    // Ensure required directories exist
    let dev_dir = args.rootfs.join("dev");
    let _ = std::fs::create_dir_all(dev_dir.join("input"));
    let _ = std::fs::create_dir_all(dev_dir.join("socket"));
    let _ = std::fs::create_dir_all(dev_dir.join("maps"));

    let listener = TcpListener::bind(&args.listen).expect("Failed to bind to address");
    info!("Server listening on {}", args.listen);

    let rootfs = Arc::new(args.rootfs.clone());
    let width = args.width;
    let height = args.height;

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let rootfs = Arc::clone(&rootfs);
                thread::spawn(move || {
                    handle_client(stream, rootfs, width, height);
                });
            }
            Err(e) => {
                error!("Error accepting connection: {}", e);
            }
        }
    }
}
