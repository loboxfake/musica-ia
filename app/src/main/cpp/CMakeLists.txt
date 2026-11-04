#
# SPDX-FileCopyrightText: Copyright 2025 Arm Limited and/or its affiliates
# SPDX-License-Identifier: Apache-2.0
#
cmake_minimum_required(VERSION 3.22.1)
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
    set(EXECUTORCH_SOURCE_DIR \${CMAKE_SOURCE_DIR}/executorch)
    FetchContent_Declare(executorch
        SOURCE_DIR \${EXECUTORCH_SOURCE_DIR}
        GIT_REPOSITORY https://github.com/pytorch/executorch.git
        GIT_TAG v1.0.0
    )
    FetchContent_Populate(executorch)
else()
    set(EXECUTORCH_SOURCE_DIR \${ET_SRC_PATH})
endif()

add_subdirectory(\${EXECUTORCH_SOURCE_DIR} \${CMAKE_BINARY_DIR}/executorch)
add_subdirectory(\${EXECUTORCH_SOURCE_DIR}/extension/llm/tokenizers \${CMAKE_BINARY_DIR}/tokenizers)

add_library(audiogen SHARED audiogen_jni.cpp)
target_compile_options(audiogen PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(
    audiogen
    PUBLIC executorch optimized_native_cpu_ops_lib
           xnnpack_backend extension_module_static extension_tensor tokenizers
    PRIVATE log
)
