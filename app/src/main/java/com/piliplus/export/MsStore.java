package com.piliplus.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MediaStore 发布工具：把 App 私有目录里的导出产物镜像到共享 Download。
 *
 * 背景：Android 11+ 无 MANAGE_EXTERNAL_STORAGE 时，普通 App 无法用 File API 直写
 * /storage/emulated/0/Download。MediaStore 是官方允许的替代路径：通过
 * ContentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI) 在 Download
 * 下建文件，再向返回的 Uri 写字节，无需任何存储权限。
 *
 * 本类负责输出侧：导出先落 App 私有目录（File API 自由），结束时整树复制到 Download。
 */
public final class MsStore {

    private MsStore() {}

    /** MediaStore.Downloads 需 API 29+；低版本沿用旧 File 直写（那时 Download 可直写）。 */
    public static boolean available() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /**
     * 把 srcRoot 目录树镜像到 Download/&lt;relBase&gt;。
     * relBase 形如 "PiliPlus_导出/单视频/某标题"，可含或不含 "Download/" 前缀。
     *
     * @return 写入的文件数
     */
    public static int publishTree(Context ctx, File srcRoot, String relBase) throws Exception {
        if (!available()) throw new Exception("MediaStore 需要 Android 10 (API 29) 及以上");
        if (srcRoot == null || !srcRoot.isDirectory()) return 0;
        String base = normalizeRel(relBase);
        return walk(ctx, srcRoot, srcRoot, base);
    }

    private static int walk(Context ctx, File root, File cur, String relBase) throws Exception {
        File[] fs = cur.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) {
                n += walk(ctx, root, f, relBase);
            } else {
                writeOne(ctx, relDirOf(root, f, relBase), f.getName(), guessMime(f.getName()), f);
                n++;
            }
        }
        return n;
    }

    private static String relDirOf(File root, File f, String relBase) {
        String rootPath = root.getAbsolutePath();
        String filePath = f.getAbsolutePath();
        String sub = filePath.startsWith(rootPath) ? filePath.substring(rootPath.length()) : "";
        if (sub.startsWith("/")) sub = sub.substring(1);
        int slash = sub.lastIndexOf('/');
        String dirPart = slash >= 0 ? sub.substring(0, slash) : "";
        String full = dirPart.isEmpty() ? relBase : (relBase + "/" + dirPart);
        return normalizeRel(full);
    }

    /** 去首尾斜杠，并剥掉 "Download/" 前缀（RELATIVE_PATH 以 Download 为根）。 */
    private static String normalizeRel(String rel) {
        if (rel == null) return "";
        String s = rel.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.equals("Download")) s = "";
        else if (s.startsWith("Download/")) s = s.substring("Download/".length());
        return s;
    }

    private static void writeOne(Context ctx, String relDir, String name, String mime, File src) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String relPath = Environment.DIRECTORY_DOWNLOADS + (relDir.isEmpty() ? "" : "/" + relDir) + "/";
        String sel = MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = { relPath, name };
        try { cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, args); } catch (Throwable ignored) {}

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
        cv.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("MediaStore insert 失败：" + relPath + name);
        try (InputStream is = new FileInputStream(src);
             OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("openOutputStream 失败：" + uri);
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            os.flush();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, done, null, null);
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".md")) return "text/markdown"; if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
