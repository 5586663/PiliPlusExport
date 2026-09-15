package com.piliplus.export;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 调试用 protobuf 结构转储。
 *
 * 隐私边界（严格）：
 *   1. 只输出【字段号 + wire 类型 + 字节长度】，不输出任何字段内容
 *   2. 不记录昵称、UID、评论正文、图片 URL、IP 值、cookie
 *   3. 只写本机文件，不发起任何网络请求，不上报
 *   4. 默认关闭，仅用户手动开启后生效；每次开启只 dump 一次即自动关闭
 *   5. 文件可随时手动删除
 *
 * 输出路径：/storage/emulated/0/Download/PiliPlus_导出/调试/debug_proto.txt
 */
public final class DebugDump {

    private DebugDump() {}

    /** 是否启用。默认 false。开启后 dump 一次即自动置回 false。 */
    public static volatile boolean ENABLED = false;

    private static final String OUT_DIR = "/storage/emulated/0/Download/PiliPlus_导出/调试";
    private static final String OUT_FILE = OUT_DIR + "/debug_proto.txt";

    /**
     * 转储一条 ReplyInfo 的结构。
     * 只在 ENABLED 为真时执行，执行后自动关闭。
     */
    public static synchronized void dumpReplies(List<byte[]> raws, String tag) {
        if (!ENABLED) return;
        ENABLED = false;   // 一次性

        StringBuilder b = new StringBuilder(8192);
        b.append("# 调试结构转储（仅字段号/长度，无内容）\n");
        b.append("# 说明：wire 0=varint 2=length-delimited 5=32bit 1=64bit\n\n");
        b.append("tag=").append(tag)
         .append("  replies=").append(raws == null ? 0 : raws.size())
         .append('\n');

        if (raws != null && !raws.isEmpty()) {
            b.append("\n=== 第 1 条 ReplyInfo ===\n");
            dumpMessage(b, raws.get(0), "", 0);
        }

        write(b.toString());
    }

    /** 递归打印字段结构，depth 限制嵌套层数 */
    private static void dumpMessage(StringBuilder b, byte[] buf, String indent, int depth) {
        if (buf == null || depth > 4) return;
        List<Proto.F> fs = Proto.fields(buf);
        for (Proto.F f : fs) {
            b.append(indent).append("field ").append(f.field)
             .append("  wire=").append(f.wire);
            if (f.wire == 2) {
                int len = f.sub == null ? 0 : f.sub.length;
                b.append("  len=").append(len);
                // 仅对 content(12)/member(13)/reply_control(14) 下钻，看有没有 25
                if (depth < 3 && (f.field == 14 || f.field == 12 || f.field == 13)) {
                    b.append("   {\n");
                    dumpMessage(b, f.sub, indent + "  ", depth + 1);
                    b.append(indent).append("  }");
                }
            } else if (f.wire == 0) {
                b.append("  varint>0=").append(f.v > 0);
            }
            b.append('\n');
        }
    }

    private static void write(String s) {
        try {
            File dir = new File(OUT_DIR);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(OUT_FILE);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            // 静默失败，不影响主流程
        }
    }

    /** 读取转储内容；不存在或无内容时返回 null */
    public static String read() {
        try {
            File f = new File(OUT_FILE);
            if (!f.exists() || f.length() <= 0) return null;
            int n = (int) Math.min(f.length(), 200000);
            byte[] buf = new byte[n];
            try (FileInputStream in = new FileInputStream(f)) {
                int got = in.read(buf);
                if (got <= 0) return null;
                return new String(buf, 0, got, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static String outPath() { return OUT_FILE; }
}
