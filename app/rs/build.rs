fn main() {
    // Link to OpenglRender library in the app's jniLibs directory
    // app/rs/build.rs -> relative path to app/src/main/jniLibs/arm64-v8a
    println!("cargo:rustc-link-search=native=../src/main/jniLibs/arm64-v8a");
}
