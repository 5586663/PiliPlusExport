package com.piliplus.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 单条评论 —— ReplyInfo 字段已核实: 1=replies 2=id 3=oid 4=type 5=mid 9=like 10=ctime 11=count 12=content 13=member 14=reply_control */
public class Reply {
    public long id;
    public long oid;
    public long mid;
    public long like;
    public long ctime;
    public long count;      // 接口报告的楼中楼总数
    public String name = "";
    public String msg = "";
    public String location = "";   // IP 属地 —— ReplyControl.location (字段 25)
    public java.util.List<byte[]> rawSubs = new java.util.ArrayList<>();
    public java.util.List<Reply> subs = new java.util.ArrayList<>();

    public static Reply parse(byte[] buf) {
        Reply r = new Reply();
        r.id = Proto.getV(buf, 2);
        r.oid = Proto.getV(buf, 3);
        r.mid = Proto.getV(buf, 5);
        r.like = Proto.getV(buf, 9);
        r.ctime = Proto.getV(buf, 10);
        r.count = Proto.getV(buf, 11);

        byte[] content = Proto.getB(buf, 12);
        if (content != null) {
            String m = Proto.getS(content, 1);
            if (m != null) r.msg = m;
        }
        byte[] member = Proto.getB(buf, 13);
        if (member != null) {
            String n = Proto.getS(member, 2);
            if (n != null) r.name = n;
        }
        byte[] control = Proto.getB(buf, 14);
        if (control != null) {
            String loc = Proto.getS(control, 25);
            if (loc != null) r.location = loc;
        }
        r.rawSubs = Proto.getAllB(buf, 1);
        for (byte[] s : r.rawSubs) r.subs.add(Reply.parse(s));
        return r;
    }

    public boolean valid() { return id != 0; }
}
