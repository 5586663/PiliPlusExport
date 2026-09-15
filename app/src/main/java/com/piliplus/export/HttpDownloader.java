package com.piliplus.export;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 带 B站 cookie/Referer 的流式下载器。
 *
 * 支持 Range 断点续传：目标文件已存在且长度>0 时从断点继续。
 *
 * 关键点（针对 B站 upos CDN 的防盗链）：
 *   1. Referer 必须是具体视频页，不是站点根；由调用方通过 referer 参数传入
 *   2. 禁用 gzip（Accept-Encoding: identity），否则 Content-Length 与实际不符
 *   3. 显式处理 30x 重定向（含跨协议 http<->https）
 *   4. 返回 0 字节视为失败，抛异常，不产空文件
 */
public final class HttpDownloader {

    private HttpDownloader() {}

    public interface Progress {
        /** @return false 表示用户取消 */
        boolean on(long got, long total);
    }

    /** 兼容旧调用：Referer 用站点根 */
    public static long download(String url, File file, Progress cb) throws Exception {
        return download(url, file, cb, "https://www.bilibili.com/");
    }

    /**
     * 下载 URL 到 file。
     *
     * @param referer 防盗链所需的 Referer，建议传具体视频页 URL
     * @return 实际写入后的文件总长度（>0）
     */
    public static long download(String url, File file, Progress cb, String referer) throws Exception {
        long existing = file.exists() ? file.length() : 0;

        HttpURLConnection c = open(url, referer, existing > 0 ? existing : -1);

        int code = c.getResponseCode();

        // 重定向：HttpURLConnection 默认只跟随同协议，跨协议需手动
        if (code >= 300 && code < 400) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null || loc.isEmpty()) throw new Exception("HTTP " + code + " 无 Location");
            if (loc.startsWith("/")) {
                URL base = new URL(url);
                loc = base.getProtocol() + "://" + base.getHost() + loc;
            }
            return download(loc, file, cb, referer);
        }

        if (code == 416) { c.disconnect(); return existing; }   // 已下完
        if (code >= 400) {
            String body = readSmall(c);
            c.disconnect();
            throw new Exception("HTTP " + code + " @ " + host(url)
                    + (body.isEmpty() ? "" : ("\n" + body)));
        }

        boolean append = (code == 206 && existing > 0);
        if (!append) existing = 0;
        long contentLen = c.getContentLength();
        long total = contentLen > 0 ? contentLen + existing : -1;

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        long got = existing;
        try (InputStream in = c.getInputStream();
             FileOutputStream fos = new FileOutputStream(file, append)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (cb != null && !cb.on(got, total)) throw new Exception("用户取消");
            }
            fos.flush();
        } finally {
            c.disconnect();
        }

        long len = file.length();
        if (len <= 0) {
            throw new Exception("下载 0 字节 @ " + host(url)
                    + "（HTTP " + code + ", Content-Length " + contentLen + "）");
        }
        return len;
    }

    /** 建立连接并设置请求头 */
    private static HttpURLConnection open(String url, String referer, long rangeStart) throws Exception {
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);   // 自己处理，避免跨协议丢失
        c.setRequestMethod("GET");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", SpaceApi.PC_UA);
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        c.setRequestProperty("Origin", "https://www.bilibili.com");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");   // 禁 gzip
        c.setRequestProperty("Connection", "keep-alive");
        String ck = BiliApi.COOKIE;
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("cookie", ck);
        if (rangeStart > 0) c.setRequestProperty("Range", "bytes=" + rangeStart + "-");
        return c;
    }

    /** 读取错误响应体前 300 字节，用于诊断 */
    private static String readSmall(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n = in.read(b);
            in.close();
            if (n <= 0) return "";
            String s = new String(b, 0, Math.min(n, 300), "UTF-8").replace("\n", " ").trim();
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 小文件整块下载（评论图片），返回是否成功 */
    public static boolean downloadSmall(String url, File file) {
        try {
            if (file.exists() && file.length() > 0) return true;
            download(url, file, null, "https://www.bilibili.com/");
            return file.exists() && file.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return "?"; }
    }
}
