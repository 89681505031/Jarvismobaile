cmake_minimum_required(VERSION 3.22.1)

project(jarvisbrain LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(JARVIS_BRAIN_NATIVE "Build the full on-device neural runtime" ON)

# The full local LLM backend is compiled for ARM64 phones. Other ABIs receive a
# tiny JNI stub so the rest of JARVIS remains installable and functional.
# Diagnostic builds may also disable the heavy neural target explicitly.
if(JARVIS_BRAIN_NATIVE AND ANDROID_ABI STREQUAL "arm64-v8a")
    include(FetchContent)

    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

    # Pinned source: build-time dependency only. No network request is made by
    # the Android app at runtime.
    FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/6c7a87f7e5e5cd75b8a641c3471f2dee84a6ed17.tar.gz
    )
    FetchContent_MakeAvailable(llama_cpp)

    add_library(jarvisbrain SHARED jarvis_brain.cpp)
    target_include_directories(jarvisbrain PRIVATE
        ${llama_cpp_SOURCE_DIR}/include
        ${llama_cpp_SOURCE_DIR}/ggml/include
    )
    target_link_libraries(jarvisbrain PRIVATE llama android log)
else()
    add_library(jarvisbrain SHARED jarvis_brain_stub.cpp)
    target_link_libraries(jarvisbrain PRIVATE android log)
endif()
