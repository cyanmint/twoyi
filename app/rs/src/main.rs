// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! twoyi standalone executable
//!
//! This is the binary entry point for running twoyi as a standalone server.
//! Usage: ./twoyi -r $(realpath rootfs)
//!
//! This binary reuses all the functionality from lib.rs.

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
    
    let exit_code = twoyi::cli::run_cli(c_argv.len() as libc::c_int, c_argv.as_ptr());
    
    // c_args is still alive here, so pointers in c_argv are valid
    drop(c_args);
    
    std::process::exit(exit_code);
}
