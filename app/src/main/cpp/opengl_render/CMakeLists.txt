# Copyright Disclaimer: AI-Generated Content
# This file was created by GitHub Copilot, an AI coding assistant.
# AI-generated content is not subject to copyright protection and is provided
# without any warranty, express or implied, including warranties of merchantability,
# fitness for a particular purpose, or non-infringement.
# Use at your own risk.

cmake_minimum_required(VERSION 3.4.1)

project(OpenglRender)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the library
add_library(OpenglRender SHARED
    OpenglRender.cpp
)

# Include directories
target_include_directories(OpenglRender PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against Android libraries
target_link_libraries(OpenglRender
    android
    log
    EGL
    GLESv3
)
