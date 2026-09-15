package com.piliplus.export;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WBI 签名（B站 web 接口风控）。
 *
 * 依据：PiliPlus lib/utils/wbi_sign.dart
 *   - mixinKeyEncTab 是固定的 32 位重排表
 *   - 从 nav 接口取 wbi_img.img_url / sub_url，各取文件名拼接后重排
 *   - 参数按 key 排序 → 过滤 !'()* 字符 → 拼接 query → md5(query + mixinKey)
 *
 * 动态列表必须走 web 接口（/x/polymer/web-dynamic/v1/feed/space），
 * 因为 gRPC 的 OpusSpaceFlow 响应中不含发布时间字段（已逐字段核实）。
 */
public final class WbiSign {

    private WbiSign() {}

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    };

    private static String cachedMixinKey;
    private static long cachedDay = -1;

    /** 取 mixinKey（按天缓存，与 PiliPlus 一致） */
    public static synchronized String mixinKey() throws Exception {
        long today = System.currentTimeMillis() / 86400000L;
        if (cachedMixinKey != null && cachedDay == today) return cachedMixinKey;

        String body = SpaceApi.get("https://api.bilibili.com/x/web-interface/nav");
        Map<String, Object> root = Json2.obj(Json2.parse(body));
        Map<String, Object> data = root == null ? null : Json2.obj(root.get("data"));
        Map<String, Object> wbi = data == null ? null : Json2.obj(data.get("wbi_img"));
        if (wbi == null) throw new Exception("nav 响应无 wbi_img");

        String imgUrl = Json2.str(wbi, "img_url");
        String subUrl = Json2.str(wbi, "sub_url");
        if (imgUrl == null || subUrl == null) throw new Exception("wbi_img 字段缺失");

        String orig = fileName(imgUrl) + fileName(subUrl);
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < orig.length()) sb.append(orig.charAt(idx));
        }
        cachedMixinKey = sb.toString();
        cachedDay = today;
        return cachedMixinKey;
    }

    private static String fileName(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    /**
     * 对参数签名：原地写入 wts 与 w_rid。
     * 调用前请确保 map 中值均为 String。
     */
    public static void sign(Map<String, String> params) throws Exception {
        String mixin = mixinKey();
        params.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        StringBuilder q = new StringBuilder();
        for (String k : keys) {
            if (q.length() > 0) q.append('&');
            q.append(enc(k)).append('=').append(enc(filter(params.get(k))));
        }
        params.put("w_rid", md5(q.toString() + mixin));
    }

    /** 过滤 !'()* 五个字符（PiliPlus 用正则 [!\'()*] 剔除） */
    private static String filter(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            b.append(c);
        }
        return b.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder(32);
        for (byte x : d) {
            String h = Integer.toHexString(x & 0xff);
            if (h.length() == 1) b.append('0');
            b.append(h);
        }
        return b.toString();
    }
}
