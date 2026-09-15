package com.piliplus.export;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 评论落盘：写 markdown + 下载评论图片。
 * 视频评论与动态评论共用。
 *
 * 目录约定（commentDir 内部）：
 *   评论.md
 *   评论图片/评论N.jpg          —— 第 N 条主评论的第 1 张图
 *   评论图片/评论N-2.jpg        —— 第 N 条主评论的第 2 张图
 *   评论图片/评论N_M.jpg        —— 第 N 条主评论下第 M 条楼中楼的图
 *   评论图片/评论N_M-2.jpg      —— 同上第 2 张
 */
public final class CommentSaver {

    private CommentSaver() {}

    public interface Log { void on(String s); }

    /**
     * @param commentDir  评论文件夹（会被创建）
     * @param header      评论.md 头部（可为 null）
     * @param mains       主评论列表（含楼中楼）
     * @param downloadPics 是否下载图片
     */
    public static int[] save(File commentDir, String header, List<Reply> mains,
                             boolean downloadPics, Log log) throws Exception {
        if (!commentDir.exists() && !commentDir.mkdirs()) {
            throw new Exception("无法创建目录：" + commentDir);
        }
        File picDir = new File(commentDir, "评论图片");
        if (downloadPics && !picDir.exists()) picDir.mkdirs();

        int picCount = 0;
        if (downloadPics) {
            for (int i = 0; i < mains.size(); i++) {
                Reply m = mains.get(i);
                picCount += savePics(m, i + 1, picDir, log);
                for (int k = 0; k < m.subs.size(); k++) {
                    picCount += saveSubPics(m.subs.get(k), i + 1, k + 1, picDir, log);
                }
            }
        }

        String md = MdWriter2.comments(header, mains);
        writeFile(new File(commentDir, "评论.md"), md);
        return new int[]{mains.size(), picCount};
    }

    private static int savePics(Reply m, int n, File picDir, Log log) {
        int c = 0;
        for (int j = 0; j < m.pics.size(); j++) {
            String url = m.pics.get(j);
            String name = (j == 0) ? ("评论" + n) : ("评论" + n + "-" + (j + 1));
            if (HttpDownloader.downloadSmall(url, new File(picDir, name + NameUtil.imgExt(url)))) c++;
        }
        return c;
    }

    private static int saveSubPics(Reply s, int n, int subIdx, File picDir, Log log) {
        int c = 0;
        for (int j = 0; j < s.pics.size(); j++) {
            String url = s.pics.get(j);
            String name = "评论" + n + "_" + subIdx + (j == 0 ? "" : ("-" + (j + 1)));
            if (HttpDownloader.downloadSmall(url, new File(picDir, name + NameUtil.imgExt(url)))) c++;
        }
        return c;
    }

    static void writeFile(File f, String content) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
