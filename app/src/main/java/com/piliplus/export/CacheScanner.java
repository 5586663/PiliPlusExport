package com.piliplus.export;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描 B站客户端缓存目录，产出 CacheItem 列表。
 *
 * 扫描深度：root/<分组>/<单集>/，与 hlbmerge 的 cache_data_manager 一致。
 *
 * 判定一个目录是「单集」的条件：
 *   含 video.m4s + audio.m4s，或含至少一个 .blv
 *
 * 标题来源优先级：
 *   1. entry.json 里的 title / page_data.part（Android）
 *   2. 目录名
 *
 * entry.json 是 UTF-8 JSON，这里只做字符串提取，不引入 JSON 库。
 */
public final class CacheScanner {

    private CacheScanner() {}

    private static final Pattern P_TITLE = Pattern.compile("\"title\\s*\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_PART  = Pattern.compile("\"part\\s*\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_INDEX = Pattern.compile("\"index\\s*\"\\s*:\\s*(\\d+)");
    private static final Pattern P_BLV   = Pattern.compile("^(\\d+)\\.blv$", Pattern.CASE_INSENSITIVE);

    public interface Log { void on(String s); }

    /**
     * @param root 用户选定的缓存根目录
     * @return 可合并的条目列表（已过滤掉无媒体文件的目录）
     */
    public static List<CacheItem> scan(File root, Log log) {
        List<CacheItem> out = new ArrayList<>();
        if (root == null || !root.isDirectory()) {
            if (log != null) log.on("缓存目录不存在：" + root);
            return out;
        }

        File[] groups = root.listFiles(File::isDirectory);
        if (groups == null) return out;

        for (File g : groups) {
            File[] eps = g.listFiles(File::isDirectory);
            if (eps == null) continue;

            for (File ep : eps) {
                CacheItem it = parseOne(g, ep);
                if (it != null && it.canMerge()) {
                    out.add(it);
                    if (log != null) log.on("发现：" + it.groupTitle + " / " + it.displayName());
                }
            }
        }

        if (log != null) log.on("共 " + out.size() + " 个可合并条目");
        return out;
    }

    /** 解析单个目录为 CacheItem；不是单集则返回 null */
    private static CacheItem parseOne(File group, File ep) {
        File video = new File(ep, "video.m4s");
        File audio = new File(ep, "audio.m4s");
        List<String> blv = listBlv(ep);

        boolean hasAv = video.exists() && audio.exists();
        boolean hasBlv = !blv.isEmpty();
        if (!hasAv && !hasBlv) return null;   // 不是单集

        CacheItem it = new CacheItem();
        it.sourceDir = ep.getAbsolutePath();
        it.groupTitle = group.getName();

        if (hasAv) {
            it.videoPath = video.getAbsolutePath();
            it.audioPath = audio.getAbsolutePath();
        }
        it.blvPaths = blv;

        File dm = new File(ep, "danmaku.xml");
        if (dm.exists()) it.danmakuPath = dm.getAbsolutePath();

        // 标题与序号
        File entry = new File(ep, "entry.json");
        String fromEntry = entry.exists() ? readText(entry) : null;
        if (fromEntry != null) {
            String part = firstGroup(P_PART, fromEntry);
            String title = firstGroup(P_TITLE, fromEntry);
            it.title = !part.isEmpty() ? part : title;
            it.p = parseNum(firstGroup(P_INDEX, fromEntry));
        }
        if (it.title == null || it.title.trim().isEmpty()) {
            it.title = ep.getName();
        }
        it.title = it.title.trim();

        return it;
    }

    /** 列出目录内 .blv，按文件名数字升序 */
    private static List<String> listBlv(File dir) {
        List<File> tmp = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (P_BLV.matcher(f.getName()).matches()) tmp.add(f);
            }
        }
        Collections.sort(tmp, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return blvIndex(a.getName()) - blvIndex(b.getName());
            }
        });
        List<String> out = new ArrayList<>();
        for (File f : tmp) out.add(f.getAbsolutePath());
        return out;
    }

    private static int blvIndex(String name) {
        Matcher m = P_BLV.matcher(name);
        if (m.matches()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    // ---------------- 工具 ----------------

    private static String readText(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 1 << 20)];   // 最多读 1 MB
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return "";
        String v = m.group(1);
        if (v == null) return "";
        // 反转义常见序列
        return v.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ").replace("\\t", " ");
    }

    private static int parseNum(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
