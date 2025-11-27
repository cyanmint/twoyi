// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use clap::Parser;
use log::{info, error, debug};
use std::fs::File;
use std::io::{Write, Read, BufReader, BufRead};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::path::PathBuf;

mod input;
mod framebuffer;

/// Default ADB port for scrcpy connections
const DEFAULT_ADB_PORT: u16 = 5555;

#[derive(Parser, Debug)]
#[command(name = "twoyi-server")]
#[command(about = r#"twoyi container server

This server runs the Android container and exposes the ADB port for scrcpy connections.
The container uses a headless redroid-based ROM that works with scrcpy for display.

Graphics are rendered via scrcpy which connects to the container's ADB daemon.
Use 'scrcpy -s <address>:<adb_port>' to connect and view the display.

The server also accepts control connections for configuration and monitoring."#, long_about = None)]
struct Args {
    /// Path to the rootfs directory
    #[arg(short, long)]
    rootfs: PathBuf,

    /// Path to the loader library (libloader.so)
    #[arg(short, long)]
    loader: Option<PathBuf>,

    /// Address and port to bind for control connections (e.g., 0.0.0.0:8765)
    #[arg(short = 'a', long, default_value = "0.0.0.0:8765")]
    bind: String,

    /// ADB port for scrcpy connections (forwarded to container's adbd)
    #[arg(long, default_value_t = DEFAULT_ADB_PORT)]
    adb_port: u16,

    /// Screen width (used by container's display)
    #[arg(long, default_value_t = 1080)]
    width: i32,

    /// Screen height (used by container's display)
    #[arg(long, default_value_t = 1920)]
    height: i32,

    /// Screen DPI (used by container's display)
    #[arg(long, default_value_t = 320)]
    dpi: i32,

    /// Verbose mode - show container output in real-time
    #[arg(short, long)]
    verbose: bool,

    /// Setup mode - start server without launching container (for manual environment setup)
    #[arg(short, long)]
    setup: bool,
}

fn main() {
    let args = Args::parse();
    
    // Set log level based on verbose flag
    let log_level = if args.verbose { "debug" } else { "info" };
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or(log_level)).init();

    info!("twoyi-server starting...");
    info!("Rootfs: {:?}", args.rootfs);
    info!("Control address: {}", args.bind);
    info!("ADB port for scrcpy: {}", args.adb_port);
    info!("Screen size: {}x{} @ {}dpi", args.width, args.height, args.dpi);
    if args.verbose {
        info!("Verbose mode: enabled");
    }
    if args.setup {
        info!("Setup mode: enabled (container will NOT be started automatically)");
        info!("You can manually set up the environment and start the container later.");
    }
    if let Some(ref loader) = args.loader {
        info!("Loader: {:?}", loader);
    }
    
    // Print scrcpy connection info
    info!("=== SCRCPY DISPLAY ===");
    info!("This server uses scrcpy for display via the container's ADB.");
    info!("Connect with: scrcpy -s <host>:{}", args.adb_port);
    info!("The container uses a headless redroid-based ROM.");
    info!("======================");

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

    // Start ADB port forwarder (for scrcpy connections)
    let adb_port = args.adb_port;
    let rootfs_for_adb = args.rootfs.clone();
    thread::spawn(move || {
        start_adb_forwarder(adb_port, &rootfs_for_adb);
    });

    // Start container process (unless in setup mode)
    let container_running = Arc::new(AtomicBool::new(true));
    
    if !args.setup {
        let container_running_clone = container_running.clone();
        let rootfs_clone = args.rootfs.clone();
        let loader_clone = args.loader.clone();
        let verbose = args.verbose;
        let width = args.width;
        let height = args.height;
        let dpi = args.dpi;
        thread::spawn(move || {
            start_container(&rootfs_clone, loader_clone.as_ref(), verbose, width, height, dpi);
            container_running_clone.store(false, Ordering::SeqCst);
        });
    } else {
        info!("Container startup skipped (setup mode).");
        info!("To start the container manually, run: cd {:?} && ./init", args.rootfs);
        if let Some(ref loader) = args.loader {
            info!("Don't forget to set: export TYLOADER={:?}", loader);
        }
    }

    // Start TCP server for client connections
    let listener = match TcpListener::bind(&args.bind) {
        Ok(l) => l,
        Err(e) => {
            error!("Failed to bind to {}: {}", args.bind, e);
            std::process::exit(1);
        }
    };

    info!("Control server listening on {}", args.bind);

    // Start framebuffer streamer (for legacy clients)
    let frame_streamer = Arc::new(framebuffer::FrameStreamer::new(
        args.width, 
        args.height, 
        &args.rootfs.to_string_lossy()
    ));
    frame_streamer.start();

    let setup_mode = args.setup;
    let adb_port_for_clients = args.adb_port;
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let width = args.width;
                let height = args.height;
                let rootfs = args.rootfs.clone();
                let streamer = frame_streamer.clone();
                thread::spawn(move || {
                    handle_client(stream, width, height, &rootfs, setup_mode, streamer, adb_port_for_clients);
                });
            }
            Err(e) => {
                error!("Error accepting connection: {}", e);
            }
        }
    }
}

