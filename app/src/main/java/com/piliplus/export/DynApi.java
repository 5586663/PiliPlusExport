package com.piliplus.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UP主动态列表（HTTP web 接口 + WBI 签名）。
 *
 * 为什么不用 gRPC：
 *   OpusSpaceFlow 的 Extend 消息逐字段核实后不含发布时间（已查 v2.pb.dart 全部字段）。
 *   发布时间在 web 接口的 module_author.pub_ts。
 *
 * 接口：GET https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space
 *   参数对齐 PiliPlus lib/http/member.dart:439 memberDynamic()
 *
 * 响应结构（PiliPlus lib/models/dynamics/result.dart 已核实）：
 *   data.items[]                        动态数组
 *   item.id_str                         动态 id 字符串
 *   item.type                           类型字符串
 *   item.basic.comment_id_str           评论 oid
 *   item.basic.comment_type             评论 type（动态为 17）
 *   item.modules.module_author.pub_ts   发布时间戳（秒）★
 *   item.modules.module_author.pub_time 发布时间文本
 *   item.modules.module_author.pub_action
 *   item.modules.module_dynamic.desc.text          正文
 *   item.modules.module_dynamic.major.type         MAJOR_TYPE_*
 *   item.modules.module_dynamic.major.archive.*    视频卡片 bvid/title/cover
 *   item.modules.module_dynamic.major.opus.*       opus 标题/图片/摘要
 *   item.modules.module_stat.comment.count         评论数
 *   item.modules.module_stat.like.count            点赞数
 *   data.offset                         下一页游标
 *   data.has_more                       是否还有
 */
public final class DynApi {

    private DynApi() {}

    public static final String URL = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space";
    private static final String FEATURES = "itemOpusStyle,listOnlyfans,onlyfansQaCard";
    private static final String PC_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.2 Safari/605.1.15";

    public static class Page {
        public List<DynItem> items = new ArrayList<>();
        public String offset = null;
        public boolean hasMore;
    }

    /** 拉一页动态（带 WBI 签名） */
    public static Page memberDynamic(long mid, String offset) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("offset", offset == null ? "" : offset);
        p.put("host_mid", String.valueOf(mid));
        p.put("timezone_offset", "-480");
        p.put("features", FEATURES);
        p.put("platform", "web");
        p.put("web_location", "333.1387");
        p.put("dm_img_list", "[]");
        p.put("dm_img_str", "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ");
        p.put("dm_cover_img_str", "QU5HTEUgKEludGVsLCBNZXNhIEludGVsKFIpIFVIRCBHcmFwaGljcyAoQ01MIEdUMiksIE9wZW5HTCA0LjYpR29vZ2xlIEluYy4gKEludGVsKQ");
        p.put("dm_img_inter", "{\"ds\":[],\"wh\":[0,0,0],\"of\":[0,0,0]}");

        WbiSign.sign(p);

        StringBuilder q = new StringBuilder("?");
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 1) q.append('&');
            q.append(e.getKey()).append('=').append(urlEnc(e.getValue()));
        }

        String body = SpaceApi.getWithHeaders(URL + q, PC_UA, "https://space.bilibili.com/" + mid + "/dynamic");
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("动态响应解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) {
            throw new Exception("动态 code=" + code + " msg=" + Json2.str(root, "message"));
        }
        Map<String, Object> data = Json2.obj(root.get("data"));
        Page page = new Page();
        if (data == null) return page;

        Object hm = data.get("has_more");
        page.hasMore = (hm instanceof Boolean) && (Boolean) hm;
        page.offset = Json2.str(data, "offset");

        List<Object> items = Json2.arr(data.get("items"));
        if (items != null) {
            for (Object o : items) {
                DynItem d = parseItem(Json2.obj(o));
                if (d != null) page.items.add(d);
            }
        }
        return page;
    }

    // ------------------------------------------------------------------
    private static DynItem parseItem(Map<String, Object> it) {
        if (it == null) return null;
        DynItem d = new DynItem();
        d.dynIdStr = nz(Json2.str(it, "id_str"));
        d.typeStr = nz(Json2.str(it, "type"));

        Map<String, Object> basic = Json2.obj(it.get("basic"));
        if (basic != null) {
            d.commentOidStr = nz(Json2.str(basic, "comment_id_str"));
            d.commentType = (int) Json2.lng(basic, "comment_type");
            if (d.commentOidStr.isEmpty()) d.commentOidStr = nz(Json2.str(basic, "rid_str"));
            d.oid = parseLong(d.commentOidStr);
        }

        Map<String, Object> modules = Json2.obj(it.get("modules"));
        if (modules == null) return d;

        // ---- 作者信息：时间在这里 ----
        Map<String, Object> author = Json2.obj(modules.get("module_author"));
        if (author != null) {
            d.pubTs = Json2.lng(author, "pub_ts");
            d.pubTimeText = nz(Json2.str(author, "pub_time"));
            d.pubAction = nz(Json2.str(author, "pub_action"));
            d.authorName = nz(Json2.str(author, "name"));
        }

        // ---- 正文与主体 ----
        Map<String, Object> dyn = Json2.obj(modules.get("module_dynamic"));
        if (dyn != null) {
            Map<String, Object> desc = Json2.obj(dyn.get("desc"));
            if (desc != null) d.text = nz(Json2.str(desc, "text"));

            Map<String, Object> major = Json2.obj(dyn.get("major"));
            if (major != null) {
                d.majorType = nz(Json2.str(major, "type"));
                // 视频卡片
                Map<String, Object> archive = Json2.obj(major.get("archive"));
                if (archive == null) archive = Json2.obj(major.get("ugc_season"));
                if (archive != null) {
                    d.bvid = nz(Json2.str(archive, "bvid"));
                    d.title = nz(Json2.str(archive, "title"));
                    d.cover = nz(Json2.str(archive, "cover"));
                }
                // opus（图文）
                Map<String, Object> opus = Json2.obj(major.get("opus"));
                if (opus != null) {
                    if (d.title.isEmpty()) d.title = nz(Json2.str(opus, "title"));
                    Map<String, Object> summary = Json2.obj(opus.get("summary"));
                    if (summary != null && d.text.isEmpty()) d.text = nz(Json2.str(summary, "text"));
                    List<Object> pics = Json2.arr(opus.get("pics"));
                    if (pics != null) {
                        for (Object po : pics) {
                            Map<String, Object> pm = Json2.obj(po);
                            if (pm == null) continue;
                            String src = Json2.str(pm, "url");
                            if (src == null) src = Json2.str(pm, "src");
                            if (src != null && !src.isEmpty()) d.images.add(src);
                        }
                    }
                }
            }
        }

        // ---- 互动数 ----
        Map<String, Object> stat = Json2.obj(modules.get("module_stat"));
        if (stat != null) {
            Map<String, Object> cm = Json2.obj(stat.get("comment"));
            if (cm != null) d.replyCount = Json2.lng(cm, "count");
            Map<String, Object> lk = Json2.obj(stat.get("like"));
            if (lk != null) d.likeCount = Json2.lng(lk, "count");
        }
        return d;
    }

    // ------------------------------------------------------------------
    private static String nz(String s) { return s == null ? "" : s; }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
