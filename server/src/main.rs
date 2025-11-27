// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use clap::Parser;
use log::{info, error, warn, debug};
use std::fs::File;
use std::io::{Write, Read, BufReader, BufRead};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::path::PathBuf;

mod input;

#[derive(Parser, Debug)]
#[command(name = "twoyi-server")]
#[command(about = r#"twoyi container server

This server uses scrcpy to connect to the container's adbd for graphics display.
The bound port is the ADB port for scrcpy connections.

For graphics display, connect scrcpy to this server's ADB port."#, long_about = None)]
struct Args {
    /// Path to the rootfs directory
    #[arg(short, long)]
    rootfs: PathBuf,

    /// Path to the loader library (libloader.so)
    #[arg(short, long)]
    loader: Option<PathBuf>,

    /// ADB port to bind for scrcpy connections (e.g., 0.0.0.0:5555)
    #[arg(short = 'a', long, default_value = "0.0.0.0:5555")]
    adb_bind: String,

    /// Screen width
    #[arg(long, default_value_t = 1080)]
    width: i32,

    /// Screen height
    #[arg(long, default_value_t = 1920)]
    height: i32,

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
    info!("ADB bind address: {}", args.adb_bind);
    info!("Screen size: {}x{}", args.width, args.height);
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
    
    // Print info about scrcpy graphics
    info!("=== SCRCPY GRAPHICS MODE ===");
    info!("This server uses scrcpy to connect to the container's adbd for graphics.");
    info!("Connect scrcpy to this server's ADB port: {}", args.adb_bind);
    info!("Example: scrcpy --tcpip={}", args.adb_bind);
    info!("============================");

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

    // Start container process (unless in setup mode)
    let container_running = Arc::new(AtomicBool::new(true));
    
    if !args.setup {
        let container_running_clone = container_running.clone();
        let rootfs_clone = args.rootfs.clone();
        let loader_clone = args.loader.clone();
        let verbose = args.verbose;
        thread::spawn(move || {
            start_container(&rootfs_clone, loader_clone.as_ref(), verbose);
            container_running_clone.store(false, Ordering::SeqCst);
        });
    } else {
        info!("Container startup skipped (setup mode).");
        info!("To start the container manually, run: cd {:?} && ./init", args.rootfs);
        if let Some(ref loader) = args.loader {
            info!("Don't forget to set: export TYLOADER={:?}", loader);
        }
    }

    // Start ADB port forwarder for scrcpy connections
    let adb_listener = match TcpListener::bind(&args.adb_bind) {
        Ok(l) => l,
        Err(e) => {
            error!("Failed to bind ADB port {}: {}", args.adb_bind, e);
            std::process::exit(1);
        }
    };

    info!("ADB server listening on {} for scrcpy connections", args.adb_bind);

    let setup_mode = args.setup;
    let width = args.width;
    let height = args.height;
    let rootfs = args.rootfs.clone();
    
    for stream in adb_listener.incoming() {
        match stream {
            Ok(stream) => {
                let rootfs_clone = rootfs.clone();
                thread::spawn(move || {
                    handle_adb_client(stream, width, height, &rootfs_clone, setup_mode);
                });
            }
            Err(e) => {
                error!("Error accepting ADB connection: {}", e);
            }
        }
    }
}

fn start_container(rootfs: &PathBuf, loader: Option<&PathBuf>, verbose: bool) {
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

fn handle_adb_client(mut client_stream: TcpStream, width: i32, height: i32, rootfs: &PathBuf, setup_mode: bool) {
    let peer_addr = client_stream.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "unknown".to_string());
    info!("ADB client connected from {}", peer_addr);

    // The container's adbd listens on a Unix socket or local port
    // We need to forward the TCP connection to the container's adbd
    let adbd_socket_path = format!("{}/dev/socket/adbd", rootfs.to_string_lossy());
    
    // Try to connect to the container's adbd via Unix socket
    match unix_socket::UnixStream::connect(&adbd_socket_path) {
        Ok(mut adbd_stream) => {
            info!("Connected to container's adbd at {}", adbd_socket_path);
            
            // Clone streams for bidirectional forwarding
            let mut client_read = match client_stream.try_clone() {
                Ok(s) => s,
                Err(e) => {
                    error!("Failed to clone client stream: {}", e);
                    return;
                }
            };
            let mut adbd_write = match adbd_stream.try_clone() {
                Ok(s) => s,
                Err(e) => {
                    error!("Failed to clone adbd stream: {}", e);
                    return;
                }
            };
            
            // Forward client -> adbd
            let forward_handle = thread::spawn(move || {
                let mut buffer = [0u8; 8192];
                loop {
                    match client_read.read(&mut buffer) {
                        Ok(0) => break, // Connection closed
                        Ok(n) => {
                            if adbd_write.write_all(&buffer[..n]).is_err() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            });
            
            // Forward adbd -> client (in current thread)
            let mut buffer = [0u8; 8192];
            loop {
                match adbd_stream.read(&mut buffer) {
                    Ok(0) => break,
                    Ok(n) => {
                        if client_stream.write_all(&buffer[..n]).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
            
            let _ = forward_handle.join();
            info!("ADB client {} disconnected", peer_addr);
        }
        Err(e) => {
            warn!("Could not connect to container's adbd at {}: {}", adbd_socket_path, e);
            
            // Send error info back to client as JSON
            let status = if setup_mode { "setup" } else { "waiting_for_adbd" };
            let info = serde_json::json!({
                "error": "adbd_not_available",
                "message": format!("Container's adbd is not yet available: {}", e),
                "width": width,
                "height": height,
                "rootfs": rootfs.to_string_lossy(),
                "status": status,
                "setup_mode": setup_mode,
                "scrcpy_mode": true
            });
            
            if let Ok(info_str) = serde_json::to_string(&info) {
                let _ = client_stream.write_all(format!("{}\n", info_str).as_bytes());
            }
            
            info!("ADB client {} notified - adbd not available", peer_addr);
        }
    }
}