/// Start the ADB port forwarder for scrcpy connections
/// This listens on the specified port and forwards connections to the container's adbd
fn start_adb_forwarder(port: u16, rootfs: &PathBuf) {
    let bind_addr = format!("0.0.0.0:{}", port);
    
    let listener = match TcpListener::bind(&bind_addr) {
        Ok(l) => l,
        Err(e) => {
            error!("Failed to bind ADB forwarder to {}: {}", bind_addr, e);
            error!("scrcpy connections will not work!");
            return;
        }
    };
    
    info!("ADB forwarder listening on {}", bind_addr);
    
    // The container's adbd listens on a Unix socket at /dev/socket/adbd
    // We need to forward TCP connections to this socket
    let adbd_socket_path = rootfs.join("dev/socket/adbd");
    
    for stream in listener.incoming() {
        match stream {
            Ok(client_stream) => {
                let socket_path = adbd_socket_path.clone();
                thread::spawn(move || {
                    forward_adb_connection(client_stream, &socket_path);
                });
            }
            Err(e) => {
                error!("Error accepting ADB connection: {}", e);
            }
        }
    }
}

/// Forward a single ADB connection to the container's adbd socket
fn forward_adb_connection(mut client: TcpStream, adbd_socket_path: &PathBuf) {
    let peer_addr = client.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "unknown".to_string());
    debug!("ADB connection from {}", peer_addr);
    
    // Try to connect to the container's adbd Unix socket
    let mut adbd_socket = match unix_socket::UnixStream::connect(adbd_socket_path) {
        Ok(s) => s,
        Err(e) => {
            debug!("Failed to connect to adbd socket at {:?}: {}", adbd_socket_path, e);
            // If the Unix socket doesn't exist, the container might expose adbd on localhost
            // Try connecting to localhost:5037 (default adb daemon port inside container)
            match TcpStream::connect("127.0.0.1:5037") {
                Ok(s) => {
                    debug!("Connected to adbd via TCP fallback");
                    forward_tcp_streams(client, s);
                    return;
                }
                Err(e2) => {
                    error!("Failed to connect to adbd: socket error: {}, TCP error: {}", e, e2);
                    return;
                }
            }
        }
    };
    
    // Forward data bidirectionally between client and adbd
    let mut client_clone = match client.try_clone() {
        Ok(c) => c,
        Err(e) => {
            error!("Failed to clone client stream: {}", e);
            return;
        }
    };
    
    let mut adbd_clone = match adbd_socket.try_clone() {
        Ok(a) => a,
        Err(e) => {
            error!("Failed to clone adbd stream: {}", e);
            return;
        }
    };
    
    // Client -> adbd
    let handle1 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match client.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if adbd_socket.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    
    // adbd -> Client
    let handle2 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match adbd_clone.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if client_clone.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    
    let _ = handle1.join();
    let _ = handle2.join();
    
    debug!("ADB connection from {} closed", peer_addr);
}

/// Forward data between two TCP streams bidirectionally
fn forward_tcp_streams(mut client: TcpStream, mut server: TcpStream) {
    let mut client_clone = match client.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    };
    
    let mut server_clone = match server.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    
    // Client -> Server
    let handle1 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match client.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if server.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    
    // Server -> Client
    let handle2 = thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match server_clone.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if client_clone.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    
    let _ = handle1.join();
    let _ = handle2.join();
}

