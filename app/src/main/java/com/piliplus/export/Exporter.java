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
 *        视频评论/评论.md
 *        视频评论/评论图片/评论N.jpg
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
                File dir = new File(root, NameUtil.safe(v.title));
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录：" + dir);

                CommentSaver.writeFile(new File(dir, "视频信息.md"),
                        "# " + v.title + "\n\n> 视频：https://www.bilibili.com/video/" + v.bvid
                        + "\n> 导出：" + MdWriter2.now() + "\n");

                File cdir = new File(dir, "视频评论");
                String header = "# " + v.title + " · 视频评论\n\n"
                        + "> 视频：https://www.bilibili.com/video/" + v.bvid + "\n"
                        + "> 主评论 " + mains.size() + " 条，楼中楼 " + sub + " 条\n"
                        + (v.replyCount > 0 ? ("> 接口报告 " + v.replyCount + " 条\n") : "")
                        + "> 导出：" + MdWriter2.now() + "\n";
                int[] st = CommentSaver.save(cdir, header, mains, withPics, null);
                cb.done(cdir.getAbsolutePath(), mains.size(), sub, st[1], v.replyCount);

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

    private File exportRoot() {
        File d = new File("/storage/emulated/0/Download/PiliPlus_导出/单视频");
        if (d.isDirectory() || d.mkdirs()) return d;
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) { File f = new File(ext, "PiliPlus_导出/单视频"); f.mkdirs(); return f; }
        File f = new File(ctx.getFilesDir(), "PiliPlus_导出/单视频");
        f.mkdirs();
        return f;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
