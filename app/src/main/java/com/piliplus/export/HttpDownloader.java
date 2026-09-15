package com.piliplus.export;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 带 B站 cookie/Referer 的流式下载器。
 * 支持 Range 断点续传：目标文件已存在且长度>0 时从断点继续。
 */
public final class HttpDownloader {

    private HttpDownloader() {}

    public interface Progress {
        /** @return false 表示用户取消 */
        boolean on(long got, long total);
    }

    /**
     * 下载 URL 到 file。
     * @return 实际写入后的文件总长度
     */
    public static long download(String url, File file, Progress cb) throws Exception {
        long existing = file.exists() ? file.length() : 0;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", SpaceApi.PC_UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        c.setRequestProperty("Origin", "https://www.bilibili.com");
        c.setRequestProperty("Accept", "*/*");
        String ck = BiliApi.COOKIE;
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("cookie", ck);
        if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

        int code = c.getResponseCode();
        if (code == 416) return existing;                 // 已下完
        if (code >= 400) throw new Exception("HTTP " + code + " @ " + host(url));

        boolean append = (code == 206 && existing > 0);
        if (!append) existing = 0;
        long contentLen = c.getContentLength();
        long total = contentLen > 0 ? contentLen + existing : -1;

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (InputStream in = c.getInputStream();
             FileOutputStream fos = new FileOutputStream(file, append)) {
            byte[] buf = new byte[65536];
            int n;
            long got = existing;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (cb != null && !cb.on(got, total)) throw new Exception("用户取消");
            }
            fos.flush();
        }
        c.disconnect();
        return file.length();
    }

    /** 小文件整块下载（评论图片），返回是否成功 */
    public static boolean downloadSmall(String url, File file) {
        try {
            if (file.exists() && file.length() > 0) return true;
            download(url, file, null);
            return file.exists() && file.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return "?"; }
    }
}
