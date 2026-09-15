package com.piliplus.export;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 导出编排：拉全量主评论 + 展开全部楼中楼 + 落盘 */
public class Exporter {

    public interface Progress {
        void on(String stage, int main, int sub);
        void done(String path, int main, int sub, long apiCount);
        void error(String msg);
    }

    /** 楼层之间至少 70ms，避免风控 */
    private static final long THROTTLE_MS = 70;
    /** 主评论连续多少页无新增就停 */
    private static final int NO_NEW_LIMIT = 12;
    /** 楼中楼连续多少页重复就停 */
    private static final int SUB_STALL_LIMIT = 2;

    private final Context ctx;
    private final Progress cb;

    public Exporter(Context ctx, Progress cb) { this.ctx = ctx; this.cb = cb; }

    public void run(String videoId, MdWriter.Style style) {
        new Thread(() -> {
            try {
                BiliApi.Video v = BiliApi.videoInfo(videoId);
                if (v.aid == 0) { cb.error("取视频信息失败：" + videoId); return; }
                cb.on("视频：" + v.title, 0, 0);

                // ---------- 1. 主评论全量（mode=3 热度） ----------
                List<Reply> mains = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                long cursor = 0;
                String offset = null;
                int noNew = 0;
                long apiTotal = 0;

                for (int pg = 0; pg < 2000; pg++) {
                    if (pg > 0) sleep(THROTTLE_MS);
                    BiliApi.Page p = BiliApi.mainList(v.aid, cursor, 3, offset);
                    if (p.totalCount > 0) apiTotal = p.totalCount;

                    int add = 0;
                    for (Reply r : p.replies) {
                        if (seen.add(r.id)) { mains.add(r); add++; }
                    }
                    cb.on("拉取主评论", mains.size(), 0);
                    noNew = add > 0 ? 0 : noNew + 1;
                    if (noNew >= NO_NEW_LIMIT || p.replies.isEmpty()) break;

                    if (p.nextCursor != 0) cursor = p.nextCursor;
                    if (p.nextOffset != null) offset = p.nextOffset;
                    if (p.isEnd && add == 0) break;
                }

                // ---------- 2. 楼中楼全量 ----------
                int subTotal = 0;
                Set<Long> subSeen = new HashSet<>();
                for (int i = 0; i < mains.size(); i++) {
                    Reply m = mains.get(i);
                    for (Reply s : m.subs) subSeen.add(s.id);

                    if (m.count > m.subs.size()) {
                        sleep(THROTTLE_MS);
                        List<Reply> extra = fetchSubs(v.aid, m.id);
                        for (Reply s : extra) {
                            if (s.id != 0 && subSeen.add(s.id)) m.subs.add(s);
                        }
                    }
                    subTotal = subSeen.size();
                    if ((i + 1) % 50 == 0) cb.on("展开楼中楼", i + 1, subTotal);
                }

                // ---------- 3. 渲染落盘 ----------
                cb.on("写入文件", mains.size(), subTotal);
                String md = MdWriter.render(v, mains, style, subTotal);

                File dir = new File("/storage/emulated/0/Download");
                if (!dir.isDirectory() || !dir.canWrite()) dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();

                File out = new File(dir, MdWriter.safeName(v.title) + "_评论.md");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(md.getBytes(StandardCharsets.UTF_8));
                }
                cb.done(out.getAbsolutePath(), mains.size(), subTotal, v.replyCount);

            } catch (Throwable t) {
                cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "pili-export").start();
    }

    /** 拉一条主评论下的全部楼中楼，带重复页检测 */
    private List<Reply> fetchSubs(long aid, long root) {
        List<Reply> out = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        long cursor = 0;
        String offset = null;
        String prevKey = "";
        int stall = 0;

        for (int pg = 0; pg < 500; pg++) {
            if (pg > 0) sleep(30);
            BiliApi.SubPage sp;
            try {
                sp = BiliApi.detailList(aid, root, cursor, 2, offset);
            } catch (Exception e) {
                break;
            }
            if (sp.replies.isEmpty()) break;

            StringBuilder key = new StringBuilder();
            for (Reply r : sp.replies) key.append(r.id).append(',');
            if (key.toString().equals(prevKey)) {
                if (++stall >= SUB_STALL_LIMIT) break;
            } else {
                stall = 0;
                prevKey = key.toString();
            }

            int add = 0;
            for (Reply r : sp.replies) if (r.id != 0 && ids.add(r.id)) { out.add(r); add++; }
            if (add == 0 && stall > 0) break;
            if (sp.replies.size() < 20) break;
            if (sp.nextCursor != 0) cursor = sp.nextCursor;
            if (sp.nextOffset != null) offset = sp.nextOffset;
        }
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