fn start_container(rootfs: &PathBuf, loader: Option<&PathBuf>, verbose: bool, width: i32, height: i32, dpi: i32) {
    let working_dir = rootfs.to_string_lossy().to_string();
    let log_path = rootfs.parent()
        .map(|p| p.join("log.txt"))
        .unwrap_or_else(|| PathBuf::from("/tmp/twoyi_log.txt"));
    
    info!("Starting container in {}", working_dir);
    info!("Container log file: {:?}", log_path);
    
    let mut cmd = Command::new("./init");
    cmd.current_dir(&working_dir);
    
    // Set TYLOADER environment variable if loader path is provided
    if let Some(loader_path) = loader {
        let loader_str = loader_path.to_string_lossy().to_string();
        info!("Setting TYLOADER={}", loader_str);
        cmd.env("TYLOADER", loader_str);
    }
    
    // Set display configuration for redroid-based ROM
    // These are passed as kernel boot parameters style environment variables
    cmd.env("REDROID_WIDTH", width.to_string());
    cmd.env("REDROID_HEIGHT", height.to_string());
    cmd.env("REDROID_DPI", dpi.to_string());
    
    // Enable ADB over network
    cmd.env("REDROID_ADB_ENABLED", "1");
    
    if verbose {
        // In verbose mode, pipe stdout/stderr so we can log them
        cmd.stdout(Stdio::piped());
        cmd.stderr(Stdio::piped());
    } else {
        // In normal mode, redirect to log file
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
        cmd.stdout(Stdio::from(outputs));
        cmd.stderr(Stdio::from(errors));
    }
    
    let result = cmd.spawn();
    
    match result {
        Ok(mut child) => {
            info!("Container process started with PID: {:?}", child.id());
            
            if verbose {
                // In verbose mode, read and log stdout/stderr in real-time
                let stdout = child.stdout.take();
                let stderr = child.stderr.take();
                
                // Spawn thread to read stdout
                if let Some(stdout) = stdout {
                    thread::spawn(move || {
                        let reader = BufReader::new(stdout);
                        for line in reader.lines() {
                            match line {
                                Ok(line) => info!("[container stdout] {}", line),
                                Err(e) => {
                                    debug!("Error reading container stdout: {}", e);
                                    break;
                                }
                            }
                        }
                    });
                }
                
                // Spawn thread to read stderr
                if let Some(stderr) = stderr {
                    thread::spawn(move || {
                        let reader = BufReader::new(stderr);
                        for line in reader.lines() {
                            match line {
                                Ok(line) => info!("[container stderr] {}", line),
                                Err(e) => {
                                    debug!("Error reading container stderr: {}", e);
                                    break;
                                }
                            }
                        }
                    });
                }
            }
            
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

fn handle_client(mut stream: TcpStream, width: i32, height: i32, rootfs: &PathBuf, setup_mode: bool, frame_streamer: Arc<framebuffer::FrameStreamer>, adb_port: u16) {
    let peer_addr = stream.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "unknown".to_string());
    info!("Client connected from {}", peer_addr);

    // Send initial info to client
    let status = if setup_mode { "setup" } else { "running" };
    let info = serde_json::json!({
        "width": width,
        "height": height,
        "rootfs": rootfs.to_string_lossy(),
        "status": status,
        "setup_mode": setup_mode,
        "streaming": true,
        "adb_port": adb_port,
        "display_mode": "scrcpy"
    });
    
    if let Ok(info_str) = serde_json::to_string(&info) {
        let _ = stream.write_all(format!("{}\n", info_str).as_bytes());
    }

    // Clone stream for framebuffer streaming (legacy support)
    if let Ok(fb_stream) = stream.try_clone() {
        frame_streamer.add_client(fb_stream);
    }

    // Handle input events from client
    let mut reader = match stream.try_clone() {
        Ok(s) => BufReader::new(s),
        Err(e) => {
            error!("Failed to clone stream for reading: {}", e);
            return;
        }
    };
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
