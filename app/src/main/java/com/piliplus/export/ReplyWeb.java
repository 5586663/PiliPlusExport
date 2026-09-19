package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无登录评论拉取（复用 PiliPlus lib/http/reply.dart 未登录分支）。
 * GET https://api.bilibili.com/x/v2/reply/main
 * 空 cookie + NoAccount -> reply_control.location 即 IP 属地。
 *
 * 请求头严格对齐 PiliPlus NoAccount：AccountManager 拦截器对 NoAccount
 * 直接放行(handler.next)，最终头 = baseHeaders(env/app-key/x-bili-aurora-zone)
 * + cookie:''，不带任何账号信息。
 */
public final class ReplyWeb {

    private ReplyWeb() {}

    public static final String MAIN = "https://api.bilibili.com/x/v2/reply/main";
    static final String UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class Page {
        public List<Reply> replies = new ArrayList<>();
        public String nextOffset;
        public boolean isEnd;
    }

    public static Page mainList(long oid, int type, String offset, int sort) throws Exception {
        String off = (offset == null) ? "" : offset.replace("\"", "\\\"");
        String pag = "{\"offset\":\"" + off + "\"}";
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("oid", String.valueOf(oid));
        p.put("type", String.valueOf(type));
        p.put("mode", String.valueOf(sort + 2));
        p.put("pagination_str", pag);
        StringBuilder q = new StringBuilder("?");
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 1) q.append('&');
            q.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        String body = get(MAIN + q);
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        if (root == null) throw new Exception("评论响应解析失败");
        long code = Json2.lng(root, "code");
        if (code != 0) throw new Exception("评论 code=" + code + " msg=" + Json2.str(root, "message"));
        Page page = new Page();
        Map<String, Object> data = Json2.obj(root.get("data"));
        if (data == null) return page;
        List<Object> replies = Json2.arr(data.get("replies"));
        if (replies != null) {
            for (Object o : replies) {
                Map<String, Object> m = Json2.obj(o);
                if (m == null) continue;
                Reply r = parse(m);
                if (r != null && r.id != 0) page.replies.add(r);
            }
        }
        Map<String, Object> cursor = Json2.obj(data.get("cursor"));
        if (cursor != null) {
            page.isEnd = Json2.lng(cursor, "is_end") != 0;
            String nn = Json2.str(cursor, "next");
            if (nn != null && !nn.isEmpty()) page.nextOffset = nn;
        }
        return page;
    }

    /** PiliPlus NoAccount 请求：仅 baseHeaders + 空 cookie，不带任何账号信息。 */
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("env", "prod");
        c.setRequestProperty("app-key", "android64");
        c.setRequestProperty("x-bili-aurora-zone", "sh001");
        c.setRequestProperty("cookie", "");
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
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

    private static Reply parse(Map<String, Object> m) {
        Reply r = new Reply();
        r.id = Json2.lng(m, "rpid");
        r.oid = Json2.lng(m, "oid");
        r.mid = Json2.lng(m, "mid");
        r.ctime = Json2.lng(m, "ctime");
        r.like = Json2.lng(m, "like");
        r.count = Json2.lng(m, "rcount");
        Map<String, Object> content = Json2.obj(m.get("content"));
        if (content != null) {
            String msg = Json2.str(content, "message");
            if (msg != null) r.msg = msg;
        }
        Map<String, Object> member = Json2.obj(m.get("member"));
        if (member != null) {
            String uname = Json2.str(member, "uname");
            if (uname != null) r.name = uname;
        }
        Map<String, Object> ctrl = Json2.obj(m.get("reply_control"));
        if (ctrl != null) {
            String loc = Json2.str(ctrl, "location");
            if (loc != null) {
                String t = loc.trim();
                if (t.startsWith("IP属地:")) t = t.substring(5).trim();
                if (!t.isEmpty()) r.location = t;
            }
        }
        List<Object> subs = Json2.arr(m.get("replies"));
        if (subs != null) {
            for (Object so : subs) {
                Map<String, Object> sm = Json2.obj(so);
                if (sm == null) continue;
                Reply s = parse(sm);
                if (s != null && s.id != 0) r.subs.add(s);
            }
        }
        return r;
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
