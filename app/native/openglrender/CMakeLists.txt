# Copyright Disclaimer: AI-Generated Content
# This file was created by GitHub Copilot, an AI coding assistant.
# AI-generated content is not subject to copyright protection and is provided
# without any warranty, express or implied, including warranties of merchantability,
# fitness for a particular purpose, or non-infringement.
# Use at your own risk.

cmake_minimum_required(VERSION 3.10)
project(OpenglRender)

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Source files
set(SOURCES
    src/openglrender.cpp
    src/renderer.cpp
    src/pipe_connection.cpp
)

# Create shared library
add_library(OpenglRender SHARED ${SOURCES})

# Link against required Android libraries
target_link_libraries(OpenglRender
    android
    log
    EGL
    GLESv2
)
