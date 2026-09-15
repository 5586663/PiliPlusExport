package com.piliplus.export;

import java.io.File;

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

    /**
     * 返回一个不会覆盖已有文件的 File。
     * 若 base+ext 已存在，依次尝试 base(0)+ext、base(1)+ext …
     *
     * 对应 hlbmerge 的 FileUtil.getAvailableFilePath()。
     */
    public static File availableFile(File dir, String base, String ext) {
        if (dir != null && !dir.exists()) dir.mkdirs();
        File f = new File(dir, base + ext);
        int i = 0;
        while (f.exists()) {
            f = new File(dir, base + "(" + i + ")" + ext);
            i++;
            if (i > 9999) break;   // 防御性上限
        }
        return f;
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
