// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

#include "OpenglRender.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <android/native_window.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <memory>
#include <atomic>

#define LOG_TAG "OpenglRender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

// Global state for the OpenGL renderer
struct RendererState {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    EGLConfig config = nullptr;
    
    int width = 0;
    int height = 0;
    int fbWidth = 0;
    int fbHeight = 0;
    int xdpi = 0;
    int ydpi = 0;
    int fps = 60;
    
    pthread_t renderThread = 0;
    std::atomic<bool> running{false};
    std::atomic<bool> needsRepaint{false};
    pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    
    // Framebuffer for rendering
    GLuint framebuffer = 0;
    GLuint texture = 0;
    GLuint renderbuffer = 0;
};

static RendererState g_state;

bool initializeEGL() {
    g_state.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_state.display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(g_state.display, &major, &minor)) {
        LOGE("eglInitialize failed");
        return false;
    }

    LOGI("EGL version: %d.%d", major, minor);

    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(g_state.display, configAttribs, &g_state.config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    g_state.context = eglCreateContext(g_state.display, g_state.config, EGL_NO_CONTEXT, contextAttribs);
    if (g_state.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }

    return true;
}

bool createWindowSurface(ANativeWindow* window) {
    if (g_state.surface != EGL_NO_SURFACE) {
        eglDestroySurface(g_state.display, g_state.surface);
        g_state.surface = EGL_NO_SURFACE;
    }

    if (!window) {
        LOGE("Invalid window");
        return false;
    }

    // Set the buffer format
    ANativeWindow_setBuffersGeometry(window, g_state.width, g_state.height, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    g_state.surface = eglCreateWindowSurface(g_state.display, g_state.config, window, nullptr);
    if (g_state.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    return true;
}

void createFramebuffer() {
    // Delete existing framebuffer if any
    if (g_state.framebuffer != 0) {
        glDeleteFramebuffers(1, &g_state.framebuffer);
        g_state.framebuffer = 0;
    }
    if (g_state.texture != 0) {
        glDeleteTextures(1, &g_state.texture);
        g_state.texture = 0;
    }
    if (g_state.renderbuffer != 0) {
        glDeleteRenderbuffers(1, &g_state.renderbuffer);
        g_state.renderbuffer = 0;
    }

    // Create texture for color attachment
    glGenTextures(1, &g_state.texture);
    glBindTexture(GL_TEXTURE_2D, g_state.texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_state.fbWidth, g_state.fbHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Create renderbuffer for depth
    glGenRenderbuffers(1, &g_state.renderbuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, g_state.renderbuffer);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, g_state.fbWidth, g_state.fbHeight);

    // Create framebuffer
    glGenFramebuffers(1, &g_state.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, g_state.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_state.texture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, g_state.renderbuffer);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer not complete: 0x%x", status);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    LOGI("Created framebuffer: %dx%d", g_state.fbWidth, g_state.fbHeight);
}

void renderFrame() {
    if (!eglMakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return;
    }

    // Clear the screen
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // If we have a framebuffer texture, render it to the screen
    if (g_state.texture != 0) {
        // Simple blit from framebuffer texture to screen
        // For a complete implementation, we would render the texture using a shader
        // For now, just clear to show the window is working
    }

    eglSwapBuffers(g_state.display, g_state.surface);
}

void* renderThreadFunc(void* /*arg*/) {
    LOGI("Render thread started");
    
    while (g_state.running.load()) {
        if (g_state.needsRepaint.load() || g_state.fps > 0) {
            renderFrame();
            g_state.needsRepaint.store(false);
            
            if (g_state.fps > 0) {
                usleep(1000000 / g_state.fps);
            }
        } else {
            usleep(16000); // Sleep for ~16ms if no rendering needed
        }
    }
    
    LOGI("Render thread stopped");
    return nullptr;
}

void cleanupEGL() {
    pthread_mutex_lock(&g_state.mutex);
    
    if (g_state.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_state.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        
        if (g_state.surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_state.display, g_state.surface);
            g_state.surface = EGL_NO_SURFACE;
        }
        
        if (g_state.context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_state.display, g_state.context);
            g_state.context = EGL_NO_CONTEXT;
        }
        
        eglTerminate(g_state.display);
        g_state.display = EGL_NO_DISPLAY;
    }
    
    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = nullptr;
    }
    
    pthread_mutex_unlock(&g_state.mutex);
}

} // anonymous namespace

