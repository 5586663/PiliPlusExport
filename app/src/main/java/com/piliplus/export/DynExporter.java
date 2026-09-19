package com.piliplus.export;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态导出：每条动态一个文件夹，内含正文 md、图片、动态评论。
 *
 * 目录：
 *   <UP>/动态/
 *     000_动态总表.md
 *     <序号>_<标题或id>/
 *       动态.md
 *       图片/
 *       动态评论/
 *         评论.md
 *         评论图片/
 *
 * 评论接口：ReplyApi2.mainList(oid, TYPE_DYNAMIC=17, ...)
 * oid 来自 DynApi 的 basic.comment_id_str。
 */
public final class DynExporter {

    private DynExporter() {}

    private static final long THROTTLE_MS = 70;
    private static final int NO_NEW_LIMIT = 12;
    private static final int SUB_STALL_LIMIT = 2;

    public interface Progress {
        void on(String stage, int cur, int total, String detail);
    }

    public static class Result {
        public int dynFolders;
        public int dynPics;
        public long comments;
        public int commentPics;
        public int failed;
    }

    public static Result run(File dynRoot, List<DynItem> dyns,
                             boolean withComments, boolean withPics, Progress cb) throws Exception {
        Result r = new Result();
        if (!dynRoot.exists() && !dynRoot.mkdirs()) throw new Exception("无法创建目录：" + dynRoot);

        for (int i = 0; i < dyns.size(); i++) {
            DynItem d = dyns.get(i);
            String folder = NameUtil.indexed(i + 1, dynTitle(d));
            File dir = new File(dynRoot, folder);

            try {
                if (cb != null) cb.on("动态", i + 1, dyns.size(), d.title.isEmpty() ? d.dynIdStr : d.title);
                CommentSaver.writeFile(new File(dir, "动态.md"), MdWriter2.dynDetail(d));
                r.dynFolders++;

                if (withPics && !d.images.isEmpty()) {
                    File picDir = new File(dir, "图片");
                    int pn = 0;
                    for (String url : d.images) {
                        String n = "动态图" + (++pn);
                        if (HttpDownloader.downloadSmall(url, new File(picDir, n + NameUtil.imgExt(url)))) r.dynPics++;
                    }
                }

                if (withComments && d.oid != 0) {
                    List<Reply> mains = fetchAll(d.oid, ReplyApi2.TYPE_DYNAMIC);
                    File cdir = new File(dir, "动态评论");
                    int[] st = CommentSaver.save(cdir, null, mains, withPics, null);
                    r.comments += st[0];
                    r.commentPics += st[1];
                }
            } catch (Throwable t) {
                r.failed++;
                if (cb != null) cb.on("动态", i + 1, dyns.size(), "失败：" + t.getMessage());
            }
        }
        return r;
    }

    // ==================================================================
    /** 拉一条 oid 下的全部主评论 + 展开楼中楼。type: TYPE_VIDEO / TYPE_DYNAMIC */
    static List<Reply> fetchAll(long oid, int type) { String _ck = BiliApi.COOKIE; String _ak = BiliApi.ACCESS_KEY; BiliApi.COOKIE = ""; BiliApi.ACCESS_KEY = ""; try {
        List<Reply> mains = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long cursor = 0;
        String offset = null;
        int noNew = 0;

        for (int pg = 0; pg < 2000; pg++) {
            if (pg > 0) sleep(THROTTLE_MS);
            ReplyApi2.Page p;
            try {
                p = ReplyApi2.mainList(oid, type, cursor, 1, offset);
            } catch (Exception e) {
                break;
            }
            int add = 0;
            for (Reply x : p.replies) if (seen.add(x.id)) { mains.add(x); add++; }
            noNew = add > 0 ? 0 : noNew + 1;
            if (noNew >= NO_NEW_LIMIT || p.replies.isEmpty()) break;
            if (p.nextCursor != 0) cursor = p.nextCursor; if (p.nextOffset != null) offset = p.nextOffset;
            if (p.isEnd && add == 0) break;
        }

        Set<Long> subSeen = new HashSet<>();
        for (Reply m : mains) {
            for (Reply s : m.subs) subSeen.add(s.id);
            if (m.count > m.subs.size()) {
                sleep(THROTTLE_MS);
                List<Reply> extra = fetchSubs(oid, type, m.id);
                for (Reply s : extra) if (s.id != 0 && subSeen.add(s.id)) m.subs.add(s);
            }
        }
        return mains;
        } finally { BiliApi.COOKIE = _ck; BiliApi.ACCESS_KEY = _ak; }
    }

    static List<Reply> fetchSubs(long oid, int type, long root) {
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
            for (Reply x : sp.replies) key.append(x.id).append(',');
            if (key.toString().equals(prevKey)) { if (++stall >= SUB_STALL_LIMIT) break; }
            else { stall = 0; prevKey = key.toString(); }
            int add = 0;
            for (Reply x : sp.replies) if (x.id != 0 && ids.add(x.id)) { out.add(x); add++; }
            if (add == 0 && stall > 0) break;
            if (sp.replies.size() < 20 && sp.nextCursor == 0) break;
            if (sp.nextCursor != 0) cursor = sp.nextCursor;
            if (sp.nextOffset != null) offset = sp.nextOffset;
        }
        return out;
    }

    static String dynTitle(DynItem d) {
        if (d.title != null && !d.title.trim().isEmpty()) return d.title.trim();
        String t = d.text == null ? "" : d.text.replace("\n", " ").trim();
        if (t.length() > 24) t = t.substring(0, 24) + "…";
        if (!t.isEmpty()) return t;
        if (!d.dynIdStr.isEmpty()) return d.dynIdStr;
        return "动态";
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
