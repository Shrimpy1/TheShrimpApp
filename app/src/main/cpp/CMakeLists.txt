cmake_minimum_required(VERSION 3.22.1)
project(jpeg_lossless_optimizer LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(ExternalProject)

# libjpeg-turbo does not support add_subdirectory() as an embedded CMake subproject. Build the
# pinned source tree as a separate CMake project, then link its installed static library. See the
# README in this bundle for the one-time Git submodule setup.
set(LIBJPEG_TURBO_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo")
if (NOT EXISTS "${LIBJPEG_TURBO_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "Missing libjpeg-turbo source. See README.md and initialize the app/src/main/cpp/libjpeg-turbo Git submodule.")
endif ()

set(LIBJPEG_TURBO_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build")
set(LIBJPEG_TURBO_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-install")
set(LIBJPEG_TURBO_STATIC_LIB "${LIBJPEG_TURBO_INSTALL_DIR}/lib/${CMAKE_STATIC_LIBRARY_PREFIX}jpeg${CMAKE_STATIC_LIBRARY_SUFFIX}")

ExternalProject_Add(libjpeg_turbo_external
        SOURCE_DIR "${LIBJPEG_TURBO_SOURCE_DIR}"
        BINARY_DIR "${LIBJPEG_TURBO_BINARY_DIR}"
        DOWNLOAD_COMMAND ""
        UPDATE_COMMAND ""
        PATCH_COMMAND ""
        CMAKE_GENERATOR "${CMAKE_GENERATOR}"
        CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}"
        "-DCMAKE_INSTALL_PREFIX=${LIBJPEG_TURBO_INSTALL_DIR}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DCMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DCMAKE_INSTALL_LIBDIR=lib"
        "-DENABLE_SHARED=OFF"
        "-DENABLE_STATIC=ON"
        "-DWITH_SIMD=OFF"
        "-DWITH_TURBOJPEG=OFF"
        "-DWITH_TOOLS=OFF"
        "-DWITH_TESTS=OFF"
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --config $<CONFIG>
        INSTALL_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target install --config $<CONFIG>
        BUILD_BYPRODUCTS "${LIBJPEG_TURBO_STATIC_LIB}"
)

add_library(jpeg_turbo_static UNKNOWN IMPORTED GLOBAL)
set_target_properties(jpeg_turbo_static PROPERTIES
        IMPORTED_LOCATION "${LIBJPEG_TURBO_STATIC_LIB}"
)

add_library(jpeg_lossless_optimizer SHARED jpeg_lossless_optimizer.cpp)
add_dependencies(jpeg_lossless_optimizer libjpeg_turbo_external)
target_include_directories(jpeg_lossless_optimizer PRIVATE
        "${LIBJPEG_TURBO_SOURCE_DIR}/src"
        "${LIBJPEG_TURBO_BINARY_DIR}"
)
target_compile_features(jpeg_lossless_optimizer PRIVATE cxx_std_17)
target_link_libraries(jpeg_lossless_optimizer PRIVATE jpeg_turbo_static log)
