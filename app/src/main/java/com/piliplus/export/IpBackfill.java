package com.piliplus.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 属地回填（合并方案）。
 *  - 主评论：/x/v2/reply/wbi/main 建 rpid->location
 *  - 楼中楼：gRPC detailList 拉全量（保证条数全、顺序对）
 *  - 楼中楼 IP：/x/v2/reply/reply 建 rpid->location 后回填到 gRPC 结果
 * 两条来源按 rpid 合并，任一有 IP 即填。
 */
public final class IpBackfill {

    private IpBackfill() {}

    private static final String TAG = "PiliExportDiag";
    private static final String MAIN = "https://api.bilibili.com/x/v2/reply/wbi/main";
    private static final String SUB  = "https://api.bilibili.com/x/v2/reply/reply";
    private static final long THROTTLE_MS = 80;
    private static final int MAX_PAGES = 200;
    private static final int SUB_MAX_PAGES = 50;

    private static void log(String s) {
        android.util.Log.i(TAG, s);
        try {
            java.io.File f = new java.io.File("/storage/emulated/0/Download/PiliPlus_导出/diag_ip.txt");
            java.io.File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(s + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    /** 主评论 + 首屏内嵌楼中楼的 location 映射。 */
    public static Map<Long, String> fetchLocations(long oid, int type) {
        Map<Long, String> map = new HashMap<>();
        String next = "0";
        boolean isEnd = false;
        int pages = 0;
        long lastCode = -999;
        for (int pg = 0; pg < MAX_PAGES && !isEnd; pg++) {
            if (pg > 0) sleep(THROTTLE_MS);
            try {
                LinkedHashMap<String, String> p = new LinkedHashMap<>();
                p.put("oid", String.valueOf(oid));
                p.put("type", String.valueOf(type));
                p.put("mode", "2");
                p.put("next", next);
                p.put("ps", "20");
                WbiSign.sign(p);
                String url = MAIN + "?" + SpaceApi.buildQuery(p);
                String body = SpaceApi.getWithHeaders(url, SpaceApi.PC_UA,
                        "https://www.bilibili.com/");
                Map<String, Object> root = Json2.obj(Json2.parse(body));
                if (root == null) { lastCode = -998; break; }
                lastCode = Json2.lng(root, "code");
                if (lastCode != 0) break;
                Map<String, Object> data = Json2.obj(root.get("data"));
                if (data == null) break;
                pages++;
                List<Object> replies = Json2.arr(data.get("replies"));
                if (replies != null) {
                    for (Object o : replies) {
                        Map<String, Object> m = Json2.obj(o);
                        if (m == null) continue;
                        long rpid = Json2.lng(m, "rpid");
                        if (rpid != 0) {
                            String loc = clean(locOf(m));
                            if (loc != null && !loc.isEmpty()) map.put(rpid, loc);
                        }
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
                lastCode = -997;
                break;
            }
        }
        log("fetchLocations oid=" + oid + " pages=" + pages + " map=" + map.size()
                + " lastCode=" + lastCode);
        return map;
    }

    /** 某 root 下全部楼中楼的 rpid->location 映射（web）。 */
    public static Map<Long, String> fetchSubLocations(long oid, int type, long root) {
        Map<Long, String> map = new HashMap<>();
        long lastCode = -999;
        int pages = 0, got = 0;
        for (int pn = 1; pn <= SUB_MAX_PAGES; pn++) {
            if (pn > 1) sleep(THROTTLE_MS);
            try {
                LinkedHashMap<String, String> p = new LinkedHashMap<>();
                p.put("oid", String.valueOf(oid));
                p.put("type", String.valueOf(type));
                p.put("root", String.valueOf(root));
                p.put("ps", "20");
                p.put("pn", String.valueOf(pn));
                p.put("sort", "1");
                String url = SUB + "?" + SpaceApi.buildQuery(p);
                String body = SpaceApi.getWithHeaders(url, SpaceApi.PC_UA,
                        "https://www.bilibili.com/");
                Map<String, Object> r = Json2.obj(Json2.parse(body));
                if (r == null) { lastCode = -998; break; }
                lastCode = Json2.lng(r, "code");
                if (lastCode != 0) break;
                Map<String, Object> data = Json2.obj(r.get("data"));
                if (data == null) break;
                pages++;
                List<Object> subs = Json2.arr(data.get("replies"));
                if (subs == null || subs.isEmpty()) break;
                for (Object o : subs) {
                    Map<String, Object> m = Json2.obj(o);
                    if (m == null) continue;
                    long rpid = Json2.lng(m, "rpid");
                    if (rpid == 0) continue;
                    got++;
                    String loc = clean(locOf(m));
                    if (loc != null && !loc.isEmpty()) map.put(rpid, loc);
                }
                Map<String, Object> page = Json2.obj(data.get("page"));
                if (page != null) {
                    int count = (int) Json2.lng(page, "count");
                    if (count > 0 && got >= count) break;
                    if (subs.size() < 20) break;
                } else if (subs.size() < 20) {
                    break;
                }
            } catch (Throwable t) {
                lastCode = -997;
                break;
            }
        }
        log("fetchSubLocations root=" + root + " pages=" + pages + " got=" + got
                + " map=" + map.size() + " lastCode=" + lastCode);
        return map;
    }

    /** gRPC detailList 拉某 root 下全量楼中楼（条数最全）。 */
    private static List<Reply> fetchSubsViaGrpc(long oid, int type, long root) {
        List<Reply> out = new java.util.ArrayList<>();
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

    private static final long SUB_THROTTLE_MS = 40;

    public static int apply(long oid, int type, List<Reply> mains) {
        if (mains == null || mains.isEmpty()) return 0;
        int n = 0;
        int subsTotal = 0, subsLoc = 0;

        // 1) web main：主评论 + 首屏楼中楼 IP
        Map<Long, String> webMap = fetchLocations(oid, type);
        for (Reply m : mains) {
            if (m == null) continue;
            if (fill(m, webMap)) n++;
            if (m.subs != null) {
                for (Reply s : m.subs) {
                    subsTotal++;
                    if (fill(s, webMap)) n++;
                    if (s.location != null && !s.location.trim().isEmpty()) subsLoc++;
                }
            }
        }
        log("apply afterMain mains=" + mains.size() + " webMap=" + webMap.size()
                + " subs=" + subsTotal + " subsWithLoc=" + subsLoc + " filled=" + n);

        // 2) 逐个 root：gRPC 全量楼中楼 + web IP 回填
        int roots = 0, grpcTotal = 0, extra = 0;
        for (Reply m : mains) {
            if (m == null) continue;
            List<Reply> full = fetchSubsViaGrpc(oid, type, m.id);
            if (!full.isEmpty()) {
                { java.util.Set<Long> _h = new java.util.HashSet<>(); for (Reply _s : m.subs) _h.add(_s.id); for (Reply _f : full) if (_f != null && _f.id != 0 && _h.add(_f.id)) m.subs.add(_f); }
                roots++;
                grpcTotal += full.size();
            }
            Map<Long, String> subMap = fetchSubLocations(oid, type, m.id);
            for (Reply s : m.subs) {
                if (fill(s, subMap)) { n++; extra++; }
                else if (fill(s, webMap)) { n++; extra++; }
            }
            sleep(SUB_THROTTLE_MS);
        }
        log("apply grpcRoots=" + roots + " grpcSubs=" + grpcTotal
                + " webExtraFilled=" + extra + " totalFilled=" + n);
        return n;
    }

    private static boolean fill(Reply r, Map<Long, String> map) {
        if (r == null || map == null) return false;
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
        while (true) {
            if (t.startsWith("IP属地：")) t = t.substring(5).trim();
            else if (t.startsWith("IP属地:")) t = t.substring(5).trim();
            else break;
        }
        return t;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
