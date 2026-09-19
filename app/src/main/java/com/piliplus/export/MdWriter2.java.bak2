package com.piliplus.export;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** UP主导出专用 Markdown 渲染 */
public final class MdWriter2 {

    private MdWriter2() {}

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    static String fmt(long sec) {
        if (sec <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(sec * 1000L));
    }

    static String num(long n) { return String.format(Locale.CHINA, "%,d", n); }

    // ---- 转发到 MdWriter2b ----
    public static String videoList(SpaceApi.UpInfo up, List<VideoItem> videos) { return MdWriter2b.videoList(up, videos); }
    public static String dynList(SpaceApi.UpInfo up, List<DynItem> dyns) { return MdWriter2b.dynList(up, dyns); }
    public static String videoInfo(SpaceApi.UpInfo up, VideoItem v) { return MdWriter2b.videoInfo(up, v); }

    // ==================================================================
    /** 00_总览.md */
    public static String upOverview(SpaceApi.UpInfo up, List<VideoItem> videos, List<DynItem> dyns) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("# ").append(up.name).append(" · UP主导出总览\n\n");
        b.append("> UID：").append(up.mid).append('\n');
        if (up.sign != null && !up.sign.isEmpty()) b.append("> 签名：").append(up.sign).append('\n');
        if (up.fans > 0) b.append("> 粉丝：").append(num(up.fans)).append('\n');
        b.append("> 主页：https://space.bilibili.com/").append(up.mid).append('\n');
        b.append("> 导出时间：").append(now()).append('\n');
        b.append("> 投稿视频 ").append(videos.size()).append(" 个，动态 ").append(dyns.size()).append(" 条\n\n");

        long play = 0, like = 0, reply = 0;
        for (VideoItem v : videos) { play += v.play; like += v.like; reply += v.reply; }
        b.append("## 汇总\n\n");
        b.append("| 指标 | 数值 |\n|---|---|\n");
        b.append("| 视频数 | ").append(videos.size()).append(" |\n");
        b.append("| 总播放 | ").append(num(play)).append(" |\n");
        b.append("| 总点赞 | ").append(num(like)).append(" |\n");
        b.append("| 总评论 | ").append(num(reply)).append(" |\n");
        b.append("| 动态数 | ").append(dyns.size()).append(" |\n\n");

        if (!videos.isEmpty()) {
            b.append("## 投稿视频（").append(videos.size()).append("）\n\n");
            b.append("| # | 标题 | BV号 | 时长 | 播放 | 点赞 | 评论 | 发布 |\n");
            b.append("|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < videos.size(); i++) {
                VideoItem v = videos.get(i);
                b.append("| ").append(i + 1)
                 .append(" | ").append(cell(v.title))
                 .append(" | ").append(v.bvid)
                 .append(" | ").append(v.length)
                 .append(" | ").append(num(v.play))
                 .append(" | ").append(num(v.like))
                 .append(" | ").append(num(v.reply))
                 .append(" | ").append(cell(v.pubTime))
                 .append(" |\n");
            }
            b.append('\n');
        }

