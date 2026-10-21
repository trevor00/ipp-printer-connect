cmake_minimum_required(VERSION 3.22.1)
project(ipp_streamed_jpeg LANGUAGES C)

include(ExternalProject)
set(JPEG_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo-3.2.0")
set(JPEG_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/jpeg-build")
file(MAKE_DIRECTORY "${JPEG_BINARY_DIR}")

# Upstream explicitly requires an independent configure, not add_subdirectory().
# All sources are vendored; native builds do not download code from the network.
ExternalProject_Add(jpeg_turbo_build
    SOURCE_DIR "${JPEG_SOURCE_DIR}"
    BINARY_DIR "${JPEG_BINARY_DIR}"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
        -DWITH_SIMD=OFF
        -DWITH_ARITH_ENC=OFF
        -DWITH_ARITH_DEC=OFF
    BUILD_COMMAND "${CMAKE_COMMAND}" --build "${JPEG_BINARY_DIR}" --target jpeg-static --parallel 2
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${JPEG_BINARY_DIR}/libjpeg.a"
)

add_library(ipp_jpeg_codec STATIC IMPORTED)
set_target_properties(ipp_jpeg_codec PROPERTIES IMPORTED_LOCATION "${JPEG_BINARY_DIR}/libjpeg.a")
add_dependencies(ipp_jpeg_codec jpeg_turbo_build)

add_library(ippjpeg SHARED streaming_jpeg.c)
add_dependencies(ippjpeg jpeg_turbo_build)
target_include_directories(ippjpeg PRIVATE "${JPEG_SOURCE_DIR}/src" "${JPEG_BINARY_DIR}")
target_compile_features(ippjpeg PRIVATE c_std_11)
target_compile_options(ippjpeg PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(ippjpeg PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(ippjpeg PRIVATE ipp_jpeg_codec)
