#
# SPDX-FileCopyrightText: Copyright 2025 Arm Limited and/or its affiliates
# SPDX-License-Identifier: Apache-2.0
#
cmake_minimum_required(VERSION 3.29)
project(audiogen_runner LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(EXECUTORCH_BUILD_EXTENSION_DATA_LOADER "" ON)
option(EXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP "" ON)
option(EXECUTORCH_BUILD_EXTENSION_MODULE "" ON)
option(EXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR "" ON)
option(EXECUTORCH_BUILD_EXTENSION_TENSOR "" ON)
option(EXECUTORCH_BUILD_KERNELS_OPTIMIZED "" ON)
option(EXECUTORCH_BUILD_XNNPACK "" ON)
option(EXECUTORCH_ENABLE_LOGGING "" ON)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

if(NOT ET_SRC_PATH)
    include(FetchContent)
    set(EXECUTORCH_SOURCE_DIR ${CMAKE_SOURCE_DIR}/executorch)
    FetchContent_Declare(executorch
        SOURCE_DIR ${EXECUTORCH_SOURCE_DIR}
        GIT_REPOSITORY https://github.com/pytorch/executorch.git
        GIT_TAG v1.0.0
    )
    FetchContent_Populate(executorch)
else()
    set(EXECUTORCH_SOURCE_DIR ${ET_SRC_PATH})
endif()

add_subdirectory(${EXECUTORCH_SOURCE_DIR} ${CMAKE_BINARY_DIR}/executorch)
add_subdirectory(${EXECUTORCH_SOURCE_DIR}/extension/llm/tokenizers ${CMAKE_BINARY_DIR}/tokenizers)

# SentencePiece's command-line targets are also built by the Android Ninja
# default target. Its protobuf-lite sources call __android_log_write on Android,
# so explicitly link the NDK logging library into those executables.
if(ANDROID)
    foreach(_spm_target spm_encode spm_decode spm_normalize spm_train spm_export_vocab spm_eval)
        if(TARGET ${_spm_target})
            target_link_libraries(${_spm_target} PRIVATE log)
        endif()
    endforeach()
endif()

# LAME 3.100: LGPL MP3 encoder, fetched at an immutable source commit.
include(FetchContent)
FetchContent_Declare(lame_android_source
    GIT_REPOSITORY https://github.com/xieyangxuejun/libmp3lame-android.git
    GIT_TAG bab342ae0a1e2caa545a48e5e950a0b1e36b9004
)
FetchContent_GetProperties(lame_android_source)
if(NOT lame_android_source_POPULATED)
    FetchContent_Populate(lame_android_source)
endif()
set(LAME_ROOT ${lame_android_source_SOURCE_DIR}/jni)
add_library(mp3lame SHARED
    ${LAME_ROOT}/libmp3lame/bitstream.c
    ${LAME_ROOT}/libmp3lame/fft.c
    ${LAME_ROOT}/libmp3lame/id3tag.c
    ${LAME_ROOT}/libmp3lame/mpglib_interface.c
    ${LAME_ROOT}/libmp3lame/presets.c
    ${LAME_ROOT}/libmp3lame/quantize.c
    ${LAME_ROOT}/libmp3lame/reservoir.c
    ${LAME_ROOT}/libmp3lame/tables.c
    ${LAME_ROOT}/libmp3lame/util.c
    ${LAME_ROOT}/libmp3lame/VbrTag.c
    ${LAME_ROOT}/libmp3lame/encoder.c
    ${LAME_ROOT}/libmp3lame/gain_analysis.c
    ${LAME_ROOT}/libmp3lame/lame.c
    ${LAME_ROOT}/libmp3lame/newmdct.c
    ${LAME_ROOT}/libmp3lame/psymodel.c
    ${LAME_ROOT}/libmp3lame/quantize_pvt.c
    ${LAME_ROOT}/libmp3lame/set_get.c
    ${LAME_ROOT}/libmp3lame/takehiro.c
    ${LAME_ROOT}/libmp3lame/vbrquantize.c
    ${LAME_ROOT}/libmp3lame/version.c
)
target_include_directories(mp3lame PUBLIC ${LAME_ROOT}/include PRIVATE ${LAME_ROOT}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS=1 HAVE_STDINT_H=1 HAVE_INTTYPES_H=1 HAVE_ERRNO_H=1 HAVE_FCNTL_H=1 ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O3)
target_link_libraries(mp3lame PRIVATE m)

add_library(mp3encoder SHARED mp3_encoder.cpp)
target_include_directories(mp3encoder PRIVATE ${LAME_ROOT}/include)
target_compile_options(mp3encoder PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(mp3encoder PRIVATE mp3lame log)

add_library(audiogen SHARED audiogen_jni.cpp)
target_compile_options(audiogen PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(
    audiogen
    PUBLIC executorch optimized_native_cpu_ops_lib
           xnnpack_backend extension_module_static extension_tensor tokenizers
    PRIVATE log
)
