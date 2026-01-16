fn main() {
    // Tell cargo to look for libOpenglRender.so in the jniLibs directory
    // This is needed for the legacy renderer option that uses static FFI linking
    let jni_libs_path = std::path::Path::new("../src/main/jniLibs/arm64-v8a");
    if jni_libs_path.exists() {
        println!("cargo:rustc-link-search=native={}", jni_libs_path.display());
    }
    
    // Compile interp.c to add INTERP segment for direct execution
    cc::Build::new()
        .file("src/interp.c")
        .compile("interp");
    
    // The entry point is set via RUSTFLAGS in build_rs.sh: -Wl,-e,main
    // The interp.c file adds the .interp section needed for direct execution
    // This makes the library a PIE executable that can still be loaded by JNI
}
