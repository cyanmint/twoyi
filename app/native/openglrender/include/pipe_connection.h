// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * QEMU Pipe Connection Handler
 * Handles connections to /opengles, /opengles2, /opengles3 in container
 * Based on Anbox pipe connection implementation
 */

#ifndef PIPE_CONNECTION_H
#define PIPE_CONNECTION_H

#include <string>
#include <memory>
#include <thread>
#include <atomic>

namespace openglrender {

class Renderer;

/**
 * Pipe connection handler for OpenGL ES communication
 */
class PipeConnection {
public:
    PipeConnection(std::shared_ptr<Renderer> renderer);
    ~PipeConnection();

    /**
     * Start listening for pipe connections
     * @return true on success
     */
    bool start();

    /**
     * Stop listening and cleanup
     */
    void stop();

    /**
     * Check if the connection is running
     */
    bool isRunning() const { return m_running; }

private:
    /**
     * Main connection handler thread
     */
    void connectionThread();

    /**
     * Handle a single client connection
     * @param socketFd Socket file descriptor
     */
    void handleClient(int socketFd);

    /**
     * Identify the client type from pipe identifier
     * @param identifier Pipe identifier string (e.g., "pipe:opengles")
     * @return Client type enum
     */
    int identifyClient(const std::string& identifier);

    /**
     * Create socket listener for QEMU pipes
     * @return Socket file descriptor or -1 on error
     */
    int createListener();

    std::shared_ptr<Renderer> m_renderer;
    std::thread m_thread;
    std::atomic<bool> m_running;
    int m_listenerSocket;
};

} // namespace openglrender

#endif // PIPE_CONNECTION_H
