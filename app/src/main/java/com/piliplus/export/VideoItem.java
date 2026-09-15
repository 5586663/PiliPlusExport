package com.piliplus.export;

/** UP主视频列表项（来自 /x/v2/space/archive/cursor 的 item[]） */
public class VideoItem {
    public long aid;
    public String bvid = "";
    public String title = "";
    public String cover = "";
    public long duration;        // 秒
    public String length = "";   // 04:12
    public String pubTime = "";  // publish_time_text
    public long play;
    public long danmaku;
    public long reply;
    public long fav;
    public long coin;
    public long like;
}
