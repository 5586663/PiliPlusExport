cmake_minimum_required(VERSION 3.22.1)
project("ffmpeg_shim" C)

# ---- 1. JNI 封装库（Java 调这个） ----
add_library(ffmpeg_shim SHARED
        jni_shim.c)

find_library(log-lib log)
target_link_libraries(ffmpeg_shim
        ${log-lib}
        ffmpeg_core)          # 由下面的 IMPORTED 目标提供

# 16 KB page size（Android 15+）
target_link_options(ffmpeg_shim PRIVATE "-Wl,-z,max-page-size=16384")

# ---- 2. 预编译 libffmpeg_core.so 作为 IMPORTED 库 ----
#
# 文件位置：app/src/main/jniLibs/${ANDROID_ABI}/libffmpeg_core.so
# 该 .so 来自 hlbmerge_flutter 仓库的 ffmpeg_xmake 子模块，
# 由 CI 在构建前下载（见 .github/workflows/build.yml）。
#
# 注意：只提供 arm64-v8a。若需其他 ABI，须自行用 xmake 编译。
set(FFMPEG_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../jniLibs/${ANDROID_ABI})

if (NOT EXISTS "${FFMPEG_CORE_DIR}/libffmpeg_core.so")
    message(FATAL_ERROR
        "缺少 libffmpeg_core.so，期望路径：${FFMPEG_CORE_DIR}\n"
        "请确认 CI 的下载步骤成功，或手动放入该目录。")
endif()

add_library(ffmpeg_core SHARED IMPORTED)
set_target_properties(ffmpeg_core PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_CORE_DIR}/libffmpeg_core.so")

message(STATUS "ffmpeg_core 使用：${FFMPEG_CORE_DIR}/libffmpeg_core.so")
