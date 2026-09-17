package com.piliplus.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 属地回填。
 *
 * 背景：App 端 gRPC MainList/DetailList 不下发 ReplyControl.location，
 * 该字段只在网页端接口返回。
 *
 * 两级回填：
 *  1) /x/v2/reply/wbi/main 按 rpid 建 location 映射，并顺带取主评论首屏内嵌的少量楼中楼；
 *  2) /x/v2/reply/wbi/reply 针对仍缺 IP 的主评论，按 root 拉全部楼中楼补全。
 *     （main 接口只内嵌首屏楼中楼，深层“回复的回复”必须走这一步）
 *
 * 网页端 location 自带「IP属地：」前缀，这里统一剥掉，只留地名。
 */
public final class IpBackfill {

    private IpBackfill() {}

    private static final String MAIN = "https://api.bilibili.com/x/v2/reply/wbi/main";
    private static final String SUB  = "https://api.bilibili.com/x/v2/reply/wbi/reply";
    private static final long THROTTLE_MS = 80;
    private static final int MAX_PAGES = 200;
    private static final int SUB_MAX_PAGES = 50;

    /** 拉网页端主评论，按 rpid 建 location 映射（含首屏楼中楼）。失败返回空 map，不抛。 */
    public static Map<Long, String> fetchLocations(long oid, int type) {
        Map<Long, String> map = new HashMap<>();
        String next = "0";
        boolean isEnd = false;
        for (int pg = 0; pg < MAX_PAGES && !isEnd; pg++) {
            if (pg > 0) sleep(THROTTLE_MS);
            try {
                LinkedHashMap<String, String> p = new LinkedHashMap<>();
                p.put("oid", String.valueOf(oid));
                p.put("type", String.valueOf(type));
                p.put("mode", "3");
                p.put("next", next);
                p.put("ps", "20");
                WbiSign.sign(p);
                String url = MAIN + "?" + SpaceApi.buildQuery(p);
                String body = SpaceApi.getWithHeaders(url, SpaceApi.PC_UA,
                        "https://www.bilibili.com/");
                Map<String, Object> root = Json2.obj(Json2.parse(body));
                if (root == null || Json2.lng(root, "code") != 0) break;
                Map<String, Object> data = Json2.obj(root.get("data"));
                if (data == null) break;

                List<Object> replies = Json2.arr(data.get("replies"));
                if (replies != null) {
                    for (Object o : replies) {
                        Map<String, Object> m = Json2.obj(o);
                        if (m == null) continue;
                        long rpid = Json2.lng(m, "rpid");
                        if (rpid == 0) continue;
                        String loc = clean(locOf(m));
                        if (loc != null && !loc.isEmpty()) map.put(rpid, loc);
                        List<Object> subs = Json2.arr(m.get("replies"));
                        if (subs != null) {
                            for (Object so : subs) {
                                Map<String, Object> sm = Json2.obj(so);
                                if (sm == null) continue;
                                long srpid = Json2.lng(sm, "rpid");
                                if (srpid == 0) continue;
                                String sl = clean(locOf(sm));
                                if (sl != null && !sl.isEmpty()) map.put(srpid, sl);
                            }
                        }
                    }
                }

                Map<String, Object> cursor = Json2.obj(data.get("cursor"));
                if (cursor != null) {
                    isEnd = Json2.lng(cursor, "is_end") != 0;
                    String nn = Json2.str(cursor, "next");
                    if (nn != null && !nn.isEmpty()) next = nn;
                } else break;
                List<Object> top = Json2.arr(data.get("top_replies"));
                if (top == null && (replies == null || replies.isEmpty())) break;
            } catch (Throwable t) {
                break;
            }
        }
        return map;
    }

    /** 按 root（主评论 rpid）拉全部楼中楼，建 location 映射。失败返回空 map。 */
    public static Map<Long, String> fetchSubLocations(long oid, int type, long root) {
        Map<Long, String> map = new HashMap<>();
        for (int pn = 1; pn <= SUB_MAX_PAGES; pn++) {
            if (pn > 1) sleep(THROTTLE_MS);
            try {
                LinkedHashMap<String, String> p = new LinkedHashMap<>();
                p.put("oid", String.valueOf(oid));
                p.put("type", String.valueOf(type));
                p.put("root", String.valueOf(root));
                p.put("ps", "20");
                p.put("pn", String.valueOf(pn));
                WbiSign.sign(p);
                String url = SUB + "?" + SpaceApi.buildQuery(p);
                String body = SpaceApi.getWithHeaders(url, SpaceApi.PC_UA,
                        "https://www.bilibili.com/");
                Map<String, Object> r = Json2.obj(Json2.parse(body));
                if (r == null || Json2.lng(r, "code") != 0) break;
                Map<String, Object> data = Json2.obj(r.get("data"));
                if (data == null) break;
                List<Object> subs = Json2.arr(data.get("replies"));
                if (subs == null || subs.isEmpty()) break;
                for (Object o : subs) {
                    Map<String, Object> m = Json2.obj(o);
                    if (m == null) continue;
                    long rpid = Json2.lng(m, "rpid");
                    if (rpid == 0) continue;
                    String loc = clean(locOf(m));
                    if (loc != null && !loc.isEmpty()) map.put(rpid, loc);
                }
                Map<String, Object> page = Json2.obj(data.get("page"));
                if (page != null) {
                    long count = Json2.lng(page, "count");
                    if (pn * 20 >= count) break;
                } else if (subs.size() < 20) {
                    break;
                }
            } catch (Throwable t) {
                break;
            }
        }
        return map;
    }

    /** 回填：先主评论映射，再对仍缺 IP 的楼中楼按 root 二次回填。返回填上的条数。 */
    public static int apply(long oid, int type, List<Reply> mains) {
        if (mains == null || mains.isEmpty()) return 0;
        Map<Long, String> map = fetchLocations(oid, type);
        int n = 0;
        for (Reply m : mains) {
            if (fill(m, map)) n++;
            for (Reply s : m.subs) if (fill(s, map)) n++;
        }

        // 二次回填：对仍有缺 IP 楼中楼的主评论，走 x/v2/reply/wbi/reply 按 root 拉全
        for (Reply m : mains) {
            if (m == null || m.subs == null || m.subs.isEmpty()) continue;
            boolean missing = false;
            for (Reply s : m.subs) {
                if (s != null && (s.location == null || s.location.trim().isEmpty())) { missing = true; break; }
            }
            if (!missing) continue;
            Map<Long, String> subMap = fetchSubLocations(oid, type, m.id);
            if (subMap.isEmpty()) continue;
            for (Reply s : m.subs) if (fill(s, subMap)) n++;
        }
        return n;
    }

    private static boolean fill(Reply r, Map<Long, String> map) {
        if (r == null) return false;
        if (r.location != null && !r.location.trim().isEmpty()) return false;
        String v = map.get(r.id);
        if (v == null || v.isEmpty()) return false;
        r.location = v;
        return true;
    }

    /** 从单条网页端 reply 对象取 location（reply_control.location） */
    private static String locOf(Map<String, Object> m) {
        Map<String, Object> ctrl = Json2.obj(m.get("reply_control"));
        if (ctrl == null) return null;
        return Json2.str(ctrl, "location");
    }

    /** 剥掉「IP属地：」「IP属地:」前缀与空白，只留地名 */
    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("IP属地：")) t = t.substring(5).trim();
        else if (t.startsWith("IP属地:")) t = t.substring(5).trim();
        return t;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
