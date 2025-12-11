// Build script to compile and link the FOSS OpenGL renderer into libtwoyi.so

use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let cpp_dir = manifest_dir.parent().unwrap().join("src/main/cpp");
    
    // Build the renderer wrapper as a C library
    cc::Build::new()
        .file(cpp_dir.join("renderer_wrapper.cpp"))
        .cpp(true)
        .warnings(false)
        .compile("renderer_wrapper");
    
    // Link against Android graphics libraries
    println!("cargo:rustc-link-lib=log");
    println!("cargo:rustc-link-lib=android");
    println!("cargo:rustc-link-lib=EGL");
    println!("cargo:rustc-link-lib=GLESv2");
}
