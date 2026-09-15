package com.piliplus.export;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Markdown 渲染 —— 每种排版独立 */
public final class MdWriter {

    public enum Style { HEADING, TABLE, CHAT, PLAIN }

    private MdWriter() {}

    public static String render(BiliApi.Video v, List<Reply> mains, Style style, long subTotal) {
        StringBuilder b = new StringBuilder(1 << 20);

        b.append("# ").append(v.title).append("\n\n");
        b.append("> 视频链接：https://www.bilibili.com/video/").append(v.bvid).append('\n');
        b.append("> 导出时间：").append(now()).append('\n');
        b.append("> 主评论 ").append(mains.size()).append(" 条，楼中楼 ").append(subTotal).append(" 条，合计 ").append(mains.size() + subTotal).append(" 条\n");
        if (v.replyCount > 0) b.append("> 接口 stat.reply = ").append(v.replyCount).append("（含已删除/审核中）\n");
        b.append('\n');

        if (style == Style.TABLE) {
            b.append("| 楼层 | 昵称 | UID | 点赞 | 时间 | 内容 |\n");
            b.append("|------|------|-----|------|------|------|\n");
        } else {
            b.append("---\n\n");
        }

        int idx = 0;
        for (Reply m : mains) {
            idx++;
            switch (style) {
                case TABLE:  table(b, m, idx); break;
                case CHAT:   chat(b, m);      break;
                case PLAIN:  plain(b, m);     break;
                default:     heading(b, m, idx);
            }
        }
        if (style == Style.TABLE) b.append('\n');
        b.append("*由 PiliPlus 评论导出模块生成*\n");
        return b.toString();
    }

    // ---- 四级独立排版 ----
    private static void heading(StringBuilder b, Reply m, int idx) {
        b.append("### ").append(idx).append(". ").append(name(m)).append("\n\n");
        b.append("> UID：").append(m.mid).append("　点赞：").append(m.like)
         .append("　时间：").append(fmt(m.ctime));
        if (m.count > 0) b.append("　楼中楼：").append(m.subs.size()).append('/').append(m.count);
        b.append("\n\n");
        b.append(body(m)).append("\n\n");

        for (int k = 0; k < m.subs.size(); k++) {
            Reply s = m.subs.get(k);
            b.append("#### ↳ ").append(k + 1).append(". ").append(name(s))
             .append("（UID：").append(s.mid).append("）\n\n");
            StringBuilder meta = new StringBuilder();
            if (s.like > 0) meta.append("点赞 ").append(s.like);
            if (s.ctime > 0) { if (meta.length() > 0) meta.append(" · "); meta.append(fmt(s.ctime)); }
            if (meta.length() > 0) b.append("> ").append(meta).append("\n\n");
            b.append(body(s)).append("\n\n");
        }
        b.append("---\n\n");
    }

    private static void table(StringBuilder b, Reply m, int idx) {
        b.append("| ").append(idx).append(" | ").append(name(m)).append(" | ").append(m.mid)
         .append(" | ").append(m.like).append(" | ").append(fmt(m.ctime))
         .append(" | ").append(cell(body(m))).append(" |\n");
        for (Reply s : m.subs) {
            b.append("| ↳ | ").append(name(s)).append(" | ").append(s.mid)
             .append(" | ").append(s.like).append(" | ").append(fmt(s.ctime))
             .append(" | ").append(cell(body(s))).append(" |\n");
        }
    }

    private static void chat(StringBuilder b, Reply m) {
        b.append("**").append(name(m)).append("**(").append(m.mid).append(")：").append(body(m)).append("\n\n");
        for (Reply s : m.subs)
            b.append("> **").append(name(s)).append("**(").append(s.mid).append(")：").append(body(s)).append('\n');
        if (!m.subs.isEmpty()) b.append('\n');
    }

    private static void plain(StringBuilder b, Reply m) {
        b.append(name(m)).append("：").append(body(m)).append("\n\n");
        for (Reply s : m.subs)
            b.append("  - ").append(name(s)).append("：").append(body(s)).append('\n');
        if (!m.subs.isEmpty()) b.append('\n');
    }

    // ---- 工具 ----
    private static String name(Reply r) { return r.name == null || r.name.trim().isEmpty() ? "匿名用户" : r.name.trim(); }
    private static String body(Reply r) {
        String s = r.msg == null ? "" : r.msg.replace("\r", "").trim();
        return s.isEmpty() ? "_（空）_" : s;
    }
    private static String cell(String s) { return s.replace("|", "\\|").replace("\n", "<br>"); }

    private static String fmt(long sec) {
        if (sec <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(sec * 1000L));
    }
    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    public static String safeName(String s) {
        if (s == null) s = "视频";
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80);
        return s.isEmpty() ? "视频" : s;
    }
}
