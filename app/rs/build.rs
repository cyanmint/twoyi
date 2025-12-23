fn main() {
    // The CMake build outputs to build/intermediates/cmake/
    // We need to provide the path where libOpenglRender.so can be found
    println!("cargo:rustc-link-search=native=../build/intermediates/cmake/release/obj/arm64-v8a");
    println!("cargo:rustc-link-search=native=../build/intermediates/cmake/debug/obj/arm64-v8a");
    // Fallback to jniLibs in case library is there
    println!("cargo:rustc-link-search=native=../src/main/jniLibs/arm64-v8a");
}
