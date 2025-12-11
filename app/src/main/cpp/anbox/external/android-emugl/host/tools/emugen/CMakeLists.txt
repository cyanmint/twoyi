if (NOT "${HOST_CMAKE_CXX_COMPILER}" STREQUAL "")
    set (CMAKE_CXX_COMPILER "${HOST_CMAKE_CXX_COMPILER}")
    # Cross compile options for android
    set (CMAKE_SYSROOT "")
    set (CMAKE_CXX_COMPILER_TARGET "")
    set (CMAKE_EXE_LINKER_FLAGS "")
    set (CMAKE_SHARED_LINKER_FLAGS "")
    set (CMAKE_MODULE_LINKER_FLAGS "")
    #set (CMAKE_CXX_FLAGS_DEBUG "")
endif()

set(SOURCES
    ApiGen.cpp
    EntryPoint.cpp
    main.cpp
    Parser.cpp
    strUtils.cpp
    TypeFactory.cpp)

add_executable(emugen ${SOURCES})
set_target_properties(emugen PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${PROJECT_BINARY_DIR}/external/android-emugl/host/tools/emugen")
target_link_options(emugen INTERFACE "")
