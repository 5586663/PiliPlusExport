package com.piliplus.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评论接口（官方 gRPC）。
 *  - MainList: 合并置顶字段 4/5/6/14
 *  - DetailList: scene=0 / rpid=0，拉全量楼中楼
 * 字段号（PiliPlus v1.pb.dart 核实）：
 *   MainListReq  oid=1 type=2 cursor=3 pagination=10
 *   DetailListReq oid=1 type=2 root=3 rpid=4 cursor=5 scene=6 mode=7 pagination=8
 */
public final class ReplyApi2 {

    private ReplyApi2() {}

    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_ARTICLE = 12;
    public static final int TYPE_DYNAMIC = 17;

    public static class Page {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public boolean isEnd;
        public long totalCount;
        public String nextOffset;
    }

    public static Page mainList(long oid, int type, long nextCursor, int mode, String offset) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(1, oid),
                Proto.fv(2, type),
                Proto.fb(3, Proto.cat(Proto.fv(1, nextCursor), Proto.fv(4, mode))),
                offset == null ? null : Proto.fb(10, Proto.fb(2, offset.getBytes(StandardCharsets.UTF_8))));
        byte[] resp = BiliApi.grpcRaw(BiliApi.MAIN, req);
        Page p = new Page();
        byte[] cur = Proto.getB(resp, 1);
        if (cur != null) {
            p.nextCursor = Proto.getV(cur, 1);
            p.isEnd = Proto.getV(cur, 4) != 0;
        }
        byte[] sc = Proto.getB(resp, 3);
        if (sc != null) p.totalCount = Proto.getV(sc, 16);
        byte[] pr = Proto.getB(resp, 20);
        if (pr != null) {
            byte[] off = Proto.getB(pr, 1);
            if (off != null) p.nextOffset = new String(off, StandardCharsets.UTF_8);
        }
        Set<Long> seen = new HashSet<>();
        collect(p.replies, seen, Proto.getB(resp, 4), "UP主置顶");
        collect(p.replies, seen, Proto.getB(resp, 5), "管理员置顶");
        collect(p.replies, seen, Proto.getB(resp, 6), "热评置顶");
        for (byte[] tb : Proto.getAllB(resp, 14)) collect(p.replies, seen, tb, "置顶");
        java.util.List<byte[]> _raws = Proto.getAllB(resp, 2);
        DebugDump.dumpReplies(_raws, "mainList type=" + type);
        for (byte[] rb : _raws) collect(p.replies, seen, rb, null);
        return p;
    }

    private static void collect(List<Reply> out, Set<Long> seen, byte[] rb, String kind) {
        if (rb == null) return;
        Reply r = Reply.parse(rb);
        if (r == null || !r.valid() || !seen.add(r.id)) return;
        if (kind != null) { r.top = true; r.topKind = kind; }
        out.add(r);
    }

    public static class SubPage {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public String nextOffset;
    }

    public static SubPage detailList(long oid, int type, long root, long cursor, int mode, String offset) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(1, oid),
                Proto.fv(2, type),
                Proto.fv(3, root),
                Proto.fv(4, 0),
                cursor == 0 ? null : Proto.fb(5, Proto.cat(Proto.fv(1, cursor), Proto.fv(4, 0))),
                Proto.fv(6, 0),
                Proto.fv(7, mode),
                offset == null ? null : Proto.fb(8, Proto.fb(2, offset.getBytes(StandardCharsets.UTF_8))));
        byte[] resp = BiliApi.grpcRaw(BiliApi.DETAIL, req);
        SubPage p = new SubPage();
        byte[] cur = Proto.getB(resp, 1);
        if (cur != null) p.nextCursor = Proto.getV(cur, 1);
        byte[] rootB = Proto.getB(resp, 3);
        if (rootB != null) {
            for (byte[] sb : Proto.getAllB(rootB, 1)) {
                Reply r = Reply.parse(sb);
                if (r.valid()) p.replies.add(r);
            }
        }
        diagDetail(oid, root, rootB, p);
        return p;
    }

    private static void diagDetail(long oid, long root, byte[] rootB, SubPage p) {
        try {
            int parsedLoc = 0;
            for (Reply r : p.replies) if (r.location != null && !r.location.isEmpty()) parsedLoc++;
            int ctrlCnt = 0, field25 = 0;
            if (rootB != null) {
                for (byte[] sb : Proto.getAllB(rootB, 1)) {
                    byte[] c = Proto.getB(sb, 14);
                    if (c != null) { ctrlCnt++; if (Proto.getB(c, 25) != null) field25++; }
                }
            }
            String msg = "detailList oid=" + oid + " root=" + root + " subs=" + p.replies.size()
                    + " ctrl=" + ctrlCnt + " field25=" + field25 + " parsedLoc=" + parsedLoc;
            android.util.Log.i("PiliExportDiag", msg);
        } catch (Throwable t) {
            android.util.Log.e("PiliExportDiag", "diag fail", t);
        }
    }
}
