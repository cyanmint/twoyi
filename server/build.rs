fn main() {
    // Link to OpenglRender library in the app's jniLibs directory
    println!("cargo:rustc-link-search=native=../app/src/main/jniLibs/arm64-v8a");
}
