cmake_minimum_required(VERSION 3.22)
project(resticky_whisper)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
set(GGML_NATIVE OFF)
set(GGML_OPENMP ON)
set(GGML_CCACHE OFF)
file(GLOB OMP_STATIC "${ANDROID_TOOLCHAIN_ROOT}/lib/clang/*/lib/linux/aarch64/libomp.a")
set(OpenMP_omp_LIBRARY ${OMP_STATIC} CACHE FILEPATH "" FORCE)
set(GGML_CPU_ARM_ARCH armv8.6-a+dotprod+i8mm+fp16)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720)
FetchContent_Declare(opus
    URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
FetchContent_MakeAvailable(whisper opus)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper opus)
