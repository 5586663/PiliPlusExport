package com.piliplus.export;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UP主全量导出编排。
 *
 * 三种模式（互相独立，也可一起）：
 *   MODE_VIDEOS —— 只导投稿视频列表 + 每个视频的全部评论
 *   MODE_DYNS   —— 只导动态（含发布时间）
 *   MODE_ALL    —— 两者都导
 *
 * 动态走 HTTP web 接口（DynApi），因为 gRPC 的 OpusSpaceFlow 响应中无发布时间字段。
 */
public class UpExporter {

    public static final int MODE_VIDEOS = 1;
    public static final int MODE_DYNS = 2;
    public static final int MODE_ALL = 3;

    public interface Progress {
        void on(String stage, int cur, int total, String detail);
        void done(String dir, int videos, int dyns, long comments, long bytes);
        void error(String msg);
    }

    private static final long THROTTLE_MS = 70;
    private static final int NO_NEW_LIMIT = 12;
    private static final int SUB_STALL_LIMIT = 2;

    private final Context ctx;
    private final Progress cb;

    public UpExporter(Context ctx, Progress cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    /**
     * @param mode            MODE_VIDEOS / MODE_DYNS / MODE_ALL
     * @param withComments    仅 MODE_VIDEOS/MODE_ALL 有效：是否拉每个视频的全部评论
     */
    public void run(long mid, MdWriter.Style style, int mode, boolean withComments) {
        new Thread(() -> {
            try {
                doRun(mid, style, mode, withComments);
            } catch (Throwable t) {
                cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "pili-up-export").start();
    }

    // ==================================================================
    private void doRun(long mid, MdWriter.Style style, int mode, boolean withComments) throws Exception {
        cb.on("获取 UP 信息", 0, 0, String.valueOf(mid));
        SpaceApi.UpInfo up = SpaceApi.spaceInfo(mid);
        if (up.name == null || up.name.isEmpty()) up.name = "UID" + mid;

        File root = exportRoot();
        File dir = new File(root, MdWriter.safeName(up.name));
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录：" + dir);
        File vdir = new File(dir, "视频");
        if (!vdir.exists()) vdir.mkdirs();

        boolean wantVideos = (mode == MODE_VIDEOS || mode == MODE_ALL);
        boolean wantDyns = (mode == MODE_DYNS || mode == MODE_ALL);

        // ---------- 1. 视频列表 ----------
        List<VideoItem> videos = new ArrayList<>();
        if (wantVideos) {
            Set<Long> vseen = new HashSet<>();
            int pn = 1;
            int vNoNew = 0;
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
                for (VideoItem v : page.items) {
                    if (v.aid != 0 && vseen.add(v.aid)) { videos.add(v); add++; }
                }
                cb.on("视频列表", videos.size(), page.total, page.items.isEmpty() ? "本页空" : "继续");
                vNoNew = add > 0 ? 0 : vNoNew + 1;
                if (vNoNew >= NO_NEW_LIMIT) break;
                if (!page.hasNext || page.next <= 0 || page.next == pn) break;
                pn = page.next;
            }
        }

        // ---------- 2. 动态列表（HTTP + WBI，含发布时间） ----------
        List<DynItem> dyns = new ArrayList<>();
        if (wantDyns) {
            Set<String> dseen = new HashSet<>();
            String offset = null;
            int dNoNew = 0;
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
                    if (!key.isEmpty() && dseen.add(key)) { dyns.add(d); add++; }
                }
                cb.on("动态列表", dyns.size(), 0, page.items.isEmpty() ? "本页空" : "继续");
                dNoNew = add > 0 ? 0 : dNoNew + 1;
                if (dNoNew >= NO_NEW_LIMIT) break;
                if (!page.hasMore) break;
                if (page.offset == null || page.offset.isEmpty() || page.offset.equals(offset)) break;
                offset = page.offset;
            }
        }

        // ---------- 3. 总览 ----------
        cb.on("写入总览", 0, 0, up.name);
        writeFile(new File(dir, "00_总览.md"), MdWriter2.upOverview(up, videos, dyns));

        // ---------- 4. 动态 ----------
        if (wantDyns && !dyns.isEmpty()) {
            cb.on("写入动态", 0, 0, dyns.size() + " 条");
            writeFile(new File(dir, "动态.md"), MdWriter2.dyns(up, dyns));
        }

        // ---------- 5. 逐视频评论 ----------
        long totalComments = 0;
        if (wantVideos && withComments) {
            for (int i = 0; i < videos.size(); i++) {
                VideoItem v = videos.get(i);
                cb.on("视频评论", i + 1, videos.size(), v.title);
                try {
                    long[] stat = new long[2];
                    String md = collectVideo(up, v, style, stat);
                    totalComments += stat[0] + stat[1];
                    writeFile(new File(vdir, MdWriter.safeName(v.title) + ".md"), md);
                } catch (Exception e) {
                    cb.on("视频评论", i + 1, videos.size(), "失败：" + v.title + " " + e.getMessage());
                }
            }
        }

        cb.on("统计", 0, 0, "整理结果");
        long bytes = dirSize(dir);
        cb.done(dir.getAbsolutePath(), videos.size(), dyns.size(), totalComments, bytes);
    }

