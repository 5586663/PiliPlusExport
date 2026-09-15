package com.piliplus.export;

/**
 * libffmpeg_core.so 的 JNI 封装。
 *
 * 底层是 hlbmerge_flutter 项目 ffmpeg_xmake 子模块编译出的
 * libffmpeg_core.so（FFmpeg 7.1，仅 remux，不含编解码器）。
 *
 * 原生符号（ffmpeg_merge.h）：
 *   char* merge_audio_video(v, a, out);   // 成功 NULL，失败错误串
 *   char* merge_videos(paths, n, out);
 *   char* ffmpeg_version(void);
 *
 * 中间经过 jni_shim.c 做符号名与参数转换。
 *
 * 生命周期：静态加载一次；native 方法均为同步阻塞，
 *          调用方须自行放到后台线程（本类不代劳）。
 *
 * 内存：C 侧返回的串已由 shim 释放，Java 侧只拿 String，无泄漏。
 */
public final class FfmpegCore {

    private FfmpegCore() {}

    private static boolean sLoaded = false;
    private static String sLoadError = "";

    static {
        try {
            System.loadLibrary("ffmpeg_shim");   // 依赖 libffmpeg_core.so，由 linker 自动加载
            sLoaded = true;
        } catch (Throwable t) {
            sLoadError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 原生库是否可用 */
    public static boolean available() { return sLoaded; }

    /** 加载失败原因，可用时为空串 */
    public static String loadError() { return sLoadError; }

    // ---------------- 原生方法 ----------------

    private static native String nativeVersion();

    private static native String nativeMergeAudioVideo(
            String videoPath, String audioPath, String outputPath);

    private static native String nativeMergeVideos(
            String[] videoPaths, String outputPath);

    // ---------------- 安全封装 ----------------

    /**
     * 合并分离的音视频。
     *
     * @return null 表示成功；非空为错误描述
     */
    public static String mergeAudioVideo(String videoPath, String audioPath, String outputPath) {
        if (!sLoaded) return "原生库未加载：" + sLoadError;
        if (videoPath == null || audioPath == null || outputPath == null) {
            return "参数为空";
        }
        try {
            return nativeMergeAudioVideo(videoPath, audioPath, outputPath);
        } catch (Throwable t) {
            return "JNI 调用异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 顺序拼接多个视频分片（B站 blv）。
     *
     * @return null 表示成功；非空为错误描述
     */
    public static String mergeVideos(String[] videoPaths, String outputPath) {
        if (!sLoaded) return "原生库未加载：" + sLoadError;
        if (videoPaths == null || videoPaths.length == 0) return "输入分片为空";
        if (outputPath == null) return "输出路径为空";
        try {
            return nativeMergeVideos(videoPaths, outputPath);
        } catch (Throwable t) {
            return "JNI 调用异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** FFmpeg 版本号，如 "7.1"；失败返回 null */
    public static String version() {
        if (!sLoaded) return null;
        try {
            return nativeVersion();
        } catch (Throwable t) {
            return null;
        }
    }
}
