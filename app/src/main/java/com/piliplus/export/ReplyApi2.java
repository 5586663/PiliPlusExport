package com.piliplus.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 带 type 参数的评论接口。
 *
 * BiliApi.mainList() 把 type 写死为 1（视频），动态评论需要 type=17，
 * 因此这里提供参数化版本，复用 BiliApi.grpcRaw 通道。
 *
 * 字段号同 BiliApi（已核实）：
 *   MainListReq:  oid=1 type=2 cursor=3 pagination=10
 *   DetailListReq: oid=1 type=2 root=3 rpid=4 cursor=5 scene=6 mode=7 pagination=8
 *
 * 评论区 type 取值：
 *   1  = 视频
 *   12 = 专栏
 *   17 = 动态
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

    /** 主评论分页（可指定 type） */
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

        // MainListReply 里置顶评论是独立字段，不在 replies(2) 里：
        //   4 = upTop（UP 主置顶）  5 = adminTop（管理员置顶）
        //   6 = voteTop            14 = topReplies（repeated）
        // 旧实现只读 replies(2)，导致置顶评论丢失（接口总数比导出多一条）。
        Set<Long> seen = new HashSet<>();
        collect(p.replies, seen, Proto.getB(resp, 4));   // upTop
        collect(p.replies, seen, Proto.getB(resp, 5));   // adminTop
        collect(p.replies, seen, Proto.getB(resp, 6));   // voteTop
        for (byte[] tb : Proto.getAllB(resp, 14)) collect(p.replies, seen, tb); // topReplies

        List<byte[]> _raws = Proto.getAllB(resp, 2);
        DebugDump.dumpReplies(_raws, "mainList type=" + type);
        for (byte[] rb : _raws) collect(p.replies, seen, rb);
        return p;
    }

    /** 解析单条并去重加入结果。 */
    private static void collect(List<Reply> out, Set<Long> seen, byte[] rb) {
        if (rb == null) return;
        Reply r = Reply.parse(rb);
        if (r != null && r.valid() && seen.add(r.id)) out.add(r);
    }

    public static class SubPage {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public String nextOffset;
    }

    /** 楼中楼分页（可指定 type） */
    public static SubPage detailList(long oid, int type, long root, long cursor, int mode, String offset) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(1, oid),
                Proto.fv(2, type),
                Proto.fv(3, root),
                Proto.fv(4, root),
                cursor == 0 ? null : Proto.fb(5, Proto.cat(Proto.fv(1, cursor), Proto.fv(4, 0))),
                Proto.fv(6, 1),
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
        return p;
    }
}
