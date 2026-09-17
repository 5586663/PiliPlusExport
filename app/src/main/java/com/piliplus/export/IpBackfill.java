package com.piliplus.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 属地回填。
 *
 * 两条路径：
 * 1) 网页端 wbi/main 按 rpid 建映射，回填主评论 + 内嵌首屏楼中楼。
 * 2) 对楼中楼仍缺 location 的主评论，走 gRPC DetailList(scene=REPLY) 拉全量补。
 */
public final class IpBackfill {

    private IpBackfill() {}

    private static final String MAIN = "https://api.bilibili.com/x/v2/reply/wbi/main";
    private static final long THROTTLE_MS = 80;
    private static final int MAX_PAGES = 200;
    private static final int SUB_MAX_PAGES = 50;
    private static final long SUB_THROTTLE_MS = 40;

    public static volatile int statWeb = 0;
    public static volatile int statGrpcRoots = 0;
    public static volatile int statGrpcHits = 0;

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

    public static int apply(long oid, int type, List<Reply> mains) {
        if (mains == null || mains.isEmpty()) return 0;
        int n = 0;

        Map<Long, String> map = fetchLocations(oid, type);
        if (!map.isEmpty()) {
            for (Reply m : mains) {
                if (fill(m, map)) n++;
                for (Reply s : m.subs) if (fill(s, map)) n++;
            }
        }
        statWeb = n;

        int roots = 0, hits = 0;
        for (Reply m : mains) {
            if (m.subs == null || m.subs.isEmpty()) continue;
            boolean need = false;
            for (Reply s : m.subs) {
                if (s.location == null || s.location.trim().isEmpty()) { need = true; break; }
            }
            if (!need) continue;
            roots++;
            try {
                List<Reply> full = fetchSubsViaGrpc(oid, type, m.id);
                Map<Long, String> subMap = new HashMap<>();
                for (Reply x : full) {
                    if (x.location != null && !x.location.trim().isEmpty()) subMap.put(x.id, x.location);
                }
                if (!subMap.isEmpty()) {
                    for (Reply s : m.subs) if (fill(s, subMap)) { n++; hits++; }
                }
            } catch (Throwable t) { }
            sleep(SUB_THROTTLE_MS);
        }
        statGrpcRoots = roots;
        statGrpcHits = hits;
        return n;
    }

    private static List<Reply> fetchSubsViaGrpc(long oid, int type, long root) {
        List<Reply> out = new ArrayList<>();
        long cursor = 0;
        String offset = null;
        String prevKey = "";
        int stall = 0;
        for (int pg = 0; pg < SUB_MAX_PAGES; pg++) {
            if (pg > 0) sleep(SUB_THROTTLE_MS);
            ReplyApi2.SubPage sp;
            try { sp = ReplyApi2.detailList(oid, type, root, cursor, 2, offset); }
            catch (Exception e) { break; }
            if (sp.replies.isEmpty()) break;
            StringBuilder key = new StringBuilder();
            for (Reply x : sp.replies) key.append(x.id).append(',');
            if (key.toString().equals(prevKey)) { if (++stall >= 3) break; }
            else { stall = 0; prevKey = key.toString(); }
            out.addAll(sp.replies);
            if (sp.replies.size() < 20) break;
            if (sp.nextCursor != 0) cursor = sp.nextCursor;
            if (sp.nextOffset != null) offset = sp.nextOffset;
        }
        return out;
    }

    private static boolean fill(Reply r, Map<Long, String> map) {
        if (r == null) return false;
        if (r.location != null && !r.location.trim().isEmpty()) return false;
        String v = map.get(r.id);
        if (v == null || v.isEmpty()) return false;
        r.location = v;
        return true;
    }

    private static String locOf(Map<String, Object> m) {
        Map<String, Object> ctrl = Json2.obj(m.get("reply_control"));
        if (ctrl == null) return null;
        return Json2.str(ctrl, "location");
    }

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
