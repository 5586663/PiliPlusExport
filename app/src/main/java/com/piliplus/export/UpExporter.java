package com.piliplus.export;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UP主全量导出编排。
 *
 * 目录结构：
 *   <root>/<UP名>/
 *     00_总览.md
 *     视频/
 *       000_视频总表.md
 *       <序号>_<标题>/
 *         视频信息.md
 *         视频.mp4                 (可选)
 *         视频.xml                 (弹幕，与视频同名；可选)
 *         弹幕.md
 *         视频评论/
 *           评论.md
 *           评论图片/评论N.jpg
 *     动态/
 *       000_动态总表.md
 *       <序号>_<标题>/
 *         动态.md
 *         图片/
 *         动态评论/
 *           评论.md
 *           评论图片/
 */
public class UpExporter {

    public interface Progress {
        void on(String stage, int cur, int total, String detail);
        void done(String dir, int videos, int dyns, long comments, long bytes);
        void error(String msg);
    }

    public static class Options {
        public boolean videos = true;
        public boolean videoComments = true;
        public boolean dyns = true;
        public boolean dynComments = true;
        public boolean commentPics = true;
        public boolean downloadVideo = false;
        public boolean dynPics = true;
        /** 拉视频弹幕（视频.xml + 弹幕.md） */
        public boolean danmaku = true;
        /** 评论 IP 属地回填（gRPC 不下发，用网页端接口补） */
        public boolean ipBackfill = true;
    }

    private static final long THROTTLE_MS = 70;
    private static final int NO_NEW_LIMIT = 12;

    private final Context ctx;
    private final Progress cb;

