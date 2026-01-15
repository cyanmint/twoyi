// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * OpenGL Renderer Implementation
 * Based on Anbox graphics renderer
 */

#include "renderer.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "OpenGLRender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace openglrender {

Renderer::Renderer()
    : m_eglDisplay(EGL_NO_DISPLAY),
      m_eglContext(EGL_NO_CONTEXT),
      m_windowSurface(EGL_NO_SURFACE),
      m_pbufSurface(EGL_NO_SURFACE),
      m_pbufContext(EGL_NO_CONTEXT),
      m_eglConfig(nullptr),
      m_windowWidth(0),
      m_windowHeight(0),
      m_windowX(0),
      m_windowY(0),
      m_initialized(false) {
}

Renderer::~Renderer() {
    finalize();
}

bool Renderer::initialize(EGLNativeDisplayType nativeDisplay) {
    std::lock_guard<std::mutex> lock(m_lock);
    
    if (m_initialized) {
        LOGW("Renderer already initialized");
        return true;
    }

    LOGI("Initializing OpenGL Renderer");

    // Get EGL display
    m_eglDisplay = eglGetDisplay(nativeDisplay);
    if (m_eglDisplay == EGL_NO_DISPLAY) {
        LOGE("Failed to get EGL display");
        return false;
    }

    // Initialize EGL
    EGLint major, minor;
    if (!eglInitialize(m_eglDisplay, &major, &minor)) {
        LOGE("Failed to initialize EGL");
        return false;
    }
    LOGI("EGL version %d.%d", major, minor);

    // Choose EGL config
    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(m_eglDisplay, configAttribs, &m_eglConfig, 1, &numConfigs) || numConfigs == 0) {
        LOGE("Failed to choose EGL config");
        return false;
    }

    // Create EGL context
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };

    m_eglContext = eglCreateContext(m_eglDisplay, m_eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (m_eglContext == EGL_NO_CONTEXT) {
        LOGE("Failed to create EGL context");
        return false;
    }

    // Create a pbuffer surface for off-screen rendering
    const EGLint pbufAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };

    m_pbufSurface = eglCreatePbufferSurface(m_eglDisplay, m_eglConfig, pbufAttribs);
    if (m_pbufSurface == EGL_NO_SURFACE) {
        LOGE("Failed to create pbuffer surface");
        return false;
    }

    m_pbufContext = eglCreateContext(m_eglDisplay, m_eglConfig, m_eglContext, contextAttribs);
    if (m_pbufContext == EGL_NO_CONTEXT) {
        LOGE("Failed to create pbuffer context");
        return false;
    }

    m_initialized = true;
    LOGI("OpenGL Renderer initialized successfully");
    return true;
}

bool Renderer::createWindowSurface(EGLNativeWindowType nativeWindow) {
    std::lock_guard<std::mutex> lock(m_lock);

    if (!m_initialized) {
        LOGE("Renderer not initialized");
        return false;
    }

    // Destroy existing window surface if any
    if (m_windowSurface != EGL_NO_SURFACE) {
        eglDestroySurface(m_eglDisplay, m_windowSurface);
        m_windowSurface = EGL_NO_SURFACE;
    }

    // Create window surface
    m_windowSurface = eglCreateWindowSurface(m_eglDisplay, m_eglConfig, nativeWindow, nullptr);
    if (m_windowSurface == EGL_NO_SURFACE) {
        LOGE("Failed to create window surface, error: 0x%x", eglGetError());
        return false;
    }

    LOGI("Window surface created successfully");
    return true;
}

void Renderer::updateWindowSize(int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lock(m_lock);
    m_windowX = x;
    m_windowY = y;
    m_windowWidth = width;
    m_windowHeight = height;
    LOGD("Window size updated: %dx%d at (%d,%d)", width, height, x, y);
}

bool Renderer::bind() {
    std::lock_guard<std::mutex> lock(m_lock);

    if (!m_initialized) {
        LOGE("Renderer not initialized");
        return false;
    }

    EGLSurface surface = (m_windowSurface != EGL_NO_SURFACE) ? m_windowSurface : m_pbufSurface;
    EGLContext context = (m_windowSurface != EGL_NO_SURFACE) ? m_eglContext : m_pbufContext;

    if (!eglMakeCurrent(m_eglDisplay, surface, surface, context)) {
        LOGE("Failed to make EGL context current, error: 0x%x", eglGetError());
        return false;
    }

    return true;
}

void Renderer::unbind() {
    std::lock_guard<std::mutex> lock(m_lock);
    
    if (m_initialized) {
        eglMakeCurrent(m_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

void Renderer::swapBuffers() {
    std::lock_guard<std::mutex> lock(m_lock);

    if (m_windowSurface != EGL_NO_SURFACE) {
        if (!eglSwapBuffers(m_eglDisplay, m_windowSurface)) {
            LOGE("Failed to swap buffers, error: 0x%x", eglGetError());
        }
    }
}

void Renderer::finalize() {
    std::lock_guard<std::mutex> lock(m_lock);

    if (!m_initialized) {
        return;
    }

    LOGI("Finalizing renderer");

    eglMakeCurrent(m_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (m_windowSurface != EGL_NO_SURFACE) {
        eglDestroySurface(m_eglDisplay, m_windowSurface);
        m_windowSurface = EGL_NO_SURFACE;
    }

    if (m_pbufSurface != EGL_NO_SURFACE) {
        eglDestroySurface(m_eglDisplay, m_pbufSurface);
        m_pbufSurface = EGL_NO_SURFACE;
    }

    if (m_pbufContext != EGL_NO_CONTEXT) {
        eglDestroyContext(m_eglDisplay, m_pbufContext);
        m_pbufContext = EGL_NO_CONTEXT;
    }

    if (m_eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(m_eglDisplay, m_eglContext);
        m_eglContext = EGL_NO_CONTEXT;
    }

    if (m_eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(m_eglDisplay);
        m_eglDisplay = EGL_NO_DISPLAY;
    }

    m_initialized = false;
    LOGI("Renderer finalized");
}

} // namespace openglrender