extern "C" {

int startOpenGLRenderer(void* win, int width, int height, int xdpi, int ydpi, int fps) {
    LOGI("startOpenGLRenderer: window=%p, size=%dx%d, dpi=%dx%d, fps=%d", 
         win, width, height, xdpi, ydpi, fps);
    
    pthread_mutex_lock(&g_state.mutex);
    
    if (g_state.running.load()) {
        LOGD("Renderer already running");
        pthread_mutex_unlock(&g_state.mutex);
        return 0;
    }
    
    g_state.window = static_cast<ANativeWindow*>(win);
    g_state.width = width;
    g_state.height = height;
    g_state.fbWidth = width;
    g_state.fbHeight = height;
    g_state.xdpi = xdpi;
    g_state.ydpi = ydpi;
    g_state.fps = fps;
    
    if (g_state.window) {
        ANativeWindow_acquire(g_state.window);
    }
    
    if (!initializeEGL()) {
        LOGE("Failed to initialize EGL");
        pthread_mutex_unlock(&g_state.mutex);
        return -1;
    }
    
    if (!createWindowSurface(g_state.window)) {
        LOGE("Failed to create window surface");
        cleanupEGL();
        pthread_mutex_unlock(&g_state.mutex);
        return -1;
    }
    
    // Make context current to create framebuffer
    if (!eglMakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context)) {
        LOGE("eglMakeCurrent failed");
        cleanupEGL();
        pthread_mutex_unlock(&g_state.mutex);
        return -1;
    }
    
    createFramebuffer();
    
    // Start render thread
    g_state.running.store(true);
    if (pthread_create(&g_state.renderThread, nullptr, renderThreadFunc, nullptr) != 0) {
        LOGE("Failed to create render thread");
        g_state.running.store(false);
        cleanupEGL();
        pthread_mutex_unlock(&g_state.mutex);
        return -1;
    }
    
    pthread_mutex_unlock(&g_state.mutex);
    LOGI("OpenGL renderer started successfully");
    return 0;
}

int setNativeWindow(void* win) {
    LOGI("setNativeWindow: window=%p", win);
    
    pthread_mutex_lock(&g_state.mutex);
    
    if (g_state.window) {
        ANativeWindow_release(g_state.window);
    }
    
    g_state.window = static_cast<ANativeWindow*>(win);
    if (g_state.window) {
        ANativeWindow_acquire(g_state.window);
        
        if (g_state.display != EGL_NO_DISPLAY) {
            createWindowSurface(g_state.window);
        }
    }
    
    pthread_mutex_unlock(&g_state.mutex);
    return 0;
}

int resetSubWindow(void* p_window, int wx, int wy, int ww, int wh, int fbw, int fbh, float dpr, float zRot) {
    LOGI("resetSubWindow: window=%p, pos=%dx%d, size=%dx%d, fb=%dx%d, dpr=%.2f, rot=%.2f",
         p_window, wx, wy, ww, wh, fbw, fbh, dpr, zRot);
    
    pthread_mutex_lock(&g_state.mutex);
    
    g_state.width = ww;
    g_state.height = wh;
    g_state.fbWidth = fbw;
    g_state.fbHeight = fbh;
    
    if (p_window && p_window != g_state.window) {
        if (g_state.window) {
            ANativeWindow_release(g_state.window);
        }
        g_state.window = static_cast<ANativeWindow*>(p_window);
        ANativeWindow_acquire(g_state.window);
    }
    
    if (g_state.display != EGL_NO_DISPLAY && g_state.window) {
        createWindowSurface(g_state.window);
        
        if (eglMakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context)) {
            createFramebuffer();
            g_state.needsRepaint.store(true);
        }
    }
    
    pthread_mutex_unlock(&g_state.mutex);
    return 0;
}

void repaintOpenGLDisplay() {
    LOGD("repaintOpenGLDisplay");
    g_state.needsRepaint.store(true);
}

int destroyOpenGLSubwindow() {
    LOGI("destroyOpenGLSubwindow");
    
    pthread_mutex_lock(&g_state.mutex);
    
    if (g_state.running.load()) {
        g_state.running.store(false);
        pthread_mutex_unlock(&g_state.mutex);
        
        if (g_state.renderThread != 0) {
            pthread_join(g_state.renderThread, nullptr);
            g_state.renderThread = 0;
        }
        
        pthread_mutex_lock(&g_state.mutex);
    }
    
    // Clean up OpenGL resources
    if (g_state.display != EGL_NO_DISPLAY && g_state.context != EGL_NO_CONTEXT) {
        eglMakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context);
        
        if (g_state.framebuffer != 0) {
            glDeleteFramebuffers(1, &g_state.framebuffer);
            g_state.framebuffer = 0;
        }
        if (g_state.texture != 0) {
            glDeleteTextures(1, &g_state.texture);
            g_state.texture = 0;
        }
        if (g_state.renderbuffer != 0) {
            glDeleteRenderbuffers(1, &g_state.renderbuffer);
            g_state.renderbuffer = 0;
        }
    }
    
    cleanupEGL();
    
    pthread_mutex_unlock(&g_state.mutex);
    return 0;
}

int removeSubWindow(void* win) {
    LOGI("removeSubWindow: window=%p", win);
    
    pthread_mutex_lock(&g_state.mutex);
    
    if (g_state.window == win) {
        if (g_state.surface != EGL_NO_SURFACE && g_state.display != EGL_NO_DISPLAY) {
            eglDestroySurface(g_state.display, g_state.surface);
            g_state.surface = EGL_NO_SURFACE;
        }
        
        if (g_state.window) {
            ANativeWindow_release(g_state.window);
            g_state.window = nullptr;
        }
    }
    
    pthread_mutex_unlock(&g_state.mutex);
    return 0;
}

} // extern "C"
