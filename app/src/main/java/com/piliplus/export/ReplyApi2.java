package com.piliplus.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        java.util.List<byte[]> _raws = Proto.getAllB(resp, 2);
        int _valid = 0, _bad = 0; StringBuilder _ids = new StringBuilder();
        for (byte[] _rb : _raws) { Reply _r = Reply.parse(_rb); if (_r.valid()) _valid++; else _bad++; _ids.append(_r.id).append(','); }
        try { java.io.FileWriter _fw = new java.io.FileWriter("/storage/emulated/0/Download/PiliPlus_导出/diag_raw.txt", true);
            _fw.write("raw=" + _raws.size() + " valid=" + _valid + " bad=" + _bad + " ids=" + _ids + "\n"); _fw.close(); } catch (Throwable _ig) {}
        DebugDump.dumpReplies(_raws, "mainList type=" + type);
        for (byte[] rb : _raws) {
            Reply r = Reply.parse(rb);
            if (r.valid()) p.replies.add(r);
        }
        return p;
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
            java.io.File f = new java.io.File("/storage/emulated/0/Download/PiliPlus_导出/diag_detail.txt");
            java.io.File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(msg + "\n");
            fw.close();
        } catch (Throwable t) {
            android.util.Log.e("PiliExportDiag", "diag fail", t);
        }
    }
}
