package com.piliplus.export;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 本地缓存合并编排。
 *
 * 三项特性：
 *   1. 单一目录 —— 所有产物平铺到同一个输出目录
 *   2. 序号       —— 同名文件自动加 (0)(1)(2) 后缀
 *   3. 弹幕文件   —— danmaku.xml 与视频同目录、同名（扩展名不同）
 *
 * 目录结构（默认，非单一目录模式）：
 *   <out>/<分组名>/<序号_标题>.mp4
 *                      <序号_标题>.xml
 *
 * 单一目录模式：
 *   <out>/<序号_标题>.mp4
 *         <序号_标题>.xml
 *
 * 依赖 libffmpeg_core.so（arm64-v8a）。
 */
public final class CacheMerger {

    private CacheMerger() {}

    public static class Options {
        /** 单一目录：true 时忽略分组，全部平铺 */
        public boolean singleOutputPath = false;
        /** 是否导出弹幕 xml */
        public boolean exportDanmaku = true;
        /** 是否给文件加序号前缀 */
        public boolean addIndex = true;
        /** 文件名前缀起始序号 */
        public int startIndex = 1;
    }

    public interface Progress {
        void on(String stage, int cur, int total, String detail);
        void done(String dir, int ok, int fail, int danmaku);
        void error(String msg);
    }

    public static class Result {
        public int ok = 0;
        public int fail = 0;
        public int danmaku = 0;
        public String outDir = "";
    }

    public static void run(Context ctx, File cacheRoot, Options opt, Progress cb) {
        new Thread(() -> {
            try {
                Result r = doRun(ctx, cacheRoot, opt, cb);
                cb.done(r.outDir, r.ok, r.fail, r.danmaku);
            } catch (Throwable t) {
                cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "pili-cache-merge").start();
    }

    // ==================================================================
    private static Result doRun(Context ctx, File cacheRoot, Options opt, Progress cb) throws Exception {
        Result r = new Result();

        // ---- 0. 原生库可用性前置检查 ----
        if (!FfmpegCore.available()) {
            throw new Exception("原生库不可用：" + FfmpegCore.loadError()
                    + "\n（可能原因：设备非 arm64-v8a，或 APK 未打包 so）");
        }
        String ver = FfmpegCore.version();
        cb.on("初始化", 0, 0, "FFmpeg " + (ver == null ? "未知" : ver));

        // ---- 1. 扫描 ----
        cb.on("扫描缓存", 0, 0, cacheRoot.getAbsolutePath());
        List<CacheItem> items = CacheScanner.scan(cacheRoot, s -> cb.on("扫描", 0, 0, s));
        if (items.isEmpty()) {
            throw new Exception("未在 " + cacheRoot.getAbsolutePath() + " 找到可合并的缓存");
        }

        // ---- 2. 输出根目录 ----
        File root = exportRoot(ctx);
        r.outDir = root.getAbsolutePath();
        if (!root.exists() && !root.mkdirs()) throw new Exception("无法创建输出目录：" + root);

        // ---- 3. 逐条合并 ----
        int idx = opt.startIndex;
        for (int i = 0; i < items.size(); i++) {
            CacheItem it = items.get(i);
            cb.on("合并", i + 1, items.size(), it.displayName());

            // 决定输出目录
            File dir = opt.singleOutputPath
                    ? root
                    : new File(root, NameUtil.safe(it.groupTitle));
            if (!dir.exists()) dir.mkdirs();

            // 文件名前缀：序号 + 标题
            String base = opt.addIndex
                    ? String.format(java.util.Locale.CHINA, "%03d_%s", idx, NameUtil.safe(it.title))
                    : NameUtil.safe(it.title);

            // 防重名（同名文件已存在时加 (0)(1)）
            File out = NameUtil.availableFile(dir, base, ".mp4");

            String err;
            if (it.useBlv()) {
                cb.on("合并 · blv分片", i + 1, items.size(), it.blvPaths.size() + " 个分片");
                err = FfmpegCore.mergeVideos(
                        it.blvPaths.toArray(new String[0]), out.getAbsolutePath());
            } else {
                cb.on("合并 · 音视频", i + 1, items.size(), "m4s");
                err = FfmpegCore.mergeAudioVideo(
                        it.videoPath, it.audioPath, out.getAbsolutePath());
            }

            if (err != null) {
                r.fail++;
                cb.on("失败", i + 1, items.size(), it.displayName() + "\n" + err);
                continue;
            }

            r.ok++;
            idx++;

            // ---- 弹幕 ----
            if (opt.exportDanmaku && it.danmakuPath != null && !it.danmakuPath.isEmpty()) {
                File src = new File(it.danmakuPath);
                if (src.exists()) {
                    // 与 mp4 同名，扩展名换 .xml
                    String mp4Name = out.getName();
                    String stem = mp4Name.endsWith(".mp4")
                            ? mp4Name.substring(0, mp4Name.length() - 4) : mp4Name;
                    File dst = NameUtil.availableFile(dir, stem, ".xml");
                    if (copy(src, dst)) r.danmaku++;
                }
            }
        }

        return r;
    }

    // ==================================================================
    private static File exportRoot(Context ctx) {
        File d = new File("/storage/emulated/0/Download/PiliPlus_导出/缓存合并");
        if (d.isDirectory() || d.mkdirs()) return d;
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            File f = new File(ext, "PiliPlus_导出/缓存合并");
            f.mkdirs();
            return f;
        }
        File f = new File(ctx.getFilesDir(), "PiliPlus_导出/缓存合并");
        f.mkdirs();
        return f;
    }

    private static boolean copy(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
