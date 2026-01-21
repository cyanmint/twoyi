fn main() {
    println!("cargo:rustc-link-search=native=../src/main/jniLibs/arm64-v8a");
    
    // Compile interp.c to add INTERP segment for direct execution
    cc::Build::new()
        .file("src/interp.c")
        .compile("interp");
    
    // Compile the OpenGL renderer wrapper
    // This creates a C wrapper that provides the same API as the legacy libOpenglRender.so
    cc::Build::new()
        .file("src/openglrenderer_wrapper.c")
        .compile("openglrenderer_wrapper");
    
    // The entry point is set via RUSTFLAGS in build_rs.sh: -Wl,-e,main
    // The interp.c file adds the .interp section needed for direct execution
    // This makes the library a PIE executable that can still be loaded by JNI
}
