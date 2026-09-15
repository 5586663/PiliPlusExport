package com.piliplus.export;

import java.util.ArrayList;
import java.util.List;

/** UP主动态项（来自 web 接口 /x/polymer/web-dynamic/v1/feed/space） */
public class DynItem {
    public String dynIdStr = "";      // 动态 id 字符串
    public String typeStr = "";       // DYNAMIC_TYPE_*
    public String majorType = "";     // MAJOR_TYPE_*

    public long oid;                  // 评论 oid（basic.comment_id_str）
    public String commentOidStr = "";
    public int commentType;           // 评论 type（动态为 17）

    public long pubTs;                // ★ 发布时间戳（秒），来自 module_author.pub_ts
    public String pubTimeText = "";   // 发布时间文本
    public String pubAction = "";     // 发布动作（投稿了视频/发布了动态…）
    public String authorName = "";

    public String text = "";          // 正文
    public String title = "";         // 视频/opus 标题
    public String cover = "";         // 封面
    public String bvid = "";          // 视频 BV 号
    public List<String> images = new ArrayList<>();  // 图片 URL

    public long replyCount;
    public long likeCount;
}