        if (!dyns.isEmpty()) {
            b.append("## 动态（").append(dyns.size()).append("）\n\n");
            b.append("| # | 时间 | 类型 | 正文摘要 | 图 | 链接 |\n");
            b.append("|---|---|---|---|---|---|\n");
            for (int i = 0; i < dyns.size(); i++) {
                DynItem d = dyns.get(i);
                String sum = d.text == null ? "" : d.text.replace("\n", " ");
                if (sum.length() > 50) sum = sum.substring(0, 50) + "…";
                String t = d.pubTs > 0 ? fmt(d.pubTs) : (d.pubTimeText.isEmpty() ? "" : d.pubTimeText);
                b.append("| ").append(i + 1)
                 .append(" | ").append(t)
                 .append(" | ").append(dynTypeName(d.typeStr, d.majorType))
                 .append(" | ").append(cell(sum))
                 .append(" | ").append(d.images.size())
                 .append(" | https://t.bilibili.com/").append(d.dynIdStr)
                 .append(" |\n");
            }
            b.append('\n');
        }
        b.append("*由 PiliPlus 导出模块生成*\n");
        return b.toString();
    }

    // ==================================================================
    /** 动态总表（兼容旧调用） */
    public static String dyns(SpaceApi.UpInfo up, List<DynItem> list) {
        StringBuilder b = new StringBuilder(1 << 18);
        b.append("# ").append(up.name).append(" · 动态全量\n\n");
        b.append("> UID：").append(up.mid).append('\n');
        b.append("> 导出时间：").append(now()).append('\n');
        b.append("> 共 ").append(list.size()).append(" 条\n\n---\n\n");
        for (int i = 0; i < list.size(); i++) b.append(dynDetail(list.get(i)));
        b.append("*由 PiliPlus 导出模块生成*\n");
        return b.toString();
    }

    /** 单条动态正文（动态文件夹内的 动态.md） */
    public static String dynDetail(DynItem d) {
        StringBuilder b = new StringBuilder(1 << 14);
        b.append("# ").append(dynTitleOf(d)).append("\n\n");
        String t = d.pubTs > 0 ? fmt(d.pubTs) : (d.pubTimeText.isEmpty() ? "时间未知" : d.pubTimeText);
        b.append("> 类型：").append(dynTypeName(d.typeStr, d.majorType)).append('\n');
        b.append("> 时间：").append(t);
        if (!d.pubAction.isEmpty()) b.append("　").append(d.pubAction);
        b.append('\n');
        if (!d.dynIdStr.isEmpty()) b.append("> 链接：https://t.bilibili.com/").append(d.dynIdStr).append('\n');
        if (d.oid != 0) b.append("> 评论 oid：").append(d.oid).append("（type ").append(d.commentType).append("）\n");
        if (d.likeCount > 0 || d.replyCount > 0) {
            b.append("> 点赞 ").append(num(d.likeCount))
             .append("　评论 ").append(num(d.replyCount)).append('\n');
        }
        b.append('\n');
        if (!d.title.isEmpty()) b.append("**").append(d.title).append("**\n\n");
        if (!d.text.trim().isEmpty()) b.append(d.text.trim()).append("\n\n");
        if (!d.cover.isEmpty()) b.append("![封面](").append(d.cover).append(")\n\n");
        for (String img : d.images) {
            if (img != null && !img.isEmpty()) b.append("![](").append(img).append(")\n\n");
        }
        if (!d.bvid.isEmpty()) {
            b.append("视频：https://www.bilibili.com/video/").append(d.bvid).append("\n\n");
        }
        return b.toString();
    }

    static String dynTitleOf(DynItem d) {
        if (d.title != null && !d.title.trim().isEmpty()) return d.title.trim();
        String t = d.text == null ? "" : d.text.replace("\n", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40) + "…";
        if (!t.isEmpty()) return t;
        if (!d.dynIdStr.isEmpty()) return d.dynIdStr;
        return "动态";
    }

    // ==================================================================
    /** 评论区 markdown（视频评论 / 动态评论共用） */
    public static String comments(String header, List<Reply> mains) {
        StringBuilder b = new StringBuilder(1 << 20);
        if (header != null && !header.isEmpty()) {
            b.append(header);
            if (!header.endsWith("\n")) b.append('\n');
            b.append('\n');
        }
        b.append("---\n\n");

        int idx = 0;
        for (Reply m : mains) {
            idx++;
            b.append("### ").append(idx).append(". ").append(name(m)).append("\n\n");
            b.append("> UID：").append(m.mid).append("　点赞：").append(m.like)
             .append("　时间：").append(fmt(m.ctime));
            if (!ip(m).isEmpty()) b.append("　IP属地：").append(ip(m));
            if (m.count > 0) b.append("　楼中楼：").append(m.subs.size()).append('/').append(m.count);
            b.append("\n\n").append(body(m)).append("\n\n");

            for (int j = 0; j < m.pics.size(); j++) {
                String fn = (j == 0) ? ("评论" + idx) : ("评论" + idx + "-" + (j + 1));
                b.append("![评论图](评论图片/").append(fn).append(NameUtil.imgExt(m.pics.get(j))).append(")\n\n");
            }

            for (int k = 0; k < m.subs.size(); k++) {
                Reply s = m.subs.get(k);
                b.append("#### ↳ ").append(k + 1).append(". ").append(name(s))
                 .append("（UID：").append(s.mid).append("）\n\n");
                StringBuilder meta = new StringBuilder();
                if (s.like > 0) meta.append("点赞 ").append(s.like);
                if (s.ctime > 0) { if (meta.length() > 0) meta.append(" · "); meta.append(fmt(s.ctime)); }
                if (!ip(s).isEmpty()) { if (meta.length() > 0) meta.append(" · "); meta.append("IP属地 ").append(ip(s)); }
                if (meta.length() > 0) b.append("> ").append(meta).append("\n\n");
                b.append(body(s)).append("\n\n");

                for (int j = 0; j < s.pics.size(); j++) {
                    String fn = "评论" + idx + "_" + (k + 1) + (j == 0 ? "" : ("-" + (j + 1)));
                    b.append("![评论图](评论图片/").append(fn).append(NameUtil.imgExt(s.pics.get(j))).append(")\n\n");
                }
            }
            b.append("---\n\n");
        }
        b.append("*由 PiliPlus 导出模块生成*\n");
        return b.toString();
    }

    // ==================================================================
    static String dynTypeName(String typeStr, String majorType) {
        String maj = majorType == null ? "" : majorType;
        if (maj.contains("ARCHIVE")) return "视频投稿";
        if (maj.contains("UGC_SEASON")) return "合集";
        if (maj.contains("PGC")) return "番剧";
        if (maj.contains("COURSES")) return "课程";
        if (maj.contains("OPUS")) return "图文";
        if (maj.contains("MUSIC")) return "音频";
        if (maj.contains("LIVE_RCMD") || maj.contains("LIVE")) return "直播";
        if (maj.contains("MEDIALIST")) return "收藏夹";

        String t = typeStr == null ? "" : typeStr;
        if (t.contains("FORWARD")) return "转发";
        if (t.contains("DRAW")) return "图文";
        if (t.contains("WORD")) return "文字";
        if (t.contains("AV")) return "视频";
        if (t.contains("ARTICLE")) return "专栏";
        return t.isEmpty() ? "动态" : t.replace("DYNAMIC_TYPE_", "");
    }

    private static String name(Reply r) {
        return r.name == null || r.name.trim().isEmpty() ? "匿名用户" : r.name.trim();
    }

    private static String body(Reply r) {
        String s = r.msg == null ? "" : r.msg.replace("\r", "").trim();
        return s.isEmpty() ? "_（空）_" : s;
    }

    private static String ip(Reply r) {
        return r.location == null ? "" : r.location.trim();
    }

    static String cell(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", "<br>");
    }
}
