// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * OpenGL Render API Implementation
 * Main entry points for the OpenGL rendering library
 */

#include "openglrender.h"
#include "renderer.h"
#include "pipe_connection.h"
#include <android/log.h>
#include <android/native_window.h>
#include <memory>
#include <mutex>

#define LOG_TAG "OpenGLRender-API"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {
    // Global renderer instance
    std::shared_ptr<openglrender::Renderer> g_renderer;
    std::shared_ptr<openglrender::PipeConnection> g_pipeConnection;
    std::mutex g_mutex;
    ANativeWindow* g_currentWindow = nullptr;
}

extern "C" {

int startOpenGLRenderer(void* win, int width, int height, int xdpi, int ydpi, int fps) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("startOpenGLRenderer: win=%p, %dx%d, dpi=%dx%d, fps=%d", 
         win, width, height, xdpi, ydpi, fps);

    if (!win) {
        LOGE("Invalid window pointer");
        return -1;
    }

    ANativeWindow* window = static_cast<ANativeWindow*>(win);
    g_currentWindow = window;

    // Create renderer if not already created
    if (!g_renderer) {
        g_renderer = std::make_shared<openglrender::Renderer>();
        
        // Initialize with default display
        if (!g_renderer->initialize(EGL_DEFAULT_DISPLAY)) {
            LOGE("Failed to initialize renderer");
            g_renderer.reset();
            return -1;
        }
    }

    // Create window surface
    if (!g_renderer->createWindowSurface(window)) {
        LOGE("Failed to create window surface");
        return -1;
    }

    // Update window size
    g_renderer->updateWindowSize(0, 0, width, height);

    // Start pipe connection handler if not already running
    if (!g_pipeConnection) {
        g_pipeConnection = std::make_shared<openglrender::PipeConnection>(g_renderer);
        if (!g_pipeConnection->start()) {
            LOGE("Failed to start pipe connection handler");
            // Continue anyway - the renderer can still work without pipe connections
        }
    }

    LOGI("OpenGL renderer started successfully");
    return 0;
}

int setNativeWindow(void* window) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("setNativeWindow: window=%p", window);

    if (!window) {
        LOGE("Invalid window pointer");
        return -1;
    }

    ANativeWindow* nativeWindow = static_cast<ANativeWindow*>(window);
    g_currentWindow = nativeWindow;

    if (g_renderer) {
        if (!g_renderer->createWindowSurface(nativeWindow)) {
            LOGE("Failed to create window surface");
            return -1;
        }
    }

    return 0;
}

int resetSubWindow(void* p_window, int wx, int wy, int ww, int wh, 
                   int fbw, int fbh, float dpr, float zRot) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGD("resetSubWindow: window=%p, pos=(%d,%d), size=%dx%d, fb=%dx%d, dpr=%.2f, rot=%.2f",
         p_window, wx, wy, ww, wh, fbw, fbh, dpr, zRot);

    if (!p_window) {
        LOGE("Invalid window pointer");
        return -1;
    }

    ANativeWindow* window = static_cast<ANativeWindow*>(p_window);
    g_currentWindow = window;

    if (!g_renderer) {
        LOGE("Renderer not initialized");
        return -1;
    }

    // Recreate window surface if needed
    if (!g_renderer->createWindowSurface(window)) {
        LOGE("Failed to create window surface");
        return -1;
    }

    // Update window dimensions
    g_renderer->updateWindowSize(wx, wy, ww, wh);

    return 0;
}

int removeSubWindow(void* window) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("removeSubWindow: window=%p", window);

    if (g_currentWindow == window) {
        g_currentWindow = nullptr;
    }

    // Window surface will be destroyed when a new one is created
    return 0;
}

int destroyOpenGLSubwindow() {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("destroyOpenGLSubwindow");

    if (g_pipeConnection) {
        g_pipeConnection->stop();
        g_pipeConnection.reset();
    }

    if (g_renderer) {
        g_renderer->finalize();
        g_renderer.reset();
    }

    g_currentWindow = nullptr;

    LOGI("OpenGL subwindow destroyed");
    return 0;
}

void repaintOpenGLDisplay() {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_renderer) {
        // Bind context and swap buffers to trigger a repaint
        if (g_renderer->bind()) {
            g_renderer->swapBuffers();
            g_renderer->unbind();
        }
    }
}

} // extern "C"
