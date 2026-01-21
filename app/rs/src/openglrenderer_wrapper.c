// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//
// OpenGL Renderer C Wrapper
//
// This file provides a C API that matches the legacy libOpenglRender.so
// but forwards all calls to the new Rust implementation.
//

#include <stdint.h>

// Forward declarations of Rust functions
// These will be linked from the Rust crate
extern int32_t rust_start_renderer(void* window, int32_t width, int32_t height, 
                                   int32_t xdpi, int32_t ydpi, int32_t fps);
extern int32_t rust_set_native_window(void* window);
extern int32_t rust_reset_window(void* window, int32_t wx, int32_t wy,
                                 int32_t ww, int32_t wh, int32_t fbw, int32_t fbh,
                                 float dpr, float z_rot);
extern int32_t rust_remove_window(void* window);
extern int32_t rust_destroy_subwindow(void);
extern void rust_repaint_display(void);

// C API functions that match the legacy libOpenglRender.so interface
// These will be exported from the new libopenglrenderer.so

__attribute__((visibility("default")))
int startOpenGLRenderer(void* win, int width, int height, int xdpi, int ydpi, int fps) {
    return rust_start_renderer(win, width, height, xdpi, ydpi, fps);
}

__attribute__((visibility("default")))
int setNativeWindow(void* window) {
    return rust_set_native_window(window);
}

__attribute__((visibility("default")))
int resetSubWindow(void* p_window, int wx, int wy, int ww, int wh, 
                   int fbw, int fbh, float dpr, float zRot) {
    return rust_reset_window(p_window, wx, wy, ww, wh, fbw, fbh, dpr, zRot);
}

__attribute__((visibility("default")))
int removeSubWindow(void* window) {
    return rust_remove_window(window);
}

__attribute__((visibility("default")))
int destroyOpenGLSubwindow(void) {
    return rust_destroy_subwindow();
}

__attribute__((visibility("default")))
void repaintOpenGLDisplay(void) {
    rust_repaint_display();
}
