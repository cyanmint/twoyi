// renderer_wrapper.cpp
// Wrapper to expose anbox android-emugl renderer functions
// This replaces the proprietary libOpenglRender.so

#include <jni.h>
#include <android/log.h>

// Include anbox renderer headers
#include "anbox/graphics/emugl/RenderApi.h"

#define LOG_TAG "TwoyiRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Export the OpenGL renderer functions that are expected by the Rust code
// These match the function signatures in app/rs/src/renderer_bindings.rs

JNIEXPORT jint JNICALL destroyOpenGLSubwindow() {
    LOGI("destroyOpenGLSubwindow called");
    // Call the anbox implementation
    return anbox::graphics::emugl::destroyOpenGLSubwindow();
}

JNIEXPORT void JNICALL repaintOpenGLDisplay() {
    LOGI("repaintOpenGLDisplay called");
    // Call the anbox implementation
    anbox::graphics::emugl::repaintOpenGLDisplay();
}

JNIEXPORT jint JNICALL setNativeWindow(void* window) {
    LOGI("setNativeWindow called");
    // Call the anbox implementation
    return anbox::graphics::emugl::setNativeWindow(window);
}

JNIEXPORT jint JNICALL resetSubWindow(
    void* p_window,
    int wx, int wy, int ww, int wh,
    int fbw, int fbh,
    float dpr, float z_rot) {
    LOGI("resetSubWindow called");
    // Call the anbox implementation
    return anbox::graphics::emugl::resetSubWindow(
        p_window, wx, wy, ww, wh, fbw, fbh, dpr, z_rot);
}

JNIEXPORT jint JNICALL startOpenGLRenderer(
    void* win,
    int width, int height,
    int xdpi, int ydpi, int fps) {
    LOGI("startOpenGLRenderer called: %dx%d", width, height);
    // Call the anbox implementation
    return anbox::graphics::emugl::startOpenGLRenderer(
        win, width, height, xdpi, ydpi, fps);
}

JNIEXPORT jint JNICALL removeSubWindow(void* window) {
    LOGI("removeSubWindow called");
    // Call the anbox implementation
    return anbox::graphics::emugl::removeSubWindow(window);
}

} // extern "C"
