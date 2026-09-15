package com.piliplus.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 属地回填。
 *
 * 背景：App 端 gRPC MainList/DetailList 不下发 ReplyControl.location，
 * 该字段只在网页端接口 /x/v2/reply/wbi/main 的 reply_control.location 返回。
 * 因此拉完 gRPC 评论后，用网页端接口按 rpid 建映射，回填 Reply.location。
 *
 * 只填 location，不动其它字段；拉不到就保持空，md 里自动不显示该行。
 */
public final class IpBackfill {

    private IpBackfill() {}

    private static final String MAIN = "https://api.bilibili.com/x/v2/reply/wbi/main";
    private static final long THROTTLE_MS = 80;
    private static final int MAX_PAGES = 200;

    /** 拉网页端评论，按 rpid 建 location 映射。失败返回空 map，不抛。 */
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
                        String loc = locOf(m);
                        if (loc != null && !loc.isEmpty()) map.put(rpid, loc);

                        // 楼中楼（网页端首屏内嵌）
                        List<Object> subs = Json2.arr(m.get("replies"));
                        if (subs != null) {
                            for (Object so : subs) {
                                Map<String, Object> sm = Json2.obj(so);
                                if (sm == null) continue;
                                long srpid = Json2.lng(sm, "rpid");
                                if (srpid == 0) continue;
                                String sl = locOf(sm);
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

    /** 回填：对 mains 及楼中楼按 id 补 location。返回填上的条数。 */
    public static int apply(long oid, int type, List<Reply> mains) {
        if (mains == null || mains.isEmpty()) return 0;
        Map<Long, String> map = fetchLocations(oid, type);
        if (map.isEmpty()) return 0;
        int n = 0;
        for (Reply m : mains) {
            if (fill(m, map)) n++;
            for (Reply s : m.subs) if (fill(s, map)) n++;
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

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
