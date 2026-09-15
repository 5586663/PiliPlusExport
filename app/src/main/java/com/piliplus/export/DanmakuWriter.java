package com.piliplus.export;

import java.io.File;
import java.util.List;

/**
 * 弹幕写出。
 *
 * 输出两份（放在视频文件夹内）：
 *   视频.xml —— 与 视频.mp4 同名，弹弹play / mpv / PotPlayer 打开视频时自动挂载
 *   弹幕.md  —— 人读用表格
 *
 * XML 每行 p 属性顺序（B站标准）：
 *   出现时间秒, 模式, 字号, 颜色十进制, 发送时间戳, 池, 用户hash, 行ID
 */
public final class DanmakuWriter {

    private DanmakuWriter() {}

    public static final String XML_NAME = "视频.xml";
    public static final String MD_NAME = "弹幕.md";

    public static String toXml(List<DanmakuApi.Item> list) {
        StringBuilder b = new StringBuilder(list.size() * 96 + 160);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n");
        for (DanmakuApi.Item it : list) {
            b.append("  <d p=\"")
             .append(sec(it.progressMs)).append(',')
             .append(it.mode).append(',')
             .append(it.fontsize == 0 ? 25 : it.fontsize).append(',')
             .append(it.color).append(',')
             .append(it.ctime).append(',')
             .append(it.pool).append(',')
             .append(it.idStr).append(",0\">")
             .append(xmlEsc(it.content))
             .append("</d>\n");
        }
        b.append("</i>\n");
        return b.toString();
    }

    public static String toMd(String title, List<DanmakuApi.Item> list) {
        StringBuilder b = new StringBuilder(list.size() * 56 + 320);
        b.append("# ").append(title).append(" · 弹幕\n\n");
        b.append("> 共 ").append(list.size()).append(" 条\n");
        b.append("> 导出：").append(MdWriter2.now()).append("\n\n");
        b.append("| 时间 | 模式 | 内容 |\n|---|---|---|\n");
        for (DanmakuApi.Item it : list) {
            b.append("| ").append(mmss(it.progressMs)).append(" | ")
             .append(modeName(it.mode)).append(" | ")
             .append(mdEsc(it.content)).append(" |\n");
        }
        return b.toString();
    }

    /** 写出到视频文件夹；空列表不写文件。返回写出条数。 */
    public static int write(File videoDir, String title, List<DanmakuApi.Item> list) {
        if (list == null || list.isEmpty()) return 0;
        if (!videoDir.exists()) videoDir.mkdirs();
        CommentSaver.writeFile(new File(videoDir, XML_NAME), toXml(list));
        CommentSaver.writeFile(new File(videoDir, MD_NAME), toMd(title, list));
        return list.size();
    }

    private static String sec(long ms) {
        String s = String.format(java.util.Locale.US, "%.3f", ms / 1000.0);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static String modeName(int m) {
        switch (m) {
            case 1: case 2: case 3: return "滚动";
            case 4: return "底部";
            case 5: return "顶部";
            case 6: return "逆向";
            case 7: return "高级";
            case 8: return "代码";
            default: return String.valueOf(m);
        }
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String mdEsc(String s) {
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }
}
