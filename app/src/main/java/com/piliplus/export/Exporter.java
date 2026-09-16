// trigger rebuild
package com.piliplus.export;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 单视频评论导出。
 *
 * 输出：/storage/emulated/0/Download/PiliPlus_导出/单视频/<标题>/
 *        视频信息.md
 *        视频.mp4                 （可选，dash 分离流下载后本机 MediaMuxer 合并）
 *        视频.xml                 （弹幕，与视频同名，播放器自动挂载）
 *        弹幕.md
 *        视频评论/评论.md
 *        视频评论/评论图片/评论N.jpg
 *
 * 若无 MANAGE_EXTERNAL_STORAGE：先落 App 私有目录，结束时用 MediaStore API 镜像到 Download。
 */
public class Exporter {

    public interface Progress {
        void on(String stage, int main, int sub);
        void done(String path, int main, int sub, int pics, long apiCount);
        void error(String msg);
    }

    private static final long THROTTLE_MS = 70;
    private static final int NO_NEW_LIMIT = 12;

    private final Context ctx;
    private final Progress cb;

    /** true 表示输出落在 App 私有目录，导出结束需用 MediaStore 镜像到 Download。 */
    private boolean useMsPublish = false;

    /** 是否拉弹幕 */
    public boolean danmaku = true;
    /** 是否回填评论 IP 属地 */
    public boolean ipBackfill = true;
    /** 是否下载视频文件（dash 高清，极慢、占空间大） */
    public boolean downloadVideo = false;

    public Exporter(Context ctx, Progress cb) { this.ctx = ctx; this.cb = cb; }

