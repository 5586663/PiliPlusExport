package com.piliplus.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取视频播放地址（web + WBI），走 dash 分离流。
 *
 * cid 来自 https://api.bilibili.com/x/web-interface/view 的 data.pages[0].cid
 * 播放：https://api.bilibili.com/x/player/wbi/playurl?bvid=X&cid=Y&qn=127&fnval=4048&fnver=0&fourk=1
 *
 * dash.video[]  { id, baseUrl, backupUrl[], bandwidth, mimeType, codecs, width, height, frameRate }
 * dash.audio[]  { id, baseUrl, backupUrl[], bandwidth, mimeType, codecs }
 *
 * 选流策略：视频取 height 最大者（同级取 bandwidth 最大）；音频取 bandwidth 最大者。
 */
public final class PlayUrlApi {

    private PlayUrlApi() {}

    public static final String PLAYURL = "https://api.bilibili.com/x/player/wbi/playurl";
    public static final String VIEW = "https://api.bilibili.com/x/web-interface/view";

    public static class Dash {
        public String videoUrl = "";
        public String videoBackup = "";
        public String audioUrl = "";
        public String audioBackup = "";
        public int width, height;
        public long videoBandwidth, audioBandwidth;
        public String videoCodecs = "";
        public String audioCodecs = "";
        public long duration;
        public String qualityDesc = "";
    }

    /** 取 cid（多 P 取第一 P） */
    public static long cidOf(String bvid, long aid) throws Exception {
        String q = (bvid != null && !bvid.isEmpty()) ? "?bvid=" + bvid : "?aid=" + aid;
        String body = BiliApi.httpGet(VIEW + q);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("view 解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) throw new Exception("view code=" + code + " " + nz(Json2.str(root, "message")));
        Map<String, Object> data = Json2.obj(root.get("data"));
        if (data == null) throw new Exception("view data 为空");
        List<Object> pages = Json2.arr(data.get("pages"));
        if (pages != null && !pages.isEmpty()) {
            Map<String, Object> p0 = Json2.obj(pages.get(0));
            if (p0 != null) {
                long cid = Json2.lng(p0, "cid");
                if (cid > 0) return cid;
            }
        }
        long cid = Json2.lng(data, "cid");
        if (cid > 0) return cid;
        throw new Exception("拿不到 cid");
    }

    /** 取 dash 流 */
    public static Dash dash(String bvid, long aid, long cid) throws Exception {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        if (bvid != null && !bvid.isEmpty()) p.put("bvid", bvid);
        else p.put("avid", String.valueOf(aid));
        p.put("cid", String.valueOf(cid));
        p.put("qn", "127");
        p.put("fnval", "4048");
        p.put("fnver", "0");
        p.put("fourk", "1");
        WbiSign.sign(p);

        String ref = "https://www.bilibili.com/video/" + ((bvid != null && !bvid.isEmpty()) ? bvid : "av" + aid);
        String body = SpaceApi.getWithHeaders(PLAYURL + "?" + SpaceApi.buildQuery(p), SpaceApi.PC_UA, ref);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("playurl 解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) throw new Exception("playurl code=" + code + " " + nz(Json2.str(root, "message")));
        Map<String, Object> data = Json2.obj(root.get("data"));
        if (data == null) throw new Exception("playurl data 为空");

        Map<String, Object> dash = Json2.obj(data.get("dash"));
        if (dash == null) throw new Exception("无 dash 流（该视频可能仅支持 durl，或需登录）");

        Dash d = new Dash();
        d.duration = Json2.lng(dash, "duration");

        // ---- 选视频轨 ----
        List<Object> vs = Json2.arr(dash.get("video"));
        Map<String, Object> bestV = null;
        if (vs != null) {
            for (Object o : vs) {
                Map<String, Object> m = Json2.obj(o);
                if (m == null) continue;
                if (bestV == null) { bestV = m; continue; }
                int h = (int) Json2.lng(m, "height");
                int bh = (int) Json2.lng(bestV, "height");
                if (h > bh) bestV = m;
                else if (h == bh && Json2.lng(m, "bandwidth") > Json2.lng(bestV, "bandwidth")) bestV = m;
            }
        }
        if (bestV == null) throw new Exception("dash.video 为空");
        d.videoUrl = nz(Json2.str(bestV, "baseUrl"));
        d.videoBackup = firstUrl(bestV, "backupUrl");
        d.videoBandwidth = Json2.lng(bestV, "bandwidth");
        d.videoCodecs = nz(Json2.str(bestV, "codecs"));
        d.width = (int) Json2.lng(bestV, "width");
        d.height = (int) Json2.lng(bestV, "height");

        // ---- 选音频轨 ----
        List<Object> as = Json2.arr(dash.get("audio"));
        Map<String, Object> bestA = null;
        if (as != null) {
            for (Object o : as) {
                Map<String, Object> m = Json2.obj(o);
                if (m == null) continue;
                if (bestA == null || Json2.lng(m, "bandwidth") > Json2.lng(bestA, "bandwidth")) bestA = m;
            }
        }
        if (bestA != null) {
            d.audioUrl = nz(Json2.str(bestA, "baseUrl"));
            d.audioBackup = firstUrl(bestA, "backupUrl");
            d.audioBandwidth = Json2.lng(bestA, "bandwidth");
            d.audioCodecs = nz(Json2.str(bestA, "codecs"));
        }

        if (d.videoUrl.isEmpty()) throw new Exception("视频轨 URL 为空");
        return d;
    }

    private static String firstUrl(Map<String, Object> m, String key) {
        List<Object> arr = Json2.arr(m.get(key));
        if (arr == null || arr.isEmpty()) return "";
        Object o = arr.get(0);
        return o == null ? "" : String.valueOf(o);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
