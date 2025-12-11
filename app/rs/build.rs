// Build script to compile and link the FOSS OpenGL renderer into libtwoyi.so
// This embeds the renderer directly instead of using dlopen

use std::env;
use std::path::PathBuf;

fn main() {
    // Tell cargo to link the renderer functions directly
    // The renderer wrapper will be compiled and linked into libtwoyi.so
    
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let cpp_dir = manifest_dir.parent().unwrap().join("src/main/cpp");
    
    // Build the renderer wrapper as a C library
    cc::Build::new()
        .file(cpp_dir.join("renderer_wrapper.cpp"))
        .include(cpp_dir.join("anbox/src"))
        .include(cpp_dir.join("anbox/external/android-emugl/shared"))
        .include(cpp_dir.join("anbox/external/android-emugl/host/include"))
        .cpp(true)
        .warnings(false) // Suppress warnings from anbox code
        .compile("renderer_wrapper");
    
    // Link against Android log library
    println!("cargo:rustc-link-lib=log");
    println!("cargo:rustc-link-lib=android");
    
    // No longer need to search jniLibs since renderer is embedded
}