    public UpExporter(Context ctx, Progress cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    public void run(long mid, Options opt) {
        new Thread(() -> {
            try {
                doRun(mid, opt);
            } catch (Throwable t) {
                cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "pili-up-export").start();
    }

    // ==================================================================
    private void doRun(long mid, Options opt) throws Exception {
        cb.on("获取 UP 信息", 0, 0, String.valueOf(mid));
        SpaceApi.UpInfo up = SpaceApi.spaceInfo(mid);
        if (up.name == null || up.name.isEmpty()) up.name = "UID" + mid;

        File root = exportRoot();
        File dir = new File(root, NameUtil.safe(up.name));
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录：" + dir);

        // ---------- 1. 视频列表 ----------
        List<VideoItem> videos = new ArrayList<>();
        if (opt.videos) {
            Set<Long> seen = new HashSet<>();
            int pn = 1, noNew = 0;
            for (int pg = 0; pg < 5000; pg++) {
                if (pg > 0) sleep(THROTTLE_MS);
                SpaceApi.VideoPage page;
                try {
                    page = SpaceApi.archiveCursor(mid, pn, "pubdate");
                } catch (Exception e) {
                    cb.on("视频列表", videos.size(), 0, "中断：" + e.getMessage());
                    break;
                }
                int add = 0;
                for (VideoItem v : page.items) if (v.aid != 0 && seen.add(v.aid)) { videos.add(v); add++; }
                cb.on("视频列表", videos.size(), page.total, page.items.isEmpty() ? "本页空" : "继续");
                noNew = add > 0 ? 0 : noNew + 1;
                if (noNew >= NO_NEW_LIMIT) break;
                if (!page.hasNext || page.next <= 0 || page.next == pn) break;
                pn = page.next;
            }
        }

        // ---------- 2. 动态列表 ----------
        List<DynItem> dyns = new ArrayList<>();
        if (opt.dyns) {
            Set<String> seen = new HashSet<>();
            String offset = null;
            int noNew = 0;
            for (int pg = 0; pg < 2000; pg++) {
                if (pg > 0) sleep(THROTTLE_MS);
                DynApi.Page page;
                try {
                    page = DynApi.memberDynamic(mid, offset);
                } catch (Exception e) {
                    cb.on("动态列表", dyns.size(), 0, "中断：" + e.getMessage());
                    break;
                }
                int add = 0;
                for (DynItem d : page.items) {
                    String key = d.dynIdStr.isEmpty() ? String.valueOf(d.oid) : d.dynIdStr;
                    if (!key.isEmpty() && seen.add(key)) { dyns.add(d); add++; }
                }
                cb.on("动态列表", dyns.size(), 0, page.items.isEmpty() ? "本页空" : "继续");
                noNew = add > 0 ? 0 : noNew + 1;
                if (noNew >= NO_NEW_LIMIT) break;
                if (!page.hasMore) break;
                if (page.offset == null || page.offset.isEmpty() || page.offset.equals(offset)) break;
                offset = page.offset;
            }
        }

        // ---------- 3. 总览 ----------
        cb.on("写入总览", 0, 0, up.name);
        CommentSaver.writeFile(new File(dir, "00_总览.md"), MdWriter2.upOverview(up, videos, dyns));

        long totalComments = 0;
        long totalDanmaku = 0;
        int ipFilled = 0;
        int vFail = 0, dFail = 0;

        // ---------- 4. 视频 ----------
        if (opt.videos && !videos.isEmpty()) {
            File vRoot = new File(dir, "视频");
            vRoot.mkdirs();
            CommentSaver.writeFile(new File(vRoot, "000_视频总表.md"), MdWriter2.videoList(up, videos));

            final int vTotal = videos.size();
            for (int i = 0; i < videos.size(); i++) {
                final int vIdx = i + 1;
                VideoItem v = videos.get(i);
                final String vTitle = v.title;
                File vdir = new File(vRoot, NameUtil.indexed(vIdx, vTitle));
                if (!vdir.exists()) vdir.mkdirs();
                cb.on("视频", vIdx, vTotal, vTitle);

                try {
                    CommentSaver.writeFile(new File(vdir, "视频信息.md"), MdWriter2.videoInfo(up, v));

                    if (opt.downloadVideo) {
                        cb.on("下载视频", vIdx, vTotal, vTitle);
                        VideoDownloader.Result dr = VideoDownloader.download(v, vdir, (stage, got, total) -> {
                            String det = total > 0
                                    ? (got / 1048576) + "MB/" + (total / 1048576) + "MB"
                                    : (got / 1048576) + "MB";
                            cb.on("下载视频 · " + stage, vIdx, vTotal, vTitle + "\n" + det);
                        });
                        if (!dr.ok) {
                            cb.on("视频下载失败", vIdx, vTotal, vTitle + "\n" + dr.error);
                        }
                    }

                    // 弹幕
                    if (opt.danmaku) {
                        try {
                            long cid = PlayUrlApi.cidOf(v.bvid, v.aid);
                            List<DanmakuApi.Item> dms = DanmakuApi.fetchAll(cid, v.duration,
                                    (seg, tot, got) -> cb.on("弹幕", vIdx, vTotal,
                                            vTitle + "\n第 " + seg + "/" + tot + " 段 · " + got + " 条"));
                            int dn = DanmakuWriter.write(vdir, vTitle, dms);
                            totalDanmaku += dn;
                            cb.on("弹幕完成", vIdx, vTotal, vTitle + "\n" + dn + " 条");
                        } catch (Throwable t) {
                            cb.on("弹幕失败", vIdx, vTotal, vTitle + "\n" + t.getMessage());
                        }
                    }

                    if (opt.videoComments) {
                        List<Reply> mains = DynExporter.fetchAll(v.aid, ReplyApi2.TYPE_VIDEO);
                        if (opt.ipBackfill && !mains.isEmpty()) {
                            cb.on("IP属地回填", vIdx, vTotal, vTitle);
                            ipFilled += IpBackfill.apply(v.aid, ReplyApi2.TYPE_VIDEO, mains);
                        }
                        File cdir = new File(vdir, "视频评论");
                        String header = "# " + vTitle + " · 视频评论\n\n"
                                + "> 视频：https://www.bilibili.com/video/" + v.bvid + "\n"
                                + "> UP主：" + up.name + "（UID：" + up.mid + "）\n"
                                + (v.pubTime.isEmpty() ? "" : ("> 发布：" + v.pubTime + "\n"))
                                + "> 导出：" + MdWriter2.now() + "\n";
                        int[] st = CommentSaver.save(cdir, header, mains, opt.commentPics, null);
                        totalComments += st[0];
                    }
                } catch (Throwable t) {
                    vFail++;
                    cb.on("视频失败", vIdx, vTotal, vTitle + "\n" + t.getMessage());
                }
            }
        }

        // ---------- 5. 动态 ----------
        if (opt.dyns && !dyns.isEmpty()) {
            File dRoot = new File(dir, "动态");
            dRoot.mkdirs();
            CommentSaver.writeFile(new File(dRoot, "000_动态总表.md"), MdWriter2.dynList(up, dyns));

            final int dTotal = dyns.size();
            for (int i = 0; i < dyns.size(); i++) {
                final int dIdx = i + 1;
                DynItem d = dyns.get(i);
                final String dTitle = DynExporter.dynTitle(d);
                File ddir = new File(dRoot, NameUtil.indexed(dIdx, dTitle));
                if (!ddir.exists()) ddir.mkdirs();
                cb.on("动态", dIdx, dTotal, dTitle);
                try {
                    CommentSaver.writeFile(new File(ddir, "动态.md"), MdWriter2.dynDetail(d));

                    if (opt.dynPics && !d.images.isEmpty()) {
                        File picDir = new File(ddir, "图片");
                        int pn = 0;
                        for (String url : d.images) {
                            String n = "动态图" + (++pn);
                            HttpDownloader.downloadSmall(url, new File(picDir, n + NameUtil.imgExt(url)));
                        }
                    }

                    if (opt.dynComments && d.oid != 0) {
                        List<Reply> mains = DynExporter.fetchAll(d.oid, ReplyApi2.TYPE_DYNAMIC);
                        if (opt.ipBackfill && !mains.isEmpty()) {
                            cb.on("IP属地回填", dIdx, dTotal, dTitle);
                            ipFilled += IpBackfill.apply(d.oid, ReplyApi2.TYPE_DYNAMIC, mains);
                        }
                        File cdir = new File(ddir, "动态评论");
                        String header = "# 动态评论\n\n"
                                + "> 动态：https://t.bilibili.com/" + d.dynIdStr + "\n"
                                + "> 时间：" + (d.pubTs > 0 ? MdWriter2.fmt(d.pubTs) : d.pubTimeText) + "\n"
                                + "> 导出：" + MdWriter2.now() + "\n";
                        int[] st = CommentSaver.save(cdir, header, mains, opt.commentPics, null);
                        totalComments += st[0];
                    }
                } catch (Throwable t) {
                    dFail++;
                    cb.on("动态失败", dIdx, dTotal, t.getMessage());
                }
            }
        }

        String stat = "评论 " + totalComments + " 条｜弹幕 " + totalDanmaku + " 条｜IP属地 " + ipFilled + " 条"
                + (vFail + dFail > 0 ? ("｜失败 " + (vFail + dFail) + " 项") : "");
        cb.on("统计", 0, 0, stat);
        long bytes = dirSize(dir);
        cb.done(dir.getAbsolutePath(), videos.size(), dyns.size(), totalComments, bytes);
    }

    // ==================================================================
    private File exportRoot() {
        File d = new File("/storage/emulated/0/Download/PiliPlus_导出");
        if (d.isDirectory() && d.canWrite()) return d;
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) { File f = new File(ext, "PiliPlus_导出"); f.mkdirs(); return f; }
        File f = new File(ctx.getFilesDir(), "PiliPlus_导出");
        f.mkdirs();
        return f;
    }

    private static long dirSize(File d) {
        long n = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) n += f.isDirectory() ? dirSize(f) : f.length();
        return n;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
