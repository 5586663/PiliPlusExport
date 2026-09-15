package com.piliplus.export;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条 B站缓存记录（一个单集）。
 *
 * 目录形态（Android 客户端）：
 *   root/<分组>/<单集>/
 *       entry.json        元数据
 *       danmaku.xml       弹幕
 *       video.m4s         视频流
 *       audio.m4s         音频流
 *       0.blv 1.blv …     旧格式分片（与 m4s 二选一）
 *       cover.jpg
 *
 * Windows 客户端结构类似；2024.03 后 m4s 带 32 字节加密头。
 */
public class CacheItem {

    /** 分组名（如合集标题），可能为空 */
    public String groupTitle = "";

    /** 单集标题，最终用作文件名 */
    public String title = "";

    /** 分P序号（entry.json 里的 p），无则 0 */
    public int p = 0;

    public String videoPath = "";
    public String audioPath = "";
    public String danmakuPath = "";

    /** blv 分片，已按文件名数字升序排列 */
    public List<String> blvPaths = new ArrayList<>();

    /** 源目录，排查用 */
    public String sourceDir = "";

    /** 是否可用原生 FFmpeg 合并 */
    public boolean canMerge() {
        boolean av = exists(videoPath) && exists(audioPath);
        boolean blv = blvPaths.size() >= 1 && exists(blvPaths.get(0));
        return av || blv;
    }

    /** 是否走 blv 分支 */
    public boolean useBlv() {
        return !(exists(videoPath) && exists(audioPath)) && !blvPaths.isEmpty();
    }

    private static boolean exists(String p) {
        return p != null && !p.isEmpty() && new File(p).exists();
    }

    /** 用于日志/进度的显示名 */
    public String displayName() {
        if (!title.isEmpty()) return title;
        if (!sourceDir.isEmpty()) return new File(sourceDir).getName();
        return "未命名";
    }
}
