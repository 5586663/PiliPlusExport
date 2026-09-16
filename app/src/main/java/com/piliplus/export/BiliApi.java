package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;

/**
 * B站 gRPC 接口调用。
 * 字段号全部按 PiliPlus v1.pb.dart / v1.pbenum.dart 核实:
 *   Mode: TIME=2, HOT=3  (没有 1)
 *   MainListReq:  oid=1 type=2 cursor=3 pagination=10
 *   CursorReq:    next=1 mode=4
 *   FeedPagination: offset=2
 *   MainListReply: cursor=1 replies=2 subjectControl=3 paginationReply=20
 *   DetailListReq: oid=1 type=2 root=3 rpid=4 cursor=5 scene=6 mode=7 pagination=8
 *   DetailListReply: cursor=1 root=3
 *   SubjectControl: count=16
 */
public class BiliApi {

    public static final String MAIN   = "/bilibili.main.community.reply.v1.Reply/MainList";
    public static final String DETAIL = "/bilibili.main.community.reply.v1.Reply/DetailList";
    public static final String HOST   = "https://app.bilibili.com";

    /** 由 Hook 注入：从 PiliPlus 进程内取到的 cookie 字符串 */
    public static volatile String COOKIE = "";

    public interface Log { void on(String s); }
    private static Log log = s -> {};
    public static void setLog(Log l) { log = l; }

    // ---------------- 主评论分页 ----------------
    public static class Page {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public boolean isEnd;
        public long totalCount;   // SubjectControl.count
        public String nextOffset;
    }

    public static Page mainList(long aid, long nextCursor, int mode, String offset) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(1, aid),
                Proto.fv(2, 1),
                Proto.fb(3, Proto.cat(Proto.fv(1, nextCursor), Proto.fv(4, mode))),
                offset == null ? null : Proto.fb(10, Proto.fb(2, offset.getBytes(StandardCharsets.UTF_8)))
        );
        byte[] resp = grpcRaw(MAIN, req);
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
        for (byte[] rb : Proto.getAllB(resp, 2)) {
            Reply r = Reply.parse(rb);
            if (r.valid()) p.replies.add(r);
        }
        return p;
    }

    // ---------------- 楼中楼分页 ----------------
    public static class SubPage {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public String nextOffset;
    }

    public static SubPage detailList(long aid, long root, long cursor, int mode, String offset) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(1, aid),
                Proto.fv(2, 1),
                Proto.fv(3, root),
                Proto.fv(4, root),
                cursor == 0 ? null : Proto.fb(5, Proto.cat(Proto.fv(1, cursor), Proto.fv(4, 0))),
                Proto.fv(6, 1),      // scene = REPLY
                Proto.fv(7, mode),
                offset == null ? null : Proto.fb(8, Proto.fb(2, offset.getBytes(StandardCharsets.UTF_8)))
        );
        byte[] resp = grpcRaw(DETAIL, req);
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

    // ---------------- 视频信息 ----------------
    public static class Video { public long aid; public String bvid = ""; public String title = ""; public long replyCount; public long duration; public String ownerName = ""; public long ownerMid; }

    public static Video videoInfo(String id) throws Exception {
        String q = id.startsWith("BV") ? "?bvid=" + id : "?aid=" + id.replace("av", "");
        String body = viewJson(q);
        Video v = new Video();
        v.aid = jLong(body, "aid");
        v.bvid = jStr(body, "bvid");
        v.title = jStr(body, "title"); int oi = body.indexOf("\"owner\""); if (oi > 0) { String ob = body.substring(oi); v.ownerName = jStr(ob, "name"); v.ownerMid = jLong(ob, "mid"); }
        v.duration = jLong(body, "duration");
        int i = body.indexOf("\"reply\"");
        if (i > 0) {
            int s = body.indexOf(':', i) + 1, e = s;
            while (e < body.length() && (Character.isDigit(body.charAt(e)))) e++;
            try { v.replyCount = Long.parseLong(body.substring(s, e).trim()); } catch (Exception ignored) {}
        }
        return v;
    }

    // ---------------- 底层 HTTP ----------------
    public static byte[] grpcRaw(String path, byte[] payload) throws Exception {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(0);
        framed.write((payload.length >>> 24) & 0xff);
        framed.write((payload.length >>> 16) & 0xff);
        framed.write((payload.length >>> 8) & 0xff);
        framed.write(payload.length & 0xff);
        framed.write(payload);

        HttpURLConnection c = (HttpURLConnection) new URL(HOST + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/grpc");
        c.setRequestProperty("TE", "trailers");
        c.setRequestProperty("grpc-encoding", "identity");
        c.setRequestProperty("grpc-accept-encoding", "gzip");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2");
        c.setRequestProperty("app-key", "android64");
        c.setRequestProperty("env", "prod");
        c.setRequestProperty("x-bili-aurora-zone", "sh001");
        c.setRequestProperty("platform", "android");
        c.setRequestProperty("mobi_app", "android");
        c.setRequestProperty("buvid", "XY00000000000000000000000000000000000");
        if (COOKIE != null && !COOKIE.isEmpty()) c.setRequestProperty("cookie", COOKIE);

        try (OutputStream os = c.getOutputStream()) { os.write(framed.toByteArray()); }

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) raw.write(buf, 0, n);
        in.close();

        byte[] all = raw.toByteArray();
        if (all.length < 5) throw new Exception("grpc 响应过短 (" + all.length + ")");

        boolean gz = all[0] == 1;
        int len = ((all[1] & 0xff) << 24) | ((all[2] & 0xff) << 16) | ((all[3] & 0xff) << 8) | (all[4] & 0xff);
        if (len <= 0 || len + 5 > all.length) len = all.length - 5;

        byte[] msg = new byte[len];
        System.arraycopy(all, 5, msg, 0, len);
        if (!gz) return msg;

        try (GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(msg))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while ((n = g.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        if (COOKIE != null && !COOKIE.isEmpty()) c.setRequestProperty("cookie", COOKIE);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // 极简 JSON 取值（避免引入依赖）
    static String jStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int s = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        int e = s;
        while (e < json.length()) {
            char ch = json.charAt(e);
            if (ch == '"' && json.charAt(e - 1) != '\\') break;
            e++;
        }
        return json.substring(s, e);
    }
    static long jLong(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return 0;
        int s = json.indexOf(':', i) + 1, e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        try { return Long.parseLong(json.substring(s, e).trim()); } catch (Exception ex) { return 0; }
    }

    /** 带风控重试 + wbi 回退的视频信息 JSON 获取。 */
    static String viewJson(String q) throws Exception {
        Exception last = null;
        for (int a = 0; a < 4; a++) {
            if (a > 0) { try { Thread.sleep(400L * a); } catch (InterruptedException ignored) {} }
            try {
                String b = httpGet("https://api.bilibili.com/x/web-interface/view" + q);
                if (b != null && b.contains("\"aid\"")) return b;
                last = new Exception("view 无 aid");
            } catch (Exception e) { last = e; }
        }
        try {
            Map<String, String> p = new LinkedHashMap<>();
            int bi = q.indexOf("bvid=");
            int ai = q.indexOf("aid=");
            if (bi >= 0) p.put("bvid", q.substring(bi + 5));
            else if (ai >= 0) p.put("aid", q.substring(ai + 4));
            WbiSign.sign(p);
            String b = httpGet("https://api.bilibili.com/x/web-interface/wbi/view?" + SpaceApi.buildQuery(p));
            if (b != null && b.contains("\"aid\"")) return b;
        } catch (Exception e) { last = e; }
        throw last != null ? last : new Exception("视频信息获取失败");
    }
}