    public void run(String videoId, boolean withPics) {
        new Thread(() -> {
            try {
                BiliApi.Video v = BiliApi.videoInfo(videoId);
                if (v.aid == 0) { cb.error("取视频信息失败：" + videoId); return; }
                cb.on("视频：" + v.title, 0, 0);

                List<Reply> mains = fetchAll(v.aid, ReplyApi2.TYPE_VIDEO, v.title);
                int sub = 0;
                for (Reply m : mains) sub += m.subs.size();

                File root = exportRoot();
                String upName = (v.ownerName != null && !v.ownerName.isEmpty()) ? v.ownerName : "未知UP"; String upDirName = NameUtil.safe(v.ownerMid > 0 ? (upName + " " + v.ownerMid) : upName); File upDir = new File(root, upDirName); File dir = new File(upDir, NameUtil.safe(v.title));
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录：" + dir);

                CommentSaver.writeFile(new File(dir, "视频信息.md"),
                        "# " + v.title + "\n\n> 视频：https://www.bilibili.com/video/" + v.bvid
                        + "\n> 导出：" + MdWriter2.now() + "\n");

                // ---- 下载视频文件 ----
                if (downloadVideo) {
                    try {
                        VideoItem vi = new VideoItem();
                        vi.aid = v.aid;
                        vi.bvid = v.bvid;
                        vi.title = v.title;
                        vi.duration = v.duration;
                        cb.on("下载视频中", 0, 0);
                        VideoDownloader.Result dr = VideoDownloader.download(vi, dir, (stage, got, total) -> {
                            String det = total > 0
                                    ? (got / 1048576) + "MB/" + (total / 1048576) + "MB"
                                    : (got / 1048576) + "MB";
                            cb.on("下载视频 · " + stage + " " + det, 0, 0);
                        });
                        if (dr.ok) cb.on("视频下载完成 " + (dr.bytes / 1048576) + "MB", 0, 0);
                        else cb.on("视频下载失败：" + dr.error, 0, 0);
                    } catch (Throwable t) {
                        cb.on("视频下载异常：" + t.getMessage(), 0, 0);
                    }
                }

                // ---- 弹幕 ----
                if (danmaku) {
                    try {
                        long cid = PlayUrlApi.cidOf(v.bvid, v.aid);
                        List<DanmakuApi.Item> dms = DanmakuApi.fetchAll(cid, v.duration,
                                (seg, tot, got) -> cb.on("弹幕 " + seg + "/" + tot, got, 0));
                        int dn = DanmakuWriter.write(dir, v.title, dms);
                        cb.on("弹幕完成 " + dn + " 条", 0, 0);
                    } catch (Throwable t) {
                        cb.on("弹幕失败：" + t.getMessage(), 0, 0);
                    }
                }

                // ---- 评论 IP 属地回填 ----
                if (ipBackfill && !mains.isEmpty()) {
                    try {
                        int n = IpBackfill.apply(v.aid, ReplyApi2.TYPE_VIDEO, mains);
                        cb.on("IP属地回填 " + n + " 条", 0, 0);
                    } catch (Throwable t) {
                        cb.on("IP属地失败：" + t.getMessage(), 0, 0);
                    }
                }

                File cdir = new File(dir, "视频评论");
                String header = "# " + v.title + " · 视频评论\n\n"
                        + "> 视频：https://www.bilibili.com/video/" + v.bvid + "\n"
                        + "> 主评论 " + mains.size() + " 条，楼中楼 " + sub + " 条\n"
                        + (v.replyCount > 0 ? ("> 接口报告 " + v.replyCount + " 条\n") : "")
                        + "> 导出：" + MdWriter2.now() + "\n";
                int[] st = CommentSaver.save(cdir, header, mains, withPics, null);

                // ---- 发布到共享 Download ----
                String outPath = cdir.getAbsolutePath();
                if (useMsPublish) {
                    String relBase = "PiliPlus_导出/单视频/" + upDirName + "/" + NameUtil.safe(v.title);
                    try {
                        int n = MsStore.publishTree(ctx, dir, relBase);
                        cb.on("已用 MediaStore 发布到 Download：" + n + " 个文件", 0, 0);
                        outPath = "Download/" + relBase + "/视频评论";
                    } catch (Throwable t) {
                        try {
                            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File(ctx.getExternalFilesDir(null), "publish_err.txt"));
                            t.printStackTrace(pw); pw.close();
                        } catch (Throwable ig) {}
                        cb.on("MediaStore 发布失败：" + t.getMessage(), 0, 0);
                    }
                }
                cb.done(outPath, mains.size(), sub, st[1], v.replyCount);

            } catch (Throwable t) {
                cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "pili-export").start();
    }

    private List<Reply> fetchAll(long aid, int type, String title) {
        List<Reply> mains = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long cursor = 0;
        String offset = null;
        int noNew = 0;

        for (int pg = 0; pg < 2000; pg++) {
            if (pg > 0) sleep(THROTTLE_MS);
            ReplyApi2.Page p;
            try {
                p = ReplyApi2.mainList(aid, type, cursor, 3, offset);
            } catch (Exception e) { break; }
            int add = 0;
            for (Reply r : p.replies) if (seen.add(r.id)) { mains.add(r); add++; }
            cb.on("拉取主评论", mains.size(), 0);
            noNew = add > 0 ? 0 : noNew + 1;
            if (noNew >= NO_NEW_LIMIT || p.replies.isEmpty()) break;
            if (p.nextCursor != 0) cursor = p.nextCursor;
            if (p.nextOffset != null) offset = p.nextOffset;
            if (p.isEnd && add == 0) break;
        }

        java.util.Set<Long> subSeen = new java.util.HashSet<>();
        for (int i = 0; i < mains.size(); i++) {
            Reply m = mains.get(i);
            for (Reply s : m.subs) subSeen.add(s.id);
            if (m.count > m.subs.size()) {
                sleep(THROTTLE_MS);
                List<Reply> extra = DynExporter.fetchSubs(aid, type, m.id);
                for (Reply s : extra) if (s.id != 0 && subSeen.add(s.id)) m.subs.add(s);
            }
            if ((i + 1) % 50 == 0) cb.on("展开楼中楼", i + 1, subSeen.size());
        }
        return mains;
    }

    /**
     * 输出根目录。
     * 有 MANAGE_EXTERNAL_STORAGE 时直写共享 Download；否则落私有目录，
     * 导出结束后由 MsStore.publishTree 用 MediaStore API 镜像到 Download。
     */
    private File exportRoot() {
        File shared = new File("/storage/emulated/0/Download/PiliPlus_导出/单视频");
        if (android.os.Build.VERSION.SDK_INT < 29 && writableDir(shared)) { useMsPublish = false; return shared; }
        useMsPublish = true;
        File ext = ctx.getExternalFilesDir(null);
        File f = (ext != null)
                ? new File(ext, "PiliPlus_导出/单视频")
                : new File(ctx.getFilesDir(), "PiliPlus_导出/单视频");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** 目录必须存在、是目录、且能实际建文件才算可用。 */
    static boolean writableDir(File d) {
        if (!d.exists() && !d.mkdirs()) return false;
        if (!d.isDirectory()) return false;
        File probe = new File(d, ".wprobe_" + System.nanoTime());
        try { if (probe.createNewFile()) { probe.delete(); return true; } } catch (Throwable ignored) {}
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
