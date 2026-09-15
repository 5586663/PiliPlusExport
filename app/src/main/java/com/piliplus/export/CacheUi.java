package com.piliplus.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 「合并缓存」按钮的交互界面。
 *
 * 与 MainHook 解耦：MainHook 只负责把按钮加进来，点击后转到本类。
 */
public final class CacheUi {

    private CacheUi() {}

    /** 常见缓存根目录候选，用于预填 */
    private static final String[] PRESETS = {
            "/sdcard/Android/data/tv.danmaku.bili/download",
            "/storage/emulated/0/Android/data/tv.danmaku.bili/download",
            "/sdcard/Android/data/com.bilibili.app.in/download",
            "/sdcard/Android/data/com.bilibili.app.pad/download"
    };

    public static void show(final Activity a) {
        ScrollView sv = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        sv.addView(box);

        TextView t0 = new TextView(a);
        t0.setText("缓存根目录：");
        box.addView(t0);

        final EditText et = new EditText(a);
        et.setHint("/sdcard/Android/data/tv.danmaku.bili/download");
        String found = firstExisting();
        if (found != null) et.setText(found);
        box.addView(et);

        // 快捷填入候选
        for (final String p : PRESETS) {
            if (!new File(p).isDirectory()) continue;
            Button b = new Button(a);
            b.setText("填入 " + p);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setOnClickListener(v -> et.setText(p));
            box.addView(b);
        }

        TextView t1 = new TextView(a);
        t1.setText("\n选项：");
        box.addView(t1);

        final CheckBox cbSingle = new CheckBox(a);
        cbSingle.setText("导出到单一目录（不按分组建子目录）");
        cbSingle.setChecked(false);
        box.addView(cbSingle);

        final CheckBox cbIndex = new CheckBox(a);
        cbIndex.setText("文件名加序号（001_标题）");
        cbIndex.setChecked(true);
        box.addView(cbIndex);

        final CheckBox cbDanmaku = new CheckBox(a);
        cbDanmaku.setText("导出弹幕文件（.xml，与视频同名）");
        cbDanmaku.setChecked(true);
        box.addView(cbDanmaku);

        TextView tip = new TextView(a);
        tip.setTextSize(11);
        tip.setText("\n输出：/sdcard/Download/PiliPlus_导出/缓存合并/\n"
                + "合并由 FFmpeg 7.1 完成，仅重封装不转码。\n"
                + "需 arm64 设备；其他架构会直接报错。\n"
                + "读取其他应用的缓存目录通常需要 Root / Shizuku。");
        box.addView(tip);

        new AlertDialog.Builder(a)
                .setTitle("合并 B站本地缓存")
                .setView(sv)
                .setPositiveButton("开始", (d, w) -> {
                    String path = et.getText().toString().trim();
                    if (path.isEmpty()) { toast(a, "请输入缓存目录"); return; }
                    File root = new File(path);
                    if (!root.isDirectory()) { toast(a, "目录不存在或不可读"); return; }

                    CacheMerger.Options opt = new CacheMerger.Options();
                    opt.singleOutputPath = cbSingle.isChecked();
                    opt.addIndex = cbIndex.isChecked();
                    opt.exportDanmaku = cbDanmaku.isChecked();

                    start(a, root, opt);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    private static void start(final Activity a, File root, CacheMerger.Options opt) {
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("正在合并缓存")
                .setMessage("准备中…")
                .setCancelable(false)
                .setNegativeButton("后台运行", (dd, w) -> {})
                .create();
        dlg.show();
        final TextView tv = dlg.findViewById(android.R.id.message);

        final Handler ui = new Handler(Looper.getMainLooper());
        CacheMerger.run(a.getApplicationContext(), root, opt, new CacheMerger.Progress() {
            @Override public void on(String stage, int cur, int total, String detail) {
                String line = stage;
                if (total > 0) line += "  " + cur + "/" + total;
                if (detail != null && !detail.isEmpty()) line += "\n" + detail;
                final String s = line;
                ui.post(() -> { if (tv != null) tv.setText(s); });
            }

            @Override public void done(String dir, int ok, int fail, int danmaku) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("合并完成")
                            .setMessage("成功 " + ok + " 个\n失败 " + fail + " 个\n弹幕 " + danmaku + " 个"
                                    + "\n\n目录：\n" + dir)
                            .setPositiveButton("好", null)
                            .show();
                });
            }

            @Override public void error(String msg) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("合并失败")
                            .setMessage(msg)
                            .setPositiveButton("好", null)
                            .show();
                });
            }
        });
    }

    /** 探测第一个存在的缓存目录 */
    private static String firstExisting() {
        for (String p : PRESETS) {
            if (new File(p).isDirectory()) return p;
        }
        return null;
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
