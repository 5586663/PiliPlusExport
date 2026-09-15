package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UP主空间接口（web 通道 + WBI 签名）。
 *
 * 为什么不用 App 接口 /x/v2/space：
 *   App 接口要求 access_key 签名（PiliPlus 的 AccountManager 拦截器自动附加），
 *   模块跑在进程内但拿不到 access_key，实测返回 code=-400。
 *
 * 改用 web 接口，只需登录 cookie + WBI 签名：
 *   用户信息：https://api.bilibili.com/x/space/wbi/acc/info?mid=X
 *   投稿列表：https://api.bilibili.com/x/space/wbi/arc/search?mid=X&pn=N&ps=30
 */
public final class SpaceApi {

    private SpaceApi() {}

    public static final String PC_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.2 Safari/605.1.15";
    public static final String ACC_INFO = "https://api.bilibili.com/x/space/wbi/acc/info";
    public static final String ARC_SEARCH = "https://api.bilibili.com/x/space/wbi/arc/search";
    public static final String RELATION_STAT = "https://api.bilibili.com/x/relation/stat";

    public static class VideoPage {
        public List<VideoItem> items = new ArrayList<>();
        public int next = 0;
        public boolean hasNext;
        public int total = 0;
    }

    public static class UpInfo {
        public long mid;
        public String name = "";
        public String sign = "";
        public long fans;
        public long archiveCount;
        public String face = "";
    }

    // ==================================================================
    /** 用户信息（web + WBI） */
    public static UpInfo spaceInfo(long mid) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mid", String.valueOf(mid));
        p.put("platform", "web");
        p.put("web_location", "1550101");
        WbiSign.sign(p);

        String url = ACC_INFO + "?" + buildQuery(p);
        String body = getWithHeaders(url, PC_UA, "https://space.bilibili.com/" + mid);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("space 响应解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) {
            throw new Exception("space code=" + code + " msg=" + nz(Json2.str(root, "message")));
        }
        Map<String, Object> data = Json2.obj(root.get("data"));
        UpInfo u = new UpInfo();
        u.mid = mid;
        if (data != null) {
            u.name = nz(Json2.str(data, "name"));
            u.sign = nz(Json2.str(data, "sign"));
            u.face = fixUrl(nz(Json2.str(data, "face")));
        }
        // 粉丝数：单独接口，失败不影响主流程
        try {
            String st = getWithHeaders(RELATION_STAT + "?vmid=" + mid, PC_UA,
                    "https://space.bilibili.com/" + mid);
            Map<String, Object> sr = Json2.obj(Json2.parse(st));
            if (sr != null && Json2.lng(sr, "code") == 0) {
                Map<String, Object> sd = Json2.obj(sr.get("data"));
                if (sd != null) u.fans = Json2.lng(sd, "follower");
            }
        } catch (Throwable ignored) {}
        return u;
    }

    // ==================================================================
    /** 投稿视频分页（web + WBI）。pn 从 1 起。 */
    public static VideoPage archiveCursor(long mid, int pn, String order) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mid", String.valueOf(mid));
        p.put("ps", "30");
        p.put("pn", String.valueOf(pn));
        p.put("order", order == null ? "pubdate" : order);
        p.put("platform", "web");
        p.put("web_location", "1550101");
        WbiSign.sign(p);

        String url = ARC_SEARCH + "?" + buildQuery(p);
        String body = getWithHeaders(url, PC_UA, "https://space.bilibili.com/" + mid + "/video");
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        VideoPage out = new VideoPage();
        if (root == null) return out;
        long code = Json2.lng(root, "code");
        if (code != 0) {
            throw new Exception("archive code=" + code + " msg=" + nz(Json2.str(root, "message")));
        }
        Map<String, Object> data = Json2.obj(root.get("data"));
        if (data == null) return out;

        Map<String, Object> list = Json2.obj(data.get("list"));
        List<Object> vlist = list == null ? null : Json2.arr(list.get("vlist"));
        if (vlist != null) {
            for (Object o : vlist) {
                Map<String, Object> m = Json2.obj(o);
                if (m == null) continue;
                VideoItem v = new VideoItem();
                v.aid = Json2.lng(m, "aid");
                v.bvid = nz(Json2.str(m, "bvid"));
                v.title = nz(Json2.str(m, "title"));
                v.cover = fixUrl(nz(Json2.str(m, "pic")));
                v.length = nz(Json2.str(m, "length"));
                v.play = Json2.lng(m, "play");
                v.reply = Json2.lng(m, "comment");
                long created = Json2.lng(m, "created");
                if (created > 0) v.pubTime = fmtDate(created);
                if (v.aid != 0 || !v.bvid.isEmpty()) out.items.add(v);
            }
        }

        Map<String, Object> page = Json2.obj(data.get("page"));
        if (page != null) out.total = (int) Json2.lng(page, "count");
        out.hasNext = (pn * 30) < out.total;
        out.next = out.hasNext ? (pn + 1) : 0;
        return out;
    }

    // ==================================================================
    static String nz(String s) { return s == null ? "" : s; }

    static String fixUrl(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.startsWith("//")) return "https:" + s;
        return s;
    }

    static String fmtDate(long sec) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(sec * 1000L));
    }

    /** 按 key 排序拼 query（与 WbiSign 签名顺序一致） */
    static String buildQuery(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);
        StringBuilder q = new StringBuilder();
        for (String k : keys) {
            if (q.length() > 0) q.append('&');
            q.append(urlEnc(k)).append('=').append(urlEnc(params.get(k)));
        }
        return q.toString();
    }

    static String urlEnc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** App 头 GET（WbiSign 取 nav 用） */
    static String get(String url) throws Exception {
        return getWithHeaders(url, null, null);
    }

    /** 带自定义 UA / Referer 的 GET */
    static String getWithHeaders(String url, String ua, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua == null ? PC_UA : ua);
        c.setRequestProperty("env", "prod");
        c.setRequestProperty("app-key", "android64");
        c.setRequestProperty("x-bili-aurora-zone", "sh001");
        c.setRequestProperty("bili-http-engine", "cronet");
        c.setRequestProperty("platform", "android");
        c.setRequestProperty("mobi_app", "android");
        c.setRequestProperty("buvid", "XY00000000000000000000000000000000000");
        if (referer != null) {
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Origin", "https://space.bilibili.com");
        }
        String ck = BiliApi.COOKIE;
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("cookie", ck);

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
