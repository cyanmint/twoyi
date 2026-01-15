fn main() {
    // The OpenglRender library is built via CMake through Gradle's externalNativeBuild
    // During a full Gradle build, it will be in the CMake build output directory
    // We add multiple search paths to handle different build scenarios
    
    // 1. CMake build output from Gradle (during full build)
    println!("cargo:rustc-link-search=native=../build/intermediates/cmake/release/obj/arm64-v8a");
    println!("cargo:rustc-link-search=native=../build/intermediates/cxx/RelWithDebInfo/4f286l5s/obj/arm64-v8a");
    
    // 2. Final jniLibs directory (for standalone cargo build after Gradle build)
    println!("cargo:rustc-link-search=native=../src/main/jniLibs/arm64-v8a");
    
    // Compile interp.c to add INTERP segment for direct execution
    cc::Build::new()
        .file("src/interp.c")
        .compile("interp");
    
    // The entry point is set via RUSTFLAGS in build_rs.sh: -Wl,-e,main
    // The interp.c file adds the .interp section needed for direct execution
    // This makes the library a PIE executable that can still be loaded by JNI
}
