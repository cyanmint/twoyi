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
    let args: Vec<std::ffi::OsString> = std::env::args_os().collect();
    
    // Convert args to C-style argc/argv for the CLI module
    let c_args: Vec<std::ffi::CString> = args
        .iter()
        .map(|arg| {
            let s = arg.to_string_lossy();
            std::ffi::CString::new(s.as_ref()).unwrap_or_else(|_| std::ffi::CString::new("").unwrap())
        })
        .collect();
    
    let c_argv: Vec<*const libc::c_char> = c_args.iter().map(|s| s.as_ptr()).collect();
    
    let exit_code = twoyi::cli::run_cli(c_argv.len() as libc::c_int, c_argv.as_ptr());
    
    std::process::exit(exit_code);
}
