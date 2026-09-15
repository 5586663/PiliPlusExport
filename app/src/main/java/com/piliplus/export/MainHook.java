package com.piliplus.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口。
 *
 * 每个 Activity 的 decorView 上分别注入两个悬浮按钮（用 tag 判重，
 * 不依赖静态引用，避免旧 Activity 的残留引用导致新页面不注入）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern MID_IN_URL =
            Pattern.compile("space\\.bilibili\\.com/(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("(\\d{2,})");

    private static final String TAG_COMMENT = "pili_export_fab_comment";
    private static final String TAG_UP = "pili_export_fab_up";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        XposedHelpers.findAndHookMethod("android.app.Activity", lp.classLoader,
                "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        injectFabs((Activity) p.thisObject);
                    }
                });
    }

    // ------------------------------------------------------------------
    private static void injectFabs(Activity a) {
        try {
            View root = a.getWindow().getDecorView();
            if (!(root instanceof FrameLayout)) return;
            FrameLayout decor = (FrameLayout) root;

            if (decor.findViewWithTag(TAG_COMMENT) == null) {
                Button b1 = makeButton(a, "导出评论", "#FB7299");
                b1.setTag(TAG_COMMENT);
                FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp1.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp1.rightMargin = dp(a, 8);
                lp1.bottomMargin = dp(a, 96);
                b1.setLayoutParams(lp1);
                b1.setOnClickListener(v -> askVideoId(a));
                decor.addView(b1);
            }

            if (decor.findViewWithTag(TAG_UP) == null) {
                Button b2 = makeButton(a, "导出UP主", "#FF6699");
                b2.setTag(TAG_UP);
                FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp2.rightMargin = dp(a, 8);
                b2.setLayoutParams(lp2);
                b2.setOnClickListener(v -> askUpId(a));
                decor.addView(b2);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Button makeButton(Activity a, String text, String color) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(48f);
        b.setBackground(bg);
        b.setAlpha(0.92f);
        b.setPadding(28, 14, 28, 14);
        return b;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================================================================
    private static void askVideoId(Activity a) {
        String fromClip = readClipBv(a);
        if (fromClip != null) {
            exportOneVideo(a, fromClip);
            return;
        }
        EditText et = new EditText(a);
        et.setHint("BV1xxxxxxxxx 或 av123456");
        new AlertDialog.Builder(a)
                .setTitle("导出评论区 Markdown")
                .setView(et)
                .setPositiveButton("开始", (d, w) -> {
                    String s = et.getText().toString().trim();
                    Matcher m = BV.matcher(s);
                    if (m.find()) s = m.group();
                    if (s.isEmpty()) { toast(a, "请输入视频号"); return; }
                    exportOneVideo(a, s);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String readClipBv(Context c) {
        try {
            String t = readClip(c);
            if (t == null) return null;
            Matcher m = BV.matcher(t);
            return m.find() ? m.group() : null;
        } catch (Throwable t) { return null; }
    }

    private static void exportOneVideo(Activity a, String videoId) {
        injectCookie();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 36, 48, 36);
        TextView tv = new TextView(a);
        tv.setTextSize(14);
        tv.setText("准备中…");
        box.addView(tv);

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("正在导出")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("后台运行", (d, w) -> {})
                .create();
        dlg.show();

        Handler ui = new Handler(Looper.getMainLooper());
        Exporter ex = new Exporter(a.getApplicationContext(), new Exporter.Progress() {
            @Override public void on(String stage, int main, int sub) {
                ui.post(() -> tv.setText(stage + "　主 " + main + "　楼 " + sub));
            }
            @Override public void done(String path, int main, int sub, long api) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("导出完成")
                            .setMessage("主评论 " + main + " 条\n楼中楼 " + sub + " 条\n合计 "
                                    + (main + sub) + " 条"
                                    + (api > 0 ? "\n接口报告 " + api + " 条" : "")
                                    + "\n\n文件：\n" + path)
                            .setPositiveButton("好", null)
                            .show();
                });
            }
            @Override public void error(String msg) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a).setTitle("导出失败").setMessage(msg)
                            .setPositiveButton("好", null).show();
                });
            }
        });
        ex.run(videoId, MdWriter.Style.HEADING);
    }

    // ==================================================================
    private static void askUpId(Activity a) {
        long fromClip = readClipMid(a);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 24);

        TextView t1 = new TextView(a);
        t1.setText("UP主 UID 或主页链接：");
        box.addView(t1);

        EditText et = new EditText(a);
        et.setHint("例如 12345678");
        if (fromClip > 0) et.setText(String.valueOf(fromClip));
        box.addView(et);

        TextView t2 = new TextView(a);
        t2.setText("\n导出内容：");
        box.addView(t2);

        RadioGroup rg = new RadioGroup(a);
        rg.setOrientation(RadioGroup.VERTICAL);
        RadioButton r1 = new RadioButton(a);
        r1.setText("仅视频（清单 + 全部评论）");
        r1.setId(1);
        RadioButton r2 = new RadioButton(a);
        r2.setText("仅动态（含发布时间）");
        r2.setId(2);
        RadioButton r3 = new RadioButton(a);
        r3.setText("视频 + 动态（全部）");
        r3.setId(3);
        rg.addView(r1);
        rg.addView(r2);
        rg.addView(r3);
        rg.check(3);
        box.addView(rg);

        CheckBox cb = new CheckBox(a);
        cb.setText("含视频评论（极慢，可能数小时）");
        cb.setChecked(true);
        box.addView(cb);

        TextView tip = new TextView(a);
        tip.setTextSize(12);
        tip.setText("不含评论时通常 1~3 分钟。\n"
                + "含评论时逐视频拉取，视规模可能数小时。\n"
                + "建议保持前台，锁屏可能被系统挂起。");
        tip.setPadding(0, dp(a, 12), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(a)
                .setTitle("导出 UP 主内容")
                .setView(box)
                .setPositiveButton("开始", (d, w) -> {
                    long mid = parseMid(et.getText().toString().trim());
                    if (mid <= 0) { toast(a, "请输入有效 UID"); return; }
                    int mode = rg.getCheckedRadioButtonId();
                    if (mode <= 0) mode = UpExporter.MODE_ALL;
                    exportUp(a, mid, mode, cb.isChecked());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static long parseMid(String s) {
        if (s == null || s.isEmpty()) return 0;
        Matcher m = MID_IN_URL.matcher(s);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
        }
        try { return Long.parseLong(s.trim()); } catch (Exception ignored) {}
        Matcher d = DIGITS.matcher(s);
        if (d.find()) {
            try { return Long.parseLong(d.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static long readClipMid(Context c) {
        try {
            String t = readClip(c);
            if (t == null) return 0;
            return parseMid(t);
        } catch (Throwable t) { return 0; }
    }

    private static void exportUp(Activity a, long mid, int mode, boolean withComments) {
        injectCookie();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 36, 48, 36);
        TextView tv = new TextView(a);
        tv.setTextSize(14);
        tv.setText("准备中…");
        box.addView(tv);

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("正在导出 UP 主")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("后台运行", (d, w) -> {})
                .create();
        dlg.show();

        Handler ui = new Handler(Looper.getMainLooper());
        UpExporter ex = new UpExporter(a.getApplicationContext(), new UpExporter.Progress() {
            @Override public void on(String stage, int cur, int total, String detail) {
                String line = stage;
                if (total > 0) line += "  " + cur + "/" + total;
                else if (cur > 0) line += "  " + cur;
                if (detail != null && !detail.isEmpty()) line += "\n" + detail;
                final String s = line;
                ui.post(() -> tv.setText(s));
            }
            @Override public void done(String dir, int videos, int dyns, long comments, long bytes) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("导出完成")
                            .setMessage("视频 " + videos + " 个\n动态 " + dyns + " 条\n评论 "
                                    + comments + " 条\n体积 " + (bytes / 1024) + " KB\n\n目录：\n" + dir)
                            .setPositiveButton("好", null)
                            .show();
                });
            }
            @Override public void error(String msg) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a).setTitle("导出失败").setMessage(msg)
                            .setPositiveButton("好", null).show();
                });
            }
        });
        ex.run(mid, MdWriter.Style.HEADING, mode, withComments);
    }

    // ==================================================================
    private static void injectCookie() {
        try {
            String ck = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            if (ck == null || ck.isEmpty()) ck = CookieManager.getInstance().getCookie("https://app.bilibili.com");
            if (ck != null && !ck.isEmpty()) BiliApi.COOKIE = ck;
        } catch (Throwable ignored) {}
    }

    private static String readClip(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            return String.valueOf(cd.getItemAt(0).coerceToText(c));
        } catch (Throwable t) { return null; }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
