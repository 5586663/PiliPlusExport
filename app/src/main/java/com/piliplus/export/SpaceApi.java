package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UP主空间接口。
 *
 * 视频列表：GET https://app.bilibili.com/x/v2/space/archive/cursor
 *   参数对齐 PiliPlus lib/http/member.dart:119 spaceArchive()
 * 用户信息：GET https://app.bilibili.com/x/v2/space
 *
 * 所有请求复用进程内 cookie（BiliApi.COOKIE）。
 */
public final class SpaceApi {

    private SpaceApi() {}

    public static final String BASE = "https://app.bilibili.com";
    public static final String ARCHIVE_CURSOR = "/x/v2/space/archive/cursor";
    public static final String SPACE = "/x/v2/space";

    public static final String UA =
            "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android " +
            "mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2";
    public static final String STATISTICS =
            "{\"appId\":1,\"platform\":3,\"version\":\"8.43.0\",\"abtest\":\"\"}";

    /** 一页视频列表 */
    public static class VideoPage {
        public List<VideoItem> items = new ArrayList<>();
        public int next = 0;
        public boolean hasNext;
        public int total = 0;
    }

    /** 用户基本信息 */
    public static class UpInfo {
        public long mid;
        public String name = "";
        public String sign = "";
        public long fans;
        public long archiveCount;
        public String face = "";
    }

    // ------------------------------------------------------------------
    public static UpInfo spaceInfo(long mid) throws Exception {
        String q = "?build=8430300&version=8.43.0&c_locale=zh_CN&channel=master"
                + "&mobi_app=android&platform=android&s_locale=zh_CN"
                + "&vmid=" + mid
                + "&statistics=" + enc(STATISTICS);
        String body = get(BASE + SPACE + q);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("space 响应解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) {
            throw new Exception("space code=" + code + " msg=" + Json2.str(root, "message"));
        }
        Map<String, Object> data = Json2.obj(root.get("data"));
        UpInfo u = new UpInfo();
        u.mid = mid;
        if (data != null) {
            u.name = nz(Json2.str(data, "name"));
            u.sign = nz(Json2.str(data, "sign"));
            u.face = nz(Json2.str(data, "face"));
            Map<String, Object> rel = Json2.obj(data.get("relation"));
            if (rel != null) u.fans = Json2.lng(rel, "follower");
            u.archiveCount = Json2.lng(data, "archive_count");
        }
        return u;
    }

    // ------------------------------------------------------------------
    /**
     * 拉一页投稿视频。
     *
     * @param mid   UP主 uid
     * @param pn    页码（1 起，或上一页返回的 next）
     * @param order "pubdate"（最新发布）或 "click"（最多播放）
     */
    public static VideoPage archiveCursor(long mid, int pn, String order) throws Exception {
        StringBuilder q = new StringBuilder("?build=8430300&version=8.43.0");
        q.append("&c_locale=zh_CN&channel=master&mobi_app=android&platform=android&s_locale=zh_CN");
        q.append("&ps=20");
        q.append("&pn=").append(pn);
        q.append("&qn=80");
        q.append("&order=").append(order == null ? "pubdate" : order);
        q.append("&vmid=").append(mid);
        q.append("&statistics=").append(enc(STATISTICS));

        String body = get(BASE + ARCHIVE_CURSOR + q);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        VideoPage p = new VideoPage();
        if (root == null) return p;
        long code = Json2.lng(root, "code");
        if (code != 0) {
            throw new Exception("archive code=" + code + " msg=" + Json2.str(root, "message"));
        }
        Map<String, Object> data = Json2.obj(root.get("data"));
        if (data == null) return p;

        p.total = (int) Json2.lng(data, "count");
        p.next = (int) Json2.lng(data, "next");
        Object hn = data.get("has_next");
        p.hasNext = (hn instanceof Boolean) ? (Boolean) hn : p.next > 0;

        List<Object> arr = Json2.arr(data.get("item"));
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> m = Json2.obj(o);
                if (m == null) continue;
                VideoItem v = new VideoItem();
                v.aid = Json2.lng(m, "aid");
                v.bvid = nz(Json2.str(m, "bvid"));
                v.title = nz(Json2.str(m, "title"));
                v.cover = nz(Json2.str(m, "cover"));
                v.duration = Json2.lng(m, "duration");
                v.length = nz(Json2.str(m, "length"));
                v.pubTime = nz(Json2.str(m, "publish_time_text"));

                Map<String, Object> stat = Json2.obj(m.get("stat"));
                if (stat != null) {
                    v.play = Json2.lng(stat, "view");
                    v.danmaku = Json2.lng(stat, "danmaku");
                    v.reply = Json2.lng(stat, "reply");
                    v.fav = Json2.lng(stat, "favorite");
                    v.coin = Json2.lng(stat, "coin");
                    v.like = Json2.lng(stat, "like");
                }
                if (v.aid != 0 || !v.bvid.isEmpty()) p.items.add(v);
            }
        }
        return p;
    }

    // ------------------------------------------------------------------
    private static String nz(String s) { return s == null ? "" : s; }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** 默认 App 头的 GET */
    static String get(String url) throws Exception {
        return getWithHeaders(url, UA, null);
    }

    /**
     * 带自定义 UA / Referer 的 GET。
     * web 接口（动态列表）需要 PC UA 与 space 域 Referer，否则触发风控。
     */
    static String getWithHeaders(String url, String ua, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua == null ? UA : ua);
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
