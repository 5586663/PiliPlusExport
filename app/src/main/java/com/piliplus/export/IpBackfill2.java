package com.piliplus.export;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网页接口楼中楼补全 + IP 回填。
 * gRPC DetailList 会漏楼中楼，网页 /x/v2/reply/wbi/reply 更全。
 */
public final class IpBackfill2 {

    private IpBackfill2() {}

    private static final String TAG = "PiliExportDiag";
    private static final String SUB = "https://api.bilibili.com/x/v2/reply/wbi/reply";
    private static final long THROTTLE_MS = 80;
    private static final int SUB_MAX_PAGES = 50;

    private static void log(String s) {
        Log.i(TAG, s);
        try {
            java.io.File f = new java.io.File("/storage/emulated/0/Download/PiliPlus_导出/diag_ip.txt");
            java.io.File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(s + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    public static int apply(long oid, int type, List<Reply> mains) {
        if (mains == null || mains.isEmpty()) return 0;
        int base = IpBackfill.apply(oid, type, mains);
        int tried = 0, hit = 0, extraLoc = 0, added = 0;
        for (Reply m : mains) {
            if (m == null || m.subs == null) continue;
            boolean needFetch = (m.count > m.subs.size());
            if (!needFetch) {
                for (Reply s : m.subs) {
                    if (s != null && (s.location == null || s.location.trim().isEmpty())) { needFetch = true; break; }
                }
            }
            if (!needFetch) continue;
            tried++;
            List<Reply> webSubs = fetchSubReplies(oid, type, m.id);
            if (webSubs.isEmpty()) continue;
            hit++;
            Map<Long, String> locMap = new HashMap<>();
            for (Reply w : webSubs) {
                if (w.location != null && !w.location.isEmpty()) locMap.put(w.id, w.location);
            }
            for (Reply s : m.subs) {
                if (s.location == null || s.location.trim().isEmpty()) {
                    String v = locMap.get(s.id);
                    if (v != null && !v.isEmpty()) { s.location = v; extraLoc++; }
                }
            }
            Set<Long> have = new HashSet<>();
            for (Reply s : m.subs) have.add(s.id);
            for (Reply w : webSubs) {
                if (w.id != 0 && !have.contains(w.id)) { m.subs.add(w); have.add(w.id); added++; }
            }
        }
        log("IpBackfill2 triedRoots=" + tried + " hit=" + hit + " extraLoc=" + extraLoc + " subsAdded=" + added);
        return base + extraLoc + added;
    }

    static List<Reply> fetchSubReplies(long oid, int type, long root) {
        List<Reply> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int pages = 0;
        long lastCode = -999;
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
                String body = SpaceApi.getWithHeaders(url, SpaceApi.PC_UA, "https://www.bilibili.com/");
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
                    if (rpid == 0 || !seen.add(rpid)) continue;
                    Reply rep = new Reply();
                    rep.id = rpid;
                    rep.oid = Json2.lng(m, "oid");
                    rep.mid = Json2.lng(m, "mid");
                    rep.ctime = Json2.lng(m, "ctime");
                    rep.like = Json2.lng(m, "like");
                    Map<String, Object> content = Json2.obj(m.get("content"));
                    if (content != null) {
                        String msg = Json2.str(content, "message");
                        if (msg != null) rep.msg = msg;
                    }
                    Map<String, Object> member = Json2.obj(m.get("member"));
                    if (member != null) {
                        String uname = Json2.str(member, "uname");
                        if (uname != null) rep.name = uname;
                    }
                    Map<String, Object> ctrl = Json2.obj(m.get("reply_control"));
                    if (ctrl != null) {
                        String loc = Json2.str(ctrl, "location");
                        if (loc != null) {
                            String t = loc.trim();
                            if (t.startsWith("IP属地：")) t = t.substring(5).trim();
                            else if (t.startsWith("IP属地:")) t = t.substring(5).trim();
                            rep.location = t;
                        }
                    }
                    out.add(rep);
                }
                Map<String, Object> page = Json2.obj(data.get("page"));
                if (page != null) {
                    long count = Json2.lng(page, "count");
                    if (pn * 20 >= count) break;
                } else if (subs.size() < 20) {
                    break;
                }
            } catch (Throwable t) {
                lastCode = -997;
                break;
            }
        }
        log("fetchSubReplies root=" + root + " pages=" + pages + " got=" + out.size() + " lastCode=" + lastCode);
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
