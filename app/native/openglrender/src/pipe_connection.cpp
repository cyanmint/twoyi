// Copyright Disclaimer: AI-Generated Content
// This file was created by GitHub Copilot, an AI coding assistant.
// AI-generated content is not subject to copyright protection and is provided
// without any warranty, express or implied, including warranties of merchantability,
// fitness for a particular purpose, or non-infringement.
// Use at your own risk.

/*
 * QEMU Pipe Connection Implementation
 * Handles connections to /opengles, /opengles2, /opengles3
 * Based on Anbox pipe connection implementation
 */

#include "pipe_connection.h"
#include "renderer.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <vector>

#define LOG_TAG "PipeConnection"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace openglrender {

// Client type identifiers
enum ClientType {
    INVALID = 0,
    OPENGLES = 1,
    OPENGLES2 = 2,
    OPENGLES3 = 3
};

PipeConnection::PipeConnection(std::shared_ptr<Renderer> renderer)
    : m_renderer(renderer),
      m_running(false),
      m_listenerSocket(-1) {
}

PipeConnection::~PipeConnection() {
    stop();
}

bool PipeConnection::start() {
    if (m_running) {
        LOGW("Pipe connection already running");
        return true;
    }

    LOGI("Starting pipe connection handler");

    m_listenerSocket = createListener();
    if (m_listenerSocket < 0) {
        LOGE("Failed to create listener socket");
        return false;
    }

    m_running = true;
    m_thread = std::thread(&PipeConnection::connectionThread, this);

    LOGI("Pipe connection handler started");
    return true;
}

void PipeConnection::stop() {
    if (!m_running) {
        return;
    }

    LOGI("Stopping pipe connection handler");
    m_running = false;

    if (m_listenerSocket >= 0) {
        close(m_listenerSocket);
        m_listenerSocket = -1;
    }

    if (m_thread.joinable()) {
        m_thread.join();
    }

    LOGI("Pipe connection handler stopped");
}

int PipeConnection::createListener() {
    // Create a Unix domain socket for listening to QEMU pipe connections
    int sockFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sockFd < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return -1;
    }

    // Set socket to non-blocking mode
    int flags = fcntl(sockFd, F_GETFL, 0);
    fcntl(sockFd, F_SETFL, flags | O_NONBLOCK);

    // Bind to a socket path for QEMU pipe connections
    // In a containerized environment, this would connect to the QEMU pipe infrastructure
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    
    // Try multiple socket paths for OpenGL ES pipes
    const char* socketPaths[] = {
        "/dev/qemu_pipe",
        "/tmp/qemu_pipe",
        nullptr
    };

    bool bound = false;
    for (int i = 0; socketPaths[i] != nullptr; i++) {
        strncpy(addr.sun_path, socketPaths[i], sizeof(addr.sun_path) - 1);
        
        // Remove existing socket file if it exists
        unlink(addr.sun_path);
        
        if (bind(sockFd, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            LOGI("Bound to socket: %s", socketPaths[i]);
            bound = true;
            break;
        }
    }

    if (!bound) {
        LOGE("Failed to bind socket: %s", strerror(errno));
        close(sockFd);
        return -1;
    }

    // Listen for connections
    if (listen(sockFd, 5) < 0) {
        LOGE("Failed to listen on socket: %s", strerror(errno));
        close(sockFd);
        return -1;
    }

    return sockFd;
}

void PipeConnection::connectionThread() {
    LOGI("Connection thread started");

    while (m_running) {
        // Accept incoming connections
        struct sockaddr_un clientAddr;
        socklen_t clientLen = sizeof(clientAddr);
        
        int clientFd = accept(m_listenerSocket, (struct sockaddr*)&clientAddr, &clientLen);
        if (clientFd < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // No connection available, sleep briefly
                usleep(10000); // 10ms
                continue;
            }
            LOGE("Failed to accept connection: %s", strerror(errno));
            continue;
        }

        LOGI("Accepted new client connection");
        
        // Handle the client in a separate thread to allow multiple connections
        std::thread([this, clientFd]() {
            handleClient(clientFd);
        }).detach();
    }

    LOGI("Connection thread stopped");
}

void PipeConnection::handleClient(int socketFd) {
    LOGD("Handling client connection, fd=%d", socketFd);

    // Read the client identifier
    // Format: "pipe:<name>\0" (e.g., "pipe:opengles\0", "pipe:opengles2\0")
    std::vector<char> buffer;
    buffer.reserve(256);

    while (buffer.size() < 255) {
        char byte;
        ssize_t n = recv(socketFd, &byte, 1, 0);
        if (n <= 0) {
            LOGE("Failed to read client identifier");
            close(socketFd);
            return;
        }

        buffer.push_back(byte);
        if (byte == '\0') {
            break;
        }
    }

    std::string identifier(buffer.data());
    LOGI("Client identifier: %s", identifier.c_str());

    int clientType = identifyClient(identifier);
    
    switch (clientType) {
        case OPENGLES:
        case OPENGLES2:
        case OPENGLES3:
            LOGI("Handling OpenGL ES client");
            // In a full implementation, this would process OpenGL ES commands
            // from the pipe and forward them to the renderer
            // For now, we acknowledge the connection
            {
                unsigned int flags = 0;
                send(socketFd, &flags, sizeof(flags), 0);
            }
            
            // Keep connection alive and process messages
            // This is a simplified version - a full implementation would
            // use a message processing loop similar to Anbox
            while (m_running) {
                char msgBuf[4096];
                ssize_t n = recv(socketFd, msgBuf, sizeof(msgBuf), 0);
                if (n <= 0) {
                    break;
                }
                // Process OpenGL ES commands here
                // This would involve parsing the command buffer and
                // calling appropriate OpenGL functions
            }
            break;

        default:
            LOGW("Unknown client type, closing connection");
            break;
    }

    close(socketFd);
    LOGD("Client connection closed");
}

int PipeConnection::identifyClient(const std::string& identifier) {
    if (identifier.find("pipe:opengles") == 0) {
        if (identifier.find("pipe:opengles2") == 0) {
            return OPENGLES2;
        } else if (identifier.find("pipe:opengles3") == 0) {
            return OPENGLES3;
        }
        return OPENGLES;
    }
    return INVALID;
}

} // namespace openglrender
