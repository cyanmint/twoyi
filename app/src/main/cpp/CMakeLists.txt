# CMakeLists.txt for building FOSS OpenGL renderer (libOpenglRender.so)
# Based on Ananbox approach: https://github.com/Ananbox/ananbox
#
# This builds a FOSS alternative to the prebuilt libOpenglRender.so
# using the android-emugl library from the Android Open Source Project (AOSP)
# as integrated by the Ananbox project.
#
# The android-emugl library is licensed under Apache License 2.0

cmake_minimum_required(VERSION 3.22)
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
enable_language(C CXX ASM)

project("twoyi-foss-renderer")

# Build configuration
option(BUILD_ANANBOX_DEMO "Build Ananbox demo application" OFF)

# emugl configuration: use eglGetProcAddress for function loading
set(USE_EGL_GETPROCADDRESS 1)

# Find required dependencies
# Note: Boost is required by anbox
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/anbox/cmake" ${CMAKE_MODULE_PATH})

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/anbox/cmake/Boost.cmake")
    include(Boost)
    find_package(Boost COMPONENTS filesystem log_setup log serialization system thread program_options REQUIRED)
else()
    message(WARNING "Anbox submodule not initialized. Run: git submodule update --init --recursive")
endif()

# Add the anbox subdirectory which contains android-emugl implementation
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/anbox/CMakeLists.txt")
    add_subdirectory(anbox)
else()
    message(FATAL_ERROR "Anbox submodule not found. Please run: git submodule update --init --recursive")
endif()

# Include directories for android-emugl and anbox
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/src
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/shared
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/include
        ${CMAKE_BINARY_DIR}/anbox/external/android-emugl/host/include
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/shared/OpenglCodecCommon
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/libs
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/include/libOpenglRender
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/libs/GLESv1_dec
        ${CMAKE_BINARY_DIR}/anbox/external/android-emugl/host/libs/GLESv1_dec
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/libs/GLESv2_dec
        ${CMAKE_BINARY_DIR}/anbox/external/android-emugl/host/libs/GLESv2_dec
        ${CMAKE_CURRENT_SOURCE_DIR}/anbox/external/android-emugl/host/libs/renderControl_dec
        ${CMAKE_BINARY_DIR}/anbox/external/android-emugl/host/libs/renderControl_dec
        ${Boost_INCLUDE_DIRS}
)

# Create the FOSS OpenglRender library
# This is a wrapper around the anbox-core library which contains android-emugl
add_library(
        OpenglRender
        SHARED
        renderer_wrapper.cpp
)

# Link against required libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        OpenglRender
        anbox-core
        ${android-lib}
        ${log-lib}
)

# Set output properties
set_target_properties(OpenglRender PROPERTIES
    OUTPUT_NAME "OpenglRender"
    VERSION 1.0.0
    SOVERSION 1
)

message(STATUS "FOSS OpenGL Renderer configuration:")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  Android ABI: ${ANDROID_ABI}")
message(STATUS "  Android Platform: ${ANDROID_PLATFORM}")
message(STATUS "  Boost found: ${Boost_FOUND}")
message(STATUS "  Output: libOpenglRender.so")
