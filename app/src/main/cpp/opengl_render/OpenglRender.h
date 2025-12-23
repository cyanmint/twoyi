// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

#ifndef OPENGL_RENDER_H
#define OPENGL_RENDER_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Start the OpenGL renderer with the given window and parameters
 * @param win Native window handle
 * @param width Window width
 * @param height Window height
 * @param xdpi X DPI
 * @param ydpi Y DPI
 * @param fps Target frames per second
 * @return 0 on success, negative on error
 */
int startOpenGLRenderer(void* win, int width, int height, int xdpi, int ydpi, int fps);

/**
 * Set the native window for rendering
 * @param win Native window handle
 * @return 0 on success, negative on error
 */
int setNativeWindow(void* win);

/**
 * Reset the sub-window with new parameters
 * @param p_window Window handle
 * @param wx Window X position
 * @param wy Window Y position
 * @param ww Window width
 * @param wh Window height
 * @param fbw Framebuffer width
 * @param fbh Framebuffer height
 * @param dpr Device pixel ratio
 * @param zRot Z-axis rotation
 * @return 0 on success, negative on error
 */
int resetSubWindow(void* p_window, int wx, int wy, int ww, int wh, int fbw, int fbh, float dpr, float zRot);

/**
 * Request a repaint of the OpenGL display
 */
void repaintOpenGLDisplay();

/**
 * Destroy the OpenGL sub-window and clean up resources
 * @return 0 on success, negative on error
 */
int destroyOpenGLSubwindow();

/**
 * Remove a sub-window
 * @param win Window handle to remove
 * @return 0 on success, negative on error
 */
int removeSubWindow(void* win);

#ifdef __cplusplus
}
#endif

#endif // OPENGL_RENDER_H
