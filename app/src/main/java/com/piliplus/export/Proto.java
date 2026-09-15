package com.piliplus.export;

import java.io.ByteArrayOutputStream;

/** protobuf 手工编解码（字段号已按 PiliPlus v1.pb.dart 核实） */
public final class Proto {
    private Proto() {}

    // ---------- 编码 ----------
    public static byte[] vi(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (v > 127) { o.write((int)(v & 0x7f) | 0x80); v >>>= 7; }
        o.write((int)(v & 0x7f));
        return o.toByteArray();
    }
    public static byte[] key(int f, int w) { return vi(((long) f << 3) | w); }
    public static byte[] fv(int f, long v) { return cat(key(f, 0), vi(v)); }
    public static byte[] fb(int f, byte[] b) { return cat(key(f, 2), vi(b.length), b); }

    public static byte[] cat(byte[]... arrs) {
        int n = 0;
        for (byte[] a : arrs) if (a != null) n += a.length;
        byte[] r = new byte[n];
        int p = 0;
        for (byte[] a : arrs) if (a != null) { System.arraycopy(a, 0, r, p, a.length); p += a.length; }
        return r;
    }

    // ---------- 游标读取 ----------
    public static final class R {
        final byte[] b;
        int i = 0;
        public R(byte[] b) { this.b = b; }
        public boolean done() { return i >= b.length; }
        public long rv() {
            long v = 0; int s = 0;
            while (i < b.length) {
                int x = b[i++] & 0xff;
                v |= (long)(x & 0x7f) << s;
                if ((x & 0x80) == 0) break;
                s += 7;
            }
            return v;
        }
        public byte[] rb() {
            int len = (int) rv();
            if (len < 0 || i + len > b.length) len = Math.max(0, b.length - i);
            byte[] r = new byte[len];
            System.arraycopy(b, i, r, 0, len);
            i += len;
            return r;
        }
    }

    /** 一个字段 */
    public static final class F {
        public final int field, wire;
        public final long v;
        public final byte[] sub;
        F(int f, int w, long v, byte[] s) { this.field = f; this.wire = w; this.v = v; this.sub = s; }
    }

    public static java.util.List<F> fields(byte[] buf) {
        java.util.List<F> out = new java.util.ArrayList<>();
        R r = new R(buf);
        while (!r.done()) {
            long tag = r.rv();
            int f = (int)(tag >>> 3), w = (int)(tag & 7);
            if (w == 0) out.add(new F(f, w, r.rv(), null));
            else if (w == 2) out.add(new F(f, w, 0, r.rb()));
            else if (w == 5) { byte[] x = new byte[4]; System.arraycopy(r.b, r.i, x, 0, 4); r.i += 4; out.add(new F(f, w, 0, x)); }
            else if (w == 1) { r.i += 8; out.add(new F(f, w, 0, null)); }
            else break;
        }
        return out;
    }

    public static long getV(byte[] buf, int field) {
        for (F f : fields(buf)) if (f.field == field && f.wire == 0) return f.v;
        return 0;
    }
    public static byte[] getB(byte[] buf, int field) {
        for (F f : fields(buf)) if (f.field == field && f.wire == 2) return f.sub;
        return null;
    }
    public static String getS(byte[] buf, int field) {
        byte[] b = getB(buf, field);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
    public static java.util.List<byte[]> getAllB(byte[] buf, int field) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (F f : fields(buf)) if (f.field == field && f.wire == 2) out.add(f.sub);
        return out;
    }
}
