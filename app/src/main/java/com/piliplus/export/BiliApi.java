package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class BiliApi {

    public static final String MAIN   = "/bilibili.main.community.reply.v1.Reply/MainList";
    public static final String DETAIL = "/bilibili.main.community.reply.v1.Reply/DetailList";
    public static final String HOST   = "https://app.bilibili.com";

    public static volatile String COOKIE = "";
    public static volatile String ACCESS_KEY = "";

    public interface Log { void on(String s); }
    private static Log log = s -> {};
    public static void setLog(Log l) { log = l; }

    public static class Page {
        public List<Reply> replies = new ArrayList<>();
        public long nextCursor;
        public boolean isEnd;
        public long totalCount;
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
                Proto.fv(6, 1),
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

    public static class Video { public long aid; public String bvid = ""; public String title = ""; public long replyCount; public long duration; public String ownerName = ""; public long ownerMid; }

    public static Video videoInfo(String id) throws Exception {
        String q = id.startsWith("BV") ? "?bvid=" + id : "?aid=" + id.replace("av", "");
        String body = httpGet("https://api.bilibili.com/x/web-interface/view" + q);
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

    public static byte[] grpcRaw(String path, byte[] payload) throws Exception {
        byte[] body = payload;
        int flag = 0;
        if (payload.length > 64) {
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            GZIPOutputStream g = new GZIPOutputStream(gz);
            g.write(payload);
            g.close();
            body = gz.toByteArray();
            flag = 1;
        }
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(flag);
        framed.write((body.length >>> 24) & 0xff);
        framed.write((body.length >>> 16) & 0xff);
        framed.write((body.length >>> 8) & 0xff);
        framed.write(body.length & 0xff);
        framed.write(body);

        HttpURLConnection c = (HttpURLConnection) new URL(HOST + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/grpc");
        c.setRequestProperty("TE", "trailers");
        GrpcHeaders.apply(c, ACCESS_KEY);
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
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        GrpcHeaders.apply(c, ACCESS_KEY);
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
}
