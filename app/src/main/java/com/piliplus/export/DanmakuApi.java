package com.piliplus.export;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频弹幕接口。
 *
 * 接口路径（PiliPlus lib/grpc/url.dart 已核实）：
 *   /bilibili.community.service.dm.v1.DM/DmSegMobile
 *
 * 请求 DmSegMobileReq 字段号（v1.pb.dart 已核实）：
 *   2=oid(=cid) 3=type(=1) 4=segmentIndex 8=pullMode 9=fromScene
 *
 * 响应 DmSegMobileReply 字段号：
 *   1=elems (repeated DanmakuElem)
 *
 * DanmakuElem 字段号（v1.pb.dart 已核实）：
 *   1=id 2=progress(毫秒) 3=mode 4=fontsize 5=color(OU3) 6=midHash
 *   7=content 8=ctime 9=weight 10=action 11=pool 12=idStr 13=attr 25=type
 *
 * 分段：B站每段固定 6 分钟(360s)，segmentIndex 从 1 开始。
 */
public final class DanmakuApi {

    private DanmakuApi() {}

    public static final String DM_SEG = "/bilibili.community.service.dm.v1.DM/DmSegMobile";

    public static class Item {
        public long id;
        public long progressMs;
        public int mode;
        public int fontsize;
        public long color;
        public long ctime;
        public int weight;
        public int pool;
        public String content = "";
        public String idStr = "";
    }

    /** 按视频时长拉取全部分段。durationSec<=0 时只拉第 1 段。 */
    public static List<Item> fetchAll(long cid, long durationSec, Progress cb) throws Exception {
        List<Item> all = new ArrayList<>();
        int segs = durationSec > 0 ? (int) ((durationSec + 359) / 360) : 1;
        if (segs <= 0) segs = 1;
        if (segs > 500) segs = 500;   // 50 小时上限，防失控
        int emptyStreak = 0;
        for (int i = 1; i <= segs; i++) {
            List<Item> one = seg(cid, i);
            all.addAll(one);
            if (cb != null) cb.on(i, segs, one.size());
            emptyStreak = one.isEmpty() ? emptyStreak + 1 : 0;
            // 连续 3 段空视为该视频无弹幕/已到底，提前停
            if (emptyStreak >= 3) break;
            if (i < segs) sleep(80);
        }
        return all;
    }

    /** 拉取单个分段 */
    public static List<Item> seg(long cid, int segmentIndex) throws Exception {
        byte[] req = Proto.cat(
                Proto.fv(2, cid),
                Proto.fv(3, 1),
                Proto.fv(4, segmentIndex));
        byte[] resp = BiliApi.grpcRaw(DM_SEG, req);
        List<Item> out = new ArrayList<>();
        for (byte[] eb : Proto.getAllB(resp, 1)) {
            Item it = new Item();
            it.id = Proto.getV(eb, 1);
            it.progressMs = Proto.getV(eb, 2);
            it.mode = (int) Proto.getV(eb, 3);
            it.fontsize = (int) Proto.getV(eb, 4);
            it.color = Proto.getV(eb, 5);
            it.ctime = Proto.getV(eb, 8);
            it.weight = (int) Proto.getV(eb, 9);
            it.pool = (int) Proto.getV(eb, 11);
            String c = Proto.getS(eb, 7);
            if (c != null) it.content = c;
            String ids = Proto.getS(eb, 12);
            if (ids != null) it.idStr = ids;
            if (!it.content.isEmpty()) out.add(it);
        }
        return out;
    }

    public interface Progress { void on(int seg, int totalSegs, int got); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
