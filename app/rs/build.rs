fn main() {
    // No longer linking against external libOpenglRender.so
    // The OpenGL renderer is now implemented directly in Rust within this library
    
    // Compile interp.c to add INTERP segment for direct execution
    cc::Build::new()
        .file("src/interp.c")
        .compile("interp");
    
    // The entry point is set via RUSTFLAGS in build_rs.sh: -Wl,-e,main
    // The interp.c file adds the .interp section needed for direct execution
    // This makes the library a PIE executable that can still be loaded by JNI
}
