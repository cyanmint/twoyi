// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * Open-source OpenGL Renderer Library
 * Based on the Anbox project implementation
 * 
 * This library provides OpenGL ES rendering support for containerized Android
 * by handling communication through QEMU pipes (/opengles, /opengles2, /opengles3)
 */

#ifndef OPENGLRENDER_H
#define OPENGLRENDER_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize and start the OpenGL renderer
 * @param win Native window handle (ANativeWindow*)
 * @param width Virtual display width
 * @param height Virtual display height
 * @param xdpi X-axis DPI
 * @param ydpi Y-axis DPI
 * @param fps Target frame rate
 * @return 0 on success, negative on error
 */
int startOpenGLRenderer(void* win, int width, int height, int xdpi, int ydpi, int fps);

/**
 * Set the native window for rendering
 * @param window Native window handle
 * @return 0 on success, negative on error
 */
int setNativeWindow(void* window);

/**
 * Reset/resize the rendering subwindow
 * @param p_window Native window handle
 * @param wx X position
 * @param wy Y position
 * @param ww Window width
 * @param wh Window height
 * @param fbw Framebuffer width
 * @param fbh Framebuffer height
 * @param dpr Device pixel ratio
 * @param zRot Z-axis rotation
 * @return 0 on success, negative on error
 */
int resetSubWindow(void* p_window, int wx, int wy, int ww, int wh, 
                   int fbw, int fbh, float dpr, float zRot);

/**
 * Remove the rendering subwindow
 * @param window Native window handle
 * @return 0 on success, negative on error
 */
int removeSubWindow(void* window);

/**
 * Destroy the OpenGL subwindow and cleanup resources
 * @return 0 on success, negative on error
 */
int destroyOpenGLSubwindow();

/**
 * Trigger a repaint of the OpenGL display
 */
void repaintOpenGLDisplay();

#ifdef __cplusplus
}
#endif

#endif // OPENGLRENDER_H