    // ==================================================================
    private String collectVideo(SpaceApi.UpInfo up, VideoItem v, MdWriter.Style style, long[] statOut) throws Exception {
        List<Reply> mains = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long cursor = 0;
        String offset = null;
        int noNew = 0;
        long apiTotal = 0;

        for (int pg = 0; pg < 2000; pg++) {
            if (pg > 0) sleep(THROTTLE_MS);
            ReplyApi2.Page p = ReplyApi2.mainList(v.aid, ReplyApi2.TYPE_VIDEO, cursor, 3, offset);
            if (p.totalCount > 0) apiTotal = p.totalCount;
            int add = 0;
            for (Reply r : p.replies) if (seen.add(r.id)) { mains.add(r); add++; }
            noNew = add > 0 ? 0 : noNew + 1;
            if (noNew >= NO_NEW_LIMIT || p.replies.isEmpty()) break;
            if (p.nextCursor != 0) cursor = p.nextCursor;
            if (p.nextOffset != null) offset = p.nextOffset;
            if (p.isEnd && add == 0) break;
        }

        int subTotal = 0;
        Set<Long> subSeen = new HashSet<>();
        for (Reply m : mains) {
            for (Reply s : m.subs) subSeen.add(s.id);
            if (m.count > m.subs.size()) {
                sleep(THROTTLE_MS);
                List<Reply> extra = fetchSubs(v.aid, ReplyApi2.TYPE_VIDEO, m.id);
                for (Reply s : extra) if (s.id != 0 && subSeen.add(s.id)) m.subs.add(s);
            }
            subTotal = subSeen.size();
        }

        statOut[0] = mains.size();
        statOut[1] = subTotal;
        return MdWriter2.videoComments(up, v, mains, style, subTotal, apiTotal);
    }

    private List<Reply> fetchSubs(long oid, int type, long root) {
        List<Reply> out = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        long cursor = 0;
        String offset = null;
        String prevKey = "";
        int stall = 0;
        for (int pg = 0; pg < 500; pg++) {
            if (pg > 0) sleep(30);
            ReplyApi2.SubPage sp;
            try {
                sp = ReplyApi2.detailList(oid, type, root, cursor, 2, offset);
            } catch (Exception e) { break; }
            if (sp.replies.isEmpty()) break;
            StringBuilder key = new StringBuilder();
            for (Reply r : sp.replies) key.append(r.id).append(',');
            if (key.toString().equals(prevKey)) { if (++stall >= SUB_STALL_LIMIT) break; }
            else { stall = 0; prevKey = key.toString(); }
            int add = 0;
            for (Reply r : sp.replies) if (r.id != 0 && ids.add(r.id)) { out.add(r); add++; }
            if (add == 0 && stall > 0) break;
            if (sp.replies.size() < 20) break;
            if (sp.nextCursor != 0) cursor = sp.nextCursor;
            if (sp.nextOffset != null) offset = sp.nextOffset;
        }
        return out;
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

    private static void writeFile(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
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
