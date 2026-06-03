project(NitroPush)
cmake_minimum_required(VERSION 3.9.0)

set (PACKAGE_NAME NitroPush)
set (CMAKE_VERBOSE_MAKEFILE ON)
set (CMAKE_CXX_STANDARD 20)

# Enable Raw Props parsing in react-native (for Nitro Views)
add_compile_options(-DRN_SERIALIZABLE_STATE=1)

# ── Vendored bzip2 (required by bspatch; not in Android NDK sysroot) ────────
include(FetchContent)
FetchContent_Declare(
  bzip2
  URL      https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz
  URL_HASH SHA256=ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269
)
FetchContent_MakeAvailable(bzip2)

set(BZIP2_SOURCES
  ${bzip2_SOURCE_DIR}/blocksort.c
  ${bzip2_SOURCE_DIR}/huffman.c
  ${bzip2_SOURCE_DIR}/crctable.c
  ${bzip2_SOURCE_DIR}/randtable.c
  ${bzip2_SOURCE_DIR}/compress.c
  ${bzip2_SOURCE_DIR}/decompress.c
  ${bzip2_SOURCE_DIR}/bzlib.c
)

# ── Define C++ library and add all sources ───────────────────────────────────
add_library(${PACKAGE_NAME} SHARED
        src/main/cpp/cpp-adapter.cpp
        # Experimental delta updates: vendored bspatch + JNI glue
        src/main/cpp/bspatch/bspatch.c
        src/main/cpp/jni_bspatch.c
        ${BZIP2_SOURCES}
)

# Add Nitrogen specs :)
include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/NitroPush+autolinking.cmake)

# Set up local includes
include_directories(
        "src/main/cpp"
        "../cpp"
        ${bzip2_SOURCE_DIR}
)

find_library(LOG_LIB log)

# Link all libraries together
target_link_libraries(
        ${PACKAGE_NAME}
        ${LOG_LIB}
        android                                   # <-- Android core
        z                                         # zlib (in NDK sysroot)
)
