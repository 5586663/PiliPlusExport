package com.piliplus.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
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
import android.widget.ScrollView;
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
 * 不判断 packageName —— 由 LSPosed 作用域决定注入哪些应用，
 * 因此同一份模块可同时用于原版与共存版 PiliPlus。
 * 悬浮按钮用 tag 判重，每个 decorView 独立注入。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern MID_IN_URL = Pattern.compile("space\\.bilibili\\.com/(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("(\\d{2,})");

    private static final String TAG_COMMENT = "pili_export_fab_comment";
    private static final String TAG_UP = "pili_export_fab_up";
    private static final String TAG_CACHE = "pili_export_fab_cache";
    private static final String TAG_DEBUG = "pili_export_fab_debug";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName)) return;
        if (lp.packageName != null && lp.packageName.startsWith("com.android.providers.media")) {
            StorageHook.hookMediaProvider(lp.classLoader);
            return;
        }
        XposedHelpers.findAndHookMethod("android.app.Activity", lp.classLoader,
                "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        injectFabs((Activity) p.thisObject);
                        ensurePerm((Activity) p.thisObject);
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
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp1.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp1.rightMargin = dp(a, 8);
                lp1.bottomMargin = dp(a, 96);
                b1.setLayoutParams(lp1);
                b1.setOnClickListener(v -> askVideoId(a, false));
                decor.addView(b1);
            }

            if (decor.findViewWithTag(TAG_UP) == null) {
                Button b2 = makeButton(a, "导出UP主", "#FF6699");
                b2.setTag(TAG_UP);
                FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp2.rightMargin = dp(a, 8);
                b2.setLayoutParams(lp2);
                b2.setOnClickListener(v -> askUpId(a));
                decor.addView(b2);

            if (decor.findViewWithTag(TAG_CACHE) == null) {
                Button b3 = makeButton(a, "合并缓存", "#FFA500");
                b3.setTag(TAG_CACHE);
                FrameLayout.LayoutParams lp3 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp3.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp3.rightMargin = dp(a, 8);
                lp3.topMargin = dp(a, 96);
                b3.setLayoutParams(lp3);
                b3.setOnClickListener(v -> CacheUi.show(a));
                decor.addView(b3);

            if (decor.findViewWithTag(TAG_DEBUG) == null) {
                Button b4 = makeButton(a, "调试", "#666666");
                b4.setTag(TAG_DEBUG);
                FrameLayout.LayoutParams lp4 = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp4.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp4.rightMargin = dp(a, 8);
                lp4.bottomMargin = dp(a, 200);
                b4.setLayoutParams(lp4);
                b4.setOnClickListener(v -> askDebug(a));
                decor.addView(b4);
            }
            }
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
    /**
     * @param skipClip true 时不做剪贴板预填/预检，直接显示输入框。
     *                 用于用户主动选择"手动输入"，避免读取或改动剪贴板。
     */
    private static void askVideoId(Activity a, boolean skipClip) {
        String pre = null;
        if (!skipClip) {
            String fromClip = readClipBv(a);
            if (fromClip != null) { confirmOneVideo(a, fromClip); return; }
        } else {
            pre = readClipRaw(a);
        }

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 24);

        EditText et = new EditText(a);
        et.setHint("BV1xxxxxxxxx 或 av123456");
        box.addView(et);

        CheckBox cb = new CheckBox(a);
        cb.setText("下载评论图片");
        cb.setChecked(true);
        box.addView(cb);

        CheckBox cbVid = new CheckBox(a);
        cbVid.setText("下载视频文件（dash 高清，极慢、占空间大）");
        cbVid.setChecked(false);
        box.addView(cbVid);

        new AlertDialog.Builder(a)
                .setTitle("导出单个视频评论")
                .setView(box)
                .setPositiveButton("开始", (d, w) -> {
                    String s = et.getText().toString().trim();
                    Matcher m = BV.matcher(s);
                    if (m.find()) s = m.group();
                    if (s.isEmpty()) { toast(a, "请输入视频号"); return; }
                    exportOneVideo(a, s, cb.isChecked(), cbVid.isChecked());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void confirmOneVideo(Activity a, String bv) {
        new AlertDialog.Builder(a)
                .setTitle("检测到剪贴板中的视频号")
                .setMessage(bv + "\n\n直接导出该视频评论？")
                .setPositiveButton("导出", (d, w) -> exportOneVideo(a, bv, true, false))
                .setNegativeButton("手动输入", (d, w) -> askVideoId(a, true))
                .show();
    }

    private static void exportOneVideo(Activity a, String videoId, boolean withPics, boolean downloadVideo) {
        injectCookie(a);
        AlertDialog dlg = progressDialog(a, "正在导出评论");
        TextView tv = (TextView) dlg.findViewById(android.R.id.message);

        Handler ui = new Handler(Looper.getMainLooper());
        Exporter ex = new Exporter(a.getApplicationContext(), new Exporter.Progress() {
            @Override public void on(String stage, int main, int sub) {
                ui.post(() -> { if (tv != null) tv.setText(stage + "　主 " + main + "　楼 " + sub); });
            }
            @Override public void done(String path, int main, int sub, int pics, long api) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("导出完成")
                            .setMessage("主评论 " + main + " 条\n楼中楼 " + sub + " 条\n图片 " + pics + " 张"
                                    + (api > 0 ? ("\n接口报告 " + api + " 条") : "")
                                    + "\n\n目录：\n" + path)
                            .setPositiveButton("好", null).show();
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
        ex.downloadVideo = downloadVideo;
        ex.run(videoId, withPics);
    }

    // ==================================================================
    private static void askUpId(Activity a) {
        long fromClip = readClipMid(a);

        ScrollView sv = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 24);
        sv.addView(box);

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
        RadioButton r1 = new RadioButton(a); r1.setText("仅视频"); r1.setId(1);
        RadioButton r2 = new RadioButton(a); r2.setText("仅动态"); r2.setId(2);
        RadioButton r3 = new RadioButton(a); r3.setText("视频 + 动态"); r3.setId(3);
        rg.addView(r1); rg.addView(r2); rg.addView(r3);
        rg.check(3);
        box.addView(rg);

        TextView t3 = new TextView(a);
        t3.setText("\n附加：");
        box.addView(t3);

        CheckBox cbVC = new CheckBox(a); cbVC.setText("视频评论"); cbVC.setChecked(true); box.addView(cbVC);
        CheckBox cbDC = new CheckBox(a); cbDC.setText("动态评论"); cbDC.setChecked(true); box.addView(cbDC);
        CheckBox cbPic = new CheckBox(a); cbPic.setText("评论图片 / 动态图片"); cbPic.setChecked(true); box.addView(cbPic);
        CheckBox cbVid = new CheckBox(a); cbVid.setText("下载视频文件（dash 高清，极慢、占空间大）"); cbVid.setChecked(false); box.addView(cbVid);

        TextView tip = new TextView(a);
        tip.setTextSize(12);
        tip.setText("\n目录：<UP名>/视频/<序号_标题>/、<UP名>/动态/<序号_标题>/\n"
                + "视频文件由 dash 分离流下载后经本机 MediaMuxer 合并。\n"
                + "不含视频文件时通常数分钟；含视频时视规模可能数小时。\n"
                + "建议保持前台，锁屏可能被系统挂起。");
        tip.setPadding(0, dp(a, 12), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(a)
                .setTitle("导出 UP 主内容")
                .setView(sv)
                .setPositiveButton("开始", (d, w) -> {
                    long mid = parseMid(et.getText().toString().trim());
                    if (mid <= 0) { toast(a, "请输入有效 UID"); return; }
                    int mode = rg.getCheckedRadioButtonId();
                    if (mode <= 0) mode = 3;

                    UpExporter.Options opt = new UpExporter.Options();
                    opt.videos = (mode == 1 || mode == 3);
                    opt.dyns = (mode == 2 || mode == 3);
                    opt.videoComments = opt.videos && cbVC.isChecked();
                    opt.dynComments = opt.dyns && cbDC.isChecked();
                    opt.commentPics = cbPic.isChecked();
                    opt.dynPics = cbPic.isChecked();
                    opt.downloadVideo = opt.videos && cbVid.isChecked();

                    exportUp(a, mid, opt);
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
            String t = readClipRaw(c);
            if (t == null) return 0;
            return parseMid(t);
        } catch (Throwable t) { return 0; }
    }

    private static void exportUp(Activity a, long mid, UpExporter.Options opt) {
        injectCookie(a);
        AlertDialog dlg = progressDialog(a, "正在导出 UP 主");
        TextView tv = (TextView) dlg.findViewById(android.R.id.message);

        Handler ui = new Handler(Looper.getMainLooper());
        UpExporter ex = new UpExporter(a.getApplicationContext(), new UpExporter.Progress() {
            @Override public void on(String stage, int cur, int total, String detail) {
                String line = stage;
                if (total > 0) line += "  " + cur + "/" + total;
                else if (cur > 0) line += "  " + cur;
                if (detail != null && !detail.isEmpty()) line += "\n" + detail;
                final String s = line;
                ui.post(() -> { if (tv != null) tv.setText(s); });
            }
            @Override public void done(String dir, int videos, int dyns, long comments, long bytes) {
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("导出完成")
                            .setMessage("视频 " + videos + " 个\n动态 " + dyns + " 条\n主评论 "
                                    + comments + " 条\n体积 " + (bytes / 1024 / 1024) + " MB\n\n目录：\n" + dir)
                            .setPositiveButton("好", null).show();
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
        ex.run(mid, opt);
    }

    // ==================================================================
    private static AlertDialog progressDialog(Activity a, String title) {
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage("准备中…")
                .setCancelable(false)
                .setNegativeButton("后台运行", (dd, w) -> {})
                .create();
        d.show();
        return d;
    }

    private static void injectCookie(Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir().getParentFile() + "/files/hive/account.hive");
            if (f.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] a = new byte[(int) f.length()];
                int o = 0, n;
                while (o < a.length && (n = in.read(a, o, a.length - o)) > 0) o += n;
                in.close();
                StringBuilder cur = new StringBuilder();
                java.util.List<String> t = new java.util.ArrayList<String>();
                for (int i = 0; i < a.length; i++) {
                    int v = a[i] & 0xff;
                    if (v >= 32 && v < 127) cur.append((char) v);
                    else { if (cur.length() >= 2) t.add(cur.toString()); cur.setLength(0); }
                }
                if (cur.length() >= 2) t.add(cur.toString());
                StringBuilder sb = new StringBuilder();
                String[] ks = {"SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3"};
                String bv3 = null;
                for (int k = 0; k < ks.length; k++)
                    for (int i = 0; i + 1 < t.size(); i++)
                        if (t.get(i).equals(ks[k])) { sb.append(ks[k]).append("=").append(t.get(i + 1)).append("; "); if (k == 4) bv3 = t.get(i + 1); break; }
                String ak = findAccessKey(a);
                if (ak != null) BiliApi.ACCESS_KEY = ak;
                if (bv3 != null) GrpcHeaders.buvid = bv3;
                if (sb.length() > 0) { BiliApi.COOKIE = sb.toString(); return; }
            }
        } catch (Throwable ignored) {}
        try {
            String ck = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            if (ck == null || ck.length() == 0) ck = CookieManager.getInstance().getCookie("https://app.bilibili.com");
            if (ck != null && ck.length() > 0) BiliApi.COOKIE = ck;
        } catch (Throwable ignored) {}
    }

    private static String findAccessKey(byte[] a) {
        for (int i = 0; i + 28 <= a.length; i++) {
            if ((a[i] & 0xff) != 1 || (a[i + 1] & 0xff) != 4) continue;
            int len = (a[i + 2] & 0xff) | ((a[i + 3] & 0xff) << 8) | ((a[i + 4] & 0xff) << 16) | ((a[i + 5] & 0xff) << 24);
            if (len < 16 || len > 64) continue;
            int vs = i + 6, ve = vs + len;
            if (ve + 6 > a.length) continue;
            boolean ok = true;
            for (int k = vs; k < ve; k++) { int ch = a[k] & 0xff; if (!((ch >= 48 && ch <= 57) || (ch >= 97 && ch <= 122) || (ch >= 65 && ch <= 90))) { ok = false; break; } }
            if (!ok) continue;
            if ((a[ve] & 0xff) != 2 || (a[ve + 1] & 0xff) != 4) continue;
            return new String(a, vs, len);
        }
        return null;
    }
    private static String readClipBv(Context c) {
        try {
            String t = readClipRaw(c);
            if (t == null) return null;
            Matcher m = BV.matcher(t);
            return m.find() ? m.group() : null;
        } catch (Throwable t) { return null; }
    }

    private static String readClipRaw(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            return String.valueOf(cd.getItemAt(0).coerceToText(c));
        } catch (Throwable t) { return null; }
    }


    // ==================================================================
    private static void askDebug(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle("调试转储")
                .setMessage("开启后，下一次评论请求会把 protobuf 结构\n"
                        + "（仅字段号与字节长度，不含任何内容）\n"
                        + "写入：\n" + DebugDump.outPath()
                        + "\n\n写完后自动关闭。仅本机文件，不联网。")
                .setPositiveButton("开启", (d, w) -> {
                    DebugDump.ENABLED = true;
                    toast(a, "已开启，去导出一次评论");
                })
                .setNeutralButton("查看结果", (d, w) -> showDebugResult(a))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showDebugResult(Activity a) {
        String s = DebugDump.read();
        if (s == null || s.isEmpty()) {
            toast(a, "尚无转储文件");
            return;
        }
        TextView tv = new TextView(a);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 32, 32, 32);
        tv.setText(s);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);
        new AlertDialog.Builder(a)
                .setTitle("转储内容")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }
    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    private static volatile boolean permChecked = false;

    /** 启动时检查「所有文件访问」；没有则弹一次，一键跳系统授权页。低版本无此 API 直接跳过。 */
    private static void ensurePerm(Activity a) { if (true) return; // 永久禁用授权弹窗
        if (permChecked) return;
        try {
            if (Environment.isExternalStorageManager()) { permChecked = true; return; }
        } catch (Throwable t) { return; }
        permChecked = true;
        try {
            new AlertDialog.Builder(a)
                .setTitle("建议授予「所有文件访问」")
                .setMessage("导出默认写入 Download。未授予时也能用，但文件会存到 App 私有目录（Android/data/…）。\n\n现在去授予？")
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + a.getPackageName())));
                    } catch (Throwable t) {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
        } catch (Throwable ignored) {}
    }
}
