package com.piliplus.export;

/** 文件名与目录名清洗 */
public final class NameUtil {

    private NameUtil() {}

    /** 通用安全名：去非法字符、压缩空白、截断 80 字 */
    public static String safe(String s) {
        if (s == null) s = "未命名";
        s = s.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80);
        return s.isEmpty() ? "未命名" : s;
    }

    /** 目录名前缀序号，保证排序稳定且不重名 */
    public static String indexed(int idx, String title) {
        return String.format(java.util.Locale.CHINA, "%03d_%s", idx, safe(title));
    }

    /** 从 URL 猜图片扩展名，默认 .jpg */
    public static String imgExt(String url) {
        if (url == null) return ".jpg";
        int q = url.indexOf('?');
        String p = q >= 0 ? url.substring(0, q) : url;
        int dot = p.lastIndexOf('.');
        if (dot < 0) return ".jpg";
        String e = p.substring(dot).toLowerCase(java.util.Locale.ROOT);
        if (e.length() > 5) return ".jpg";
        if (e.equals(".png") || e.equals(".jpg") || e.equals(".jpeg")
                || e.equals(".gif") || e.equals(".webp") || e.equals(".bmp")) return e;
        return ".jpg";
    }
}
