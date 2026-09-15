/*
 * JNI 薄封装。
 *
 * 目的：libffmpeg_core.so 导出的是普通 C 符号（merge_audio_video 等），
 *      而 Java native 方法需要 JNI 命名符号（Java_com_piliplus_export_FfmpegCore_*）。
 *      本文件只做参数转换与字符串拷贝，不含任何业务逻辑。
 *
 * 符号来源：ffmpeg_merge.h（libffmpeg_core.so）
 *   char* merge_audio_video(const char* video, const char* audio, const char* out);
 *   char* merge_videos(const char** paths, int count, const char* out);
 *   char* ffmpeg_version(void);
 *
 * 约定：成功返回 NULL，失败返回 malloc 分配的字符串（调用方 free）。
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

/* 由 libffmpeg_core.so 提供 */
extern char *merge_audio_video(const char *video_path,
                              const char *audio_path,
                              const char *output_path);
extern char *merge_videos(const char **video_paths,
                         int path_count,
                         const char *output_path);
extern char *ffmpeg_version(void);

/* 把 C 串转成 Java String，并释放原串。ptr 可为 NULL（返回 null）。 */
static jstring take_string(JNIEnv *env, char *ptr) {
    if (ptr == NULL) return NULL;
    jstring s = (*env)->NewStringUTF(env, ptr);
    free(ptr);
    return s;
}

/* 把 jstring 转成 C 串（malloc 分配，调用方 free）。返回 NULL 表示入参为 null。 */
static char *to_cstr(JNIEnv *env, jstring js) {
    if (js == NULL) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, js, NULL);
    if (chars == NULL) return NULL;   /* OOM */
    char *out = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, js, chars);
    return out;
}

/* ---------------------------------------------------------------- */

JNIEXPORT jstring JNICALL
Java_com_piliplus_export_FfmpegCore_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return take_string(env, ffmpeg_version());
}

JNIEXPORT jstring JNICALL
Java_com_piliplus_export_FfmpegCore_nativeMergeAudioVideo(
        JNIEnv *env, jclass clazz,
        jstring videoPath, jstring audioPath, jstring outputPath) {
    (void) clazz;

    char *v = to_cstr(env, videoPath);
    char *a = to_cstr(env, audioPath);
    char *o = to_cstr(env, outputPath);

    if (v == NULL || a == NULL || o == NULL) {
        free(v); free(a); free(o);
        return (*env)->NewStringUTF(env, "参数为空或内存不足");
    }

    char *err = merge_audio_video(v, a, o);

    free(v); free(a); free(o);

    /* err == NULL 表示成功，返回 null 给 Java */
    return take_string(env, err);
}

JNIEXPORT jstring JNICALL
Java_com_piliplus_export_FfmpegCore_nativeMergeVideos(
        JNIEnv *env, jclass clazz,
        jobjectArray paths, jstring outputPath) {
    (void) clazz;

    if (paths == NULL) {
        return (*env)->NewStringUTF(env, "输入路径数组为空");
    }

    jsize count = (*env)->GetArrayLength(env, paths);
    if (count <= 0) {
        return (*env)->NewStringUTF(env, "输入路径数量为 0");
    }

    char **cpaths = (char **) calloc((size_t) count, sizeof(char *));
    if (cpaths == NULL) {
        return (*env)->NewStringUTF(env, "内存不足");
    }

    for (jsize i = 0; i < count; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, paths, i);
        cpaths[i] = to_cstr(env, js);
        if (js != NULL) (*env)->DeleteLocalRef(env, js);
        if (cpaths[i] == NULL) {
            for (jsize k = 0; k < i; k++) free(cpaths[k]);
            free(cpaths);
            return (*env)->NewStringUTF(env, "路径转换失败");
        }
    }

    char *o = to_cstr(env, outputPath);
    if (o == NULL) {
        for (jsize i = 0; i < count; i++) free(cpaths[i]);
        free(cpaths);
        return (*env)->NewStringUTF(env, "输出路径为空");
    }

    char *err = merge_videos((const char **) cpaths, (int) count, o);

    for (jsize i = 0; i < count; i++) free(cpaths[i]);
    free(cpaths);
    free(o);

    return take_string(env, err);
}
