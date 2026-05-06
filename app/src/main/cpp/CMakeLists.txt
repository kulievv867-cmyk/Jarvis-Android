cmake_minimum_required(VERSION 3.22.1)

project(jarvis-whisper)

set(CMAKE_CXX_STANDARD 17)

# Tell whisper.cpp / ggml that we're a CPU-only Android build. The vendored
# whisper.cpp source lives under ./whisper/.
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper)

# Disable everything that doesn't make sense on Android: tools, examples,
# tests, GPU backends, server.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_OPENVINO   OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML         OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO       OFF CACHE BOOL "" FORCE)
set(WHISPER_CCACHE         OFF CACHE BOOL "" FORCE)

set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_HIP               OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL            OFF CACHE BOOL "" FORCE)
set(GGML_RPC               OFF CACHE BOOL "" FORCE)
set(GGML_SYCL              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI      OFF CACHE BOOL "" FORCE)
# Force ggml-cpu only.
set(GGML_CPU               ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)

# We don't want whisper.cpp to install anything.
set(WHISPER_INSTALL OFF CACHE BOOL "" FORCE)
set(GGML_INSTALL    OFF CACHE BOOL "" FORCE)

# Treat warnings as warnings (Android NDK clang trips on a few things in
# whisper.cpp / ggml).
add_compile_options(-Wno-unused-function -Wno-unused-variable -Wno-deprecated-declarations)

add_subdirectory(${WHISPER_LIB_DIR})

# JNI library.
add_library(jarvis_whisper SHARED whisper-jni.c)

target_include_directories(jarvis_whisper PRIVATE
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(jarvis_whisper
    whisper
    ${LOG_LIB}
    ${ANDROID_LIB}
)

# Strip the resulting .so for size in release builds — debug builds keep
# symbols so crashes are diagnosable.
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(jarvis_whisper PRIVATE -Wl,--gc-sections -Wl,--strip-all)
    target_compile_options(jarvis_whisper PRIVATE -Os -ffunction-sections -fdata-sections)
endif()
