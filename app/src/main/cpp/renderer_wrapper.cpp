// renderer_wrapper.cpp
// FOSS OpenGL renderer implementation embedded directly into libtwoyi.so
// This provides a minimal working renderer using native Android OpenGL ES

#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <pthread.h>

#define LOG_TAG "TwoyiRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global state for the renderer
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static ANativeWindow* native_window = nullptr;
static bool renderer_initialized = false;
static pthread_mutex_t renderer_mutex = PTHREAD_MUTEX_INITIALIZER;

extern "C" {

// Helper function to initialize EGL
static bool initEGL(ANativeWindow* window) {
    pthread_mutex_lock(&renderer_mutex);
    
    if (renderer_initialized && display != EGL_NO_DISPLAY) {
        LOGI("EGL already initialized");
        pthread_mutex_unlock(&renderer_mutex);
        return true;
    }

    // Get EGL display
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        pthread_mutex_unlock(&renderer_mutex);
        return false;
    }

    // Initialize EGL
    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) {
        LOGE("eglInitialize failed");
        pthread_mutex_unlock(&renderer_mutex);
        return false;
    }
    LOGI("EGL initialized: %d.%d", major, minor);

    // Choose EGL config
    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        pthread_mutex_unlock(&renderer_mutex);
        return false;
    }

    // Create EGL context
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        pthread_mutex_unlock(&renderer_mutex);
        return false;
    }

    // Create EGL surface if window is provided
    if (window != nullptr) {
        surface = eglCreateWindowSurface(display, config, window, nullptr);
        if (surface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            pthread_mutex_unlock(&renderer_mutex);
            return false;
        }

        // Make context current
        if (!eglMakeCurrent(display, surface, surface, context)) {
            LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            pthread_mutex_unlock(&renderer_mutex);
            return false;
        }

        native_window = window;
        LOGI("EGL surface created and made current");
    }

    renderer_initialized = true;
    pthread_mutex_unlock(&renderer_mutex);
    return true;
}

int destroyOpenGLSubwindow() {
    LOGI("destroyOpenGLSubwindow called (FOSS renderer)");
    pthread_mutex_lock(&renderer_mutex);
    
    if (surface != EGL_NO_SURFACE) {
        eglDestroySurface(display, surface);
        surface = EGL_NO_SURFACE;
    }
    
    pthread_mutex_unlock(&renderer_mutex);
    return 0;
}

void repaintOpenGLDisplay() {
    LOGD("repaintOpenGLDisplay called (FOSS renderer)");
    pthread_mutex_lock(&renderer_mutex);
    
    if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE) {
        eglSwapBuffers(display, surface);
    }
    
    pthread_mutex_unlock(&renderer_mutex);
}

int setNativeWindow(void* window) {
    LOGI("setNativeWindow called (FOSS renderer)");
    
    if (window == nullptr) {
        LOGE("setNativeWindow: window is null");
        return -1;
    }
    
    return initEGL((ANativeWindow*)window) ? 0 : -1;
}

int resetSubWindow(
    void* p_window,
    int wx, int wy, int ww, int wh,
    int fbw, int fbh,
    float dpr, float z_rot) {
    LOGI("resetSubWindow called: window=%p, pos=(%d,%d), size=(%dx%d), fb=(%dx%d) (FOSS renderer)", 
         p_window, wx, wy, ww, wh, fbw, fbh);
    
    pthread_mutex_lock(&renderer_mutex);
    
    if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
        // Update viewport
        glViewport(wx, wy, ww, wh);
        
        // Clear the screen
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        if (surface != EGL_NO_SURFACE) {
            eglSwapBuffers(display, surface);
        }
    }
    
    pthread_mutex_unlock(&renderer_mutex);
    return 0;
}

int startOpenGLRenderer(
    void* win,
    int width, int height,
    int xdpi, int ydpi, int fps) {
    LOGI("startOpenGLRenderer called: %dx%d, dpi=%dx%d, fps=%d (FOSS renderer)", 
         width, height, xdpi, ydpi, fps);
    
    if (win == nullptr) {
        LOGE("startOpenGLRenderer: window is null");
        return -1;
    }
    
    ANativeWindow* window = (ANativeWindow*)win;
    
    // Initialize EGL with the window
    if (!initEGL(window)) {
        LOGE("Failed to initialize EGL");
        return -1;
    }
    
    // Set viewport
    glViewport(0, 0, width, height);
    
    // Clear to black
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    
    if (surface != EGL_NO_SURFACE) {
        eglSwapBuffers(display, surface);
    }
    
    LOGI("OpenGL renderer started successfully");
    return 0;
}

int removeSubWindow(void* window) {
    LOGI("removeSubWindow called (FOSS renderer)");
    
    pthread_mutex_lock(&renderer_mutex);
    
    if (surface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface);
        surface = EGL_NO_SURFACE;
    }
    
    native_window = nullptr;
    
    pthread_mutex_unlock(&renderer_mutex);
    return 0;
}

} // extern "C"
