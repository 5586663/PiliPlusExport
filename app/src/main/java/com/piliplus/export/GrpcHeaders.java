package com.piliplus.export;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** PiliPlus gRPC headers replica (lib/utils/accounts/grpc_headers.dart). */
public final class GrpcHeaders {
    private GrpcHeaders() {}

    static final String MOBI_APP = "android_hd";
    static final String DEVICE = "android";
    static final String CHANNEL = "master";
    static final long BUILD = 2001100L;
    static final String VERSION_NAME = "2.0.1";
    static final String OSVER = "15";
    static final String UA = "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2";
    static final String TRACE = "11111111111111111111111111111111:1111111111111111:0:0";

    /** 真实 buvid，由 Hook 从 PiliPlus 注入；缺失时回退。 */
    public static volatile String buvid = "XY00000000000000000000000000000000000";

    private static byte[] b(String s) { return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8); }

    private static byte[] deviceBin() {
        return Proto.cat(
                Proto.fv(1, 5), Proto.fv(2, BUILD),
                Proto.fb(3, b(buvid)), Proto.fb(4, b(MOBI_APP)),
                Proto.fb(5, b(DEVICE)), Proto.fb(6, b(DEVICE)),
                Proto.fb(7, b(CHANNEL)), Proto.fb(8, b(DEVICE)),
                Proto.fb(9, b(DEVICE)), Proto.fb(10, b(OSVER)),
                Proto.fb(13, b(VERSION_NAME)));
    }

    private static byte[] metadataBin(String accessKey) {
        return Proto.cat(
                accessKey == null ? null : Proto.fb(1, b(accessKey)),
                Proto.fb(2, b(MOBI_APP)), Proto.fb(3, b(DEVICE)),
                Proto.fv(4, BUILD), Proto.fb(5, b(CHANNEL)),
                Proto.fb(6, b(buvid)), Proto.fb(7, b(DEVICE)));
    }

    private static byte[] fawkesBin() {
        return Proto.cat(Proto.fb(1, b(MOBI_APP)), Proto.fb(2, b("prod")), Proto.fb(3, b(rand(8))));
    }

    private static byte[] networkBin() { return Proto.fv(1, 1); }

    private static byte[] localeIds() {
        return Proto.cat(Proto.fb(1, b("zh")), Proto.fb(2, b("Hans")), Proto.fb(3, b("CN")));
    }

    private static byte[] localeBin() {
        return Proto.cat(Proto.fb(1, localeIds()), Proto.fb(2, localeIds()), Proto.fb(4, b("Asia/Shanghai")));
    }

    private static String rand(int n) {
        String h = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < n; i++) sb.append(h.charAt(r.nextInt(16)));
        return sb.toString();
    }

    private static String b64(byte[] d) {
        return android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP);
    }

    static void apply(HttpURLConnection c, String accessKey) {
        c.setRequestProperty("grpc-encoding", "gzip");
        c.setRequestProperty("gzip-accept-encoding", "gzip,identity");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("bili-http-engine", "cronet");
        c.setRequestProperty("x-bili-aurora-zone", "");
        c.setRequestProperty("x-bili-trace-id", TRACE);
        c.setRequestProperty("buvid", buvid);
        c.setRequestProperty("x-bili-gaia-vtoken", "");
        c.setRequestProperty("x-bili-device-bin", b64(deviceBin()));
        c.setRequestProperty("x-bili-network-bin", b64(networkBin()));
        c.setRequestProperty("x-bili-locale-bin", b64(localeBin()));
        c.setRequestProperty("x-bili-exps-bin", "");
        c.setRequestProperty("x-bili-fawkes-req-bin", b64(fawkesBin()));
        c.setRequestProperty("x-bili-metadata-bin", b64(metadataBin(accessKey)));
        if (accessKey != null && !accessKey.isEmpty()) {
            c.setRequestProperty("authorization", "identify_v1 " + accessKey);
        }
    }
}
