// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * OpenGL Renderer Interface
 * Based on Anbox graphics renderer
 */

#ifndef RENDERER_H
#define RENDERER_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <memory>
#include <mutex>

namespace openglrender {

/**
 * Renderer class that manages EGL context and rendering operations
 */
class Renderer {
public:
    Renderer();
    ~Renderer();

    /**
     * Initialize the renderer with native display
     * @param nativeDisplay Native display handle
     * @return true on success
     */
    bool initialize(EGLNativeDisplayType nativeDisplay);

    /**
     * Create a rendering window surface
     * @param nativeWindow Native window handle
     * @return true on success
     */
    bool createWindowSurface(EGLNativeWindowType nativeWindow);

    /**
     * Update window dimensions
     * @param x X position
     * @param y Y position
     * @param width Window width
     * @param height Window height
     */
    void updateWindowSize(int x, int y, int width, int height);

    /**
     * Bind the rendering context
     * @return true on success
     */
    bool bind();

    /**
     * Unbind the rendering context
     */
    void unbind();

    /**
     * Swap buffers to display rendered content
     */
    void swapBuffers();

    /**
     * Cleanup and destroy renderer resources
     */
    void finalize();

    /**
     * Get the EGL display
     */
    EGLDisplay getDisplay() const { return m_eglDisplay; }

    /**
     * Get the current window surface
     */
    EGLSurface getWindowSurface() const { return m_windowSurface; }

private:
    EGLDisplay m_eglDisplay;
    EGLContext m_eglContext;
    EGLSurface m_windowSurface;
    EGLSurface m_pbufSurface;
    EGLContext m_pbufContext;
    EGLConfig m_eglConfig;
    
    int m_windowWidth;
    int m_windowHeight;
    int m_windowX;
    int m_windowY;
    
    std::mutex m_lock;
    bool m_initialized;
};

} // namespace openglrender

#endif // RENDERER_H
