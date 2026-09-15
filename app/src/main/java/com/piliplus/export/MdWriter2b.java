package com.piliplus.export;

import java.util.List;

/** MdWriter2 的补充渲染（视频列表 / 动态列表 / 视频信息） */
final class MdWriter2b {

    private MdWriter2b() {}

    static String videoList(SpaceApi.UpInfo up, List<VideoItem> videos) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("# ").append(up.name).append(" · 视频总表\n\n");
        b.append("> 共 ").append(videos.size()).append(" 个\n");
        b.append("> 导出：").append(MdWriter2.now()).append("\n\n");
        b.append("| # | 标题 | BV号 | 时长 | 播放 | 点赞 | 评论 | 发布 |\n");
        b.append("|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < videos.size(); i++) {
            VideoItem v = videos.get(i);
            b.append("| ").append(i + 1)
             .append(" | ").append(MdWriter2.cell(v.title))
             .append(" | ").append(v.bvid)
             .append(" | ").append(v.length)
             .append(" | ").append(MdWriter2.num(v.play))
             .append(" | ").append(MdWriter2.num(v.like))
             .append(" | ").append(MdWriter2.num(v.reply))
             .append(" | ").append(MdWriter2.cell(v.pubTime))
             .append(" |\n");
        }
        b.append("\n*由 PiliPlus 导出模块生成*\n");
        return b.toString();
    }

    static String dynList(SpaceApi.UpInfo up, List<DynItem> dyns) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("# ").append(up.name).append(" · 动态总表\n\n");
        b.append("> 共 ").append(dyns.size()).append(" 条\n");
        b.append("> 导出：").append(MdWriter2.now()).append("\n\n");
        b.append("| # | 时间 | 类型 | 正文摘要 | 图 | 链接 |\n");
        b.append("|---|---|---|---|---|---|\n");
        for (int i = 0; i < dyns.size(); i++) {
            DynItem d = dyns.get(i);
            String sum = d.text == null ? "" : d.text.replace("\n", " ");
            if (sum.length() > 60) sum = sum.substring(0, 60) + "…";
            String t = d.pubTs > 0 ? MdWriter2.fmt(d.pubTs) : d.pubTimeText;
            b.append("| ").append(i + 1)
             .append(" | ").append(t)
             .append(" | ").append(MdWriter2.dynTypeName(d.typeStr, d.majorType))
             .append(" | ").append(MdWriter2.cell(sum))
             .append(" | ").append(d.images.size())
             .append(" | https://t.bilibili.com/").append(d.dynIdStr)
             .append(" |\n");
        }
        b.append("\n*由 PiliPlus 导出模块生成*\n");
        return b.toString();
    }

    static String videoInfo(SpaceApi.UpInfo up, VideoItem v) {
        StringBuilder b = new StringBuilder(1 << 12);
        b.append("# ").append(v.title).append("\n\n");
        b.append("> BV号：").append(v.bvid).append('\n');
        b.append("> 链接：https://www.bilibili.com/video/").append(v.bvid).append('\n');
        b.append("> UP主：").append(up.name).append("（UID：").append(up.mid).append("）\n");
        if (!v.pubTime.isEmpty()) b.append("> 发布：").append(v.pubTime).append('\n');
        if (!v.length.isEmpty()) b.append("> 时长：").append(v.length).append('\n');
        b.append("> 播放：").append(MdWriter2.num(v.play)).append('\n');
        b.append("> 点赞：").append(MdWriter2.num(v.like)).append('\n');
        b.append("> 评论：").append(MdWriter2.num(v.reply)).append('\n');
        b.append("> 导出：").append(MdWriter2.now()).append("\n\n");
        if (!v.cover.isEmpty()) b.append("![封面](").append(v.cover).append(")\n");
        return b.toString();
    }
}
