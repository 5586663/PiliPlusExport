package com.piliplus.export;

import java.io.File;

/**
 * 视频下载：dash 分离流 → 临时文件 → MediaMuxer 合并 → mp4。
 *
 * 目录（视频文件夹内）：
 *   视频.mp4
 *   .tmp/v.m4s
 *   .tmp/a.m4s
 *
 * 合并成功后删除 .tmp。失败时保留 .tmp 便于排查，且不产出 mp4。
 *
 * 防盗链：B站 upos CDN 校验 Referer，必须是具体视频页；
 *        且 upos 常返回 http:// 直链，Android 9+ 默认禁明文，需网络配置放行。
 */
public final class VideoDownloader {

    private VideoDownloader() {}

    public interface Progress {
        void on(String stage, long got, long total);
    }

    public static class Result {
        public boolean ok;
        public String file = "";
        public int width, height;
        public long bytes;
        public String error = "";
        public boolean muxFailed;
    }

    public static Result download(VideoItem v, File videoDir, Progress cb) {
        Result r = new Result();
        File tmp = new File(videoDir, ".tmp");
        File vTmp = new File(tmp, "v.m4s");
        File aTmp = new File(tmp, "a.m4s");
        File out = new File(videoDir, "视频.mp4");

        try {
            if (!videoDir.exists() && !videoDir.mkdirs()) throw new Exception("无法创建目录");

            // 已存在成品则跳过
            if (out.exists() && out.length() > 1024) {
                r.ok = true;
                r.file = out.getAbsolutePath();
                r.bytes = out.length();
                return r;
            }

            long cid = PlayUrlApi.cidOf(v.bvid, v.aid);
            PlayUrlApi.Dash d = PlayUrlApi.dash(v.bvid, v.aid, cid);
            r.width = d.width;
            r.height = d.height;

            // 防盗链 Referer：必须是具体视频页
            String referer = "https://www.bilibili.com/video/"
                    + (v.bvid != null && !v.bvid.isEmpty() ? v.bvid : ("av" + v.aid));

            if (cb != null) cb.on("下载视频流", 0, -1);
            downloadWithFallback(d.videoUrl, d.videoBackup, vTmp, cb, "视频流", referer);
            if (d.audioUrl.isEmpty()) throw new Exception("无音频轨");
            if (cb != null) cb.on("下载音频流", 0, -1);
            downloadWithFallback(d.audioUrl, d.audioBackup, aTmp, cb, "音频流", referer);

            if (cb != null) cb.on("合并中", 0, -1);
            try {
                DashMuxer.mux(vTmp, aTmp, out);
            } catch (Throwable t) {
                r.muxFailed = true;
                throw new Exception("合并失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }

            if (!out.exists() || out.length() < 1024) throw new Exception("合并产物为空");

            // 清理临时
            try { vTmp.delete(); } catch (Throwable ignored) {}
            try { aTmp.delete(); } catch (Throwable ignored) {}
            try { tmp.delete(); } catch (Throwable ignored) {}

            r.ok = true;
            r.file = out.getAbsolutePath();
            r.bytes = out.length();
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    private static void downloadWithFallback(String primary, String backup, File dest,
                                             Progress cb, String label, String referer) throws Exception {
        try {
            HttpDownloader.download(primary, dest, (got, total) -> {
                if (cb != null) cb.on(label, got, total);
                return true;
            }, referer);
        } catch (Exception e) {
            if (backup == null || backup.isEmpty()) throw e;
            if (cb != null) cb.on(label + "（备用源）", 0, -1);
            // 主源失败：清掉可能写了一半的残留，重新下
            try { if (dest.exists()) dest.delete(); } catch (Throwable ignored) {}
            HttpDownloader.download(backup, dest, (got, total) -> {
                if (cb != null) cb.on(label + "（备用源）", got, total);
                return true;
            }, referer);
        }
    }
}
