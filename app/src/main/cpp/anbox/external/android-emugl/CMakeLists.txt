# Don't treat any warnings as error as we take the source directly from
# upstream and just compile it.
set(CMAKE_C_FLAGS "-Wall")
set(CMAKE_CXX_FLAGS "-std=c++11 -Wall")

# Ensure -fPIC
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include_directories(
    ${PROJECT_SOURCE_DIR}
    ${PROJECT_SOURCE_DIR}/src
    ${PROJECT_SOURCE_DIR}/external/android-emugl/shared
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/include
    ${PROJECT_BINARY_DIR}/external/android-emugl/host/include
    ${PROJECT_SOURCE_DIR}/external/android-emugl/shared/OpenglCodecCommon
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/include/libOpenglRender
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs/GLESv1_dec
    ${PROJECT_BINARY_DIR}/external/android-emugl/host/libs/GLESv1_dec
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs/GLESv2_dec
    ${PROJECT_BINARY_DIR}/external/android-emugl/host/libs/GLESv2_dec
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs/renderControl_dec
    ${PROJECT_BINARY_DIR}/external/android-emugl/host/libs/renderControl_dec
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs/Translator/
    ${PROJECT_SOURCE_DIR}/external/android-emugl/host/libs/Translator/include
    ${MIRCLIENT_INCLUDE_DIRS})

add_subdirectory(host)
add_subdirectory(shared)
