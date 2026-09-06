cmake_minimum_required(VERSION 3.22.1)
project(sage_brain LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "Pinned llama.cpp source is required for Sage Brain")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# Keep release binaries reproducible and avoid embedding the builder workspace path.
get_filename_component(LLAMA_DIR "${LLAMA_DIR}" REALPATH)
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=app/src/main/cpp"
    "-ffile-prefix-map=${LLAMA_DIR}=third_party/llama.cpp"
)

# llama.cpp stamps its Git state into the runtime library. A copied checkout can
# have stale index metadata even when its tracked bytes are identical, which
# makes ggml report the exact pinned commit as "-dirty" and breaks reproducible
# native output. Normalize and prove the tracked checkout before configuring it.
set(SAGE_LLAMA_PIN "d73c1d6b22a2d3ecc74c2c9cde354015ee72e862")
find_program(SAGE_GIT_EXECUTABLE NAMES git git.exe NO_CMAKE_FIND_ROOT_PATH)
if (NOT SAGE_GIT_EXECUTABLE)
    message(FATAL_ERROR "git is required to verify the pinned Sage Brain source")
endif()
execute_process(
    COMMAND "${SAGE_GIT_EXECUTABLE}" reset --hard "${SAGE_LLAMA_PIN}"
    WORKING_DIRECTORY "${LLAMA_DIR}"
    RESULT_VARIABLE SAGE_LLAMA_RESET_RESULT
)
if (NOT SAGE_LLAMA_RESET_RESULT EQUAL 0)
    message(FATAL_ERROR "Could not normalize pinned llama.cpp checkout")
endif()
execute_process(
    COMMAND "${SAGE_GIT_EXECUTABLE}" update-index -q --refresh
    WORKING_DIRECTORY "${LLAMA_DIR}"
    RESULT_VARIABLE SAGE_LLAMA_REFRESH_RESULT
)
execute_process(
    COMMAND "${SAGE_GIT_EXECUTABLE}" diff-index --quiet HEAD -- .
    WORKING_DIRECTORY "${LLAMA_DIR}"
    RESULT_VARIABLE SAGE_LLAMA_DIRTY_RESULT
)
if (NOT SAGE_LLAMA_REFRESH_RESULT EQUAL 0 OR NOT SAGE_LLAMA_DIRTY_RESULT EQUAL 0)
    message(FATAL_ERROR "Pinned llama.cpp checkout is not clean after normalization")
endif()

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

add_library(sage-brain SHARED
    sage_brain.cpp
)

target_include_directories(sage-brain PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_compile_options(sage-brain PRIVATE
    -O3
    -fvisibility=hidden
)

# The stripped release code is deterministic; omit the path-sensitive pre-strip ID.
target_link_options(sage-brain PRIVATE "-Wl,--build-id=none")

target_link_libraries(sage-brain
    llama
    log
    android
)
