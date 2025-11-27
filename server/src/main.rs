// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use clap::Parser;
use log::{info, error, warn, debug};
use std::fs::File;
use std::io::{Write, BufReader, BufRead};
use std::net::{TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::path::PathBuf;

mod input;
mod framebuffer;
mod gralloc;

#[derive(Parser, Debug)]
#[command(name = "twoyi-server")]
#[command(about = r#"twoyi container server with software graphics support

This server provides a software-based graphics implementation that allows
the container to run without requiring libOpenglRender.so or an Android surface.

The server includes:
- Software gralloc HAL for buffer allocation
- Framebuffer streaming to connected clients
- Input event forwarding (touch and keyboard)

Usage modes:
- Normal: Start container and stream framebuffer to clients
- Setup: Start server without container for manual environment setup

For full functionality, use the twoyi Android app as a client."#, long_about = None)]
struct Args {
    /// Path to the rootfs directory
    #[arg(short, long)]
    rootfs: PathBuf,

    /// Path to the loader library (libloader.so)
    #[arg(short, long)]
    loader: Option<PathBuf>,

    /// Address and port to bind on (e.g., 0.0.0.0:8765)
    #[arg(short = 'a', long, default_value = "0.0.0.0:8765")]
    bind: String,

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
    
    /// Disable gralloc server (use legacy framebuffer reading)
    #[arg(long)]
    no_gralloc: bool,
}

fn main() {
    let args = Args::parse();
    
    // Set log level based on verbose flag
    let log_level = if args.verbose { "debug" } else { "info" };
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or(log_level)).init();

    info!("twoyi-server starting...");
    info!("Rootfs: {:?}", args.rootfs);
    info!("Bind address: {}", args.bind);
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
    
    // Print info about graphics support
    if args.no_gralloc {
        warn!("=== GRALLOC DISABLED ===");
        warn!("Gralloc server is disabled. Using legacy framebuffer reading.");
        warn!("Graphics services may crash without proper gralloc HAL.");
        warn!("========================");
    } else {
        info!("=== SOFTWARE GRAPHICS ===");
        info!("Software gralloc server will be started.");
        info!("Graphics services will use software rendering.");
        info!("=========================");
    }

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
    
    // Start gralloc server if not disabled
    let gralloc_server = if !args.no_gralloc {
        let gralloc_socket = format!("{}/dev/gralloc", rootfs_str);
        let server = Arc::new(gralloc::GrallocServer::new(
            &gralloc_socket,
            args.width as u32,
            args.height as u32,
        ));
        
        if let Err(e) = server.start() {
            error!("Failed to start gralloc server: {}", e);
            std::process::exit(1);
        }
        info!("Gralloc server started at {}", gralloc_socket);
        Some(server)
    } else {
        None
    };

    // Start container process (unless in setup mode)
    let container_running = Arc::new(AtomicBool::new(true));
    
    if !args.setup {
        let container_running_clone = container_running.clone();
        let rootfs_clone = args.rootfs.clone();
        let loader_clone = args.loader.clone();
        let verbose = args.verbose;
        let use_gralloc = !args.no_gralloc;
        thread::spawn(move || {
            start_container(&rootfs_clone, loader_clone.as_ref(), verbose, use_gralloc);
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

    info!("Server listening on {}", args.bind);

    // Start framebuffer streamer with gralloc integration
    let mut frame_streamer = framebuffer::FrameStreamer::new(
        args.width, 
        args.height, 
        &args.rootfs.to_string_lossy()
    );
    
    // Connect gralloc server to frame streamer if available
    if let Some(ref server) = gralloc_server {
        frame_streamer.set_gralloc_server(server.clone());
    }
    
    let frame_streamer = Arc::new(frame_streamer);
    frame_streamer.start();

    let setup_mode = args.setup;
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let width = args.width;
                let height = args.height;
                let rootfs = args.rootfs.clone();
                let streamer = frame_streamer.clone();
                thread::spawn(move || {
                    handle_client(stream, width, height, &rootfs, setup_mode, streamer);
                });
            }
            Err(e) => {
                error!("Error accepting connection: {}", e);
            }
        }
    }
}

fn start_container(rootfs: &PathBuf, loader: Option<&PathBuf>, verbose: bool, use_gralloc: bool) {
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
    
    // Set gralloc socket path if using software gralloc
    if use_gralloc {
        let gralloc_socket = format!("{}/dev/gralloc", working_dir);
        info!("Setting TWOYI_GRALLOC_SOCKET={}", gralloc_socket);
        cmd.env("TWOYI_GRALLOC_SOCKET", gralloc_socket);
        cmd.env("TWOYI_USE_SOFTWARE_GRALLOC", "1");
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

fn handle_client(mut stream: TcpStream, width: i32, height: i32, rootfs: &PathBuf, setup_mode: bool, frame_streamer: Arc<framebuffer::FrameStreamer>) {
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
        "streaming": true
    });
    
    if let Ok(info_str) = serde_json::to_string(&info) {
        let _ = stream.write_all(format!("{}\n", info_str).as_bytes());
    }

    // Clone stream for framebuffer streaming
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
