package com.piliplus.export;

import android.app.Application;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 存储放行 hook —— 注入 mediaprovider 进程（com.android.providers.media.module）。
 *
 * 只针对 Download 路径放行：目标包（piliplus / piliplut）uid 访问
 * /storage/emulated/0/Download 及其子路径时，FUSE 放行判定恒为 true；
 * 其余路径（含 Android/data 外的私人目录等）不受影响，仍走系统原判定。
 *
 * 放行发生在 FUSE JNI 回调的 Java 侧：FuseDaemon.isUidAllowedAccessToDataOrObbPath(uid, path)。
 * 无需 MANAGE 授权、无需 MediaStore 中转、无需重启 system_server，强停 mediaprovider 即生效。
 *
 * 作用域：LSPosed 勾「媒体存储 / MediaProvider」（com.android.providers.media.module）。
 */
public final class StorageHook {

    private StorageHook() {}

    private static final String[] TARGET_PKGS = {
            "com.example.piliplus",
            "com.example.piliplut"
    };

    /** 只放行这些前缀下的路径。 */
    private static final String[] ALLOW_PREFIXES = {
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Download/",
            "/Download",
            "/Download/"
    };

    private static final String[] CANDIDATE_CLASSES = {
            "com.android.providers.media.fuse.FuseDaemon",
            "com.android.providers.media.MediaProvider"
    };

    private static volatile int[] targetUids = null;

    public static void hookMediaProvider(ClassLoader cl) {
        log("[PiliExport] mediaprovider 注入，只放行 Download");
        for (String cn : CANDIDATE_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                int n = 0;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().contains("isUidAllowedAccessToDataOrObbPath")) continue;
                    if (m.getReturnType() != boolean.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            Integer uid = firstIntArg(p.args);
                            String path = firstStringArg(p.args);
                            if (uid == null || path == null) return;
                            if (targetUids == null) resolveUids();
                            if (isTargetUid(uid) && isDownloadPath(path)) {
                                p.setResult(Boolean.TRUE);
                            }
                        }
                    });
                    n++;
                    log("[PiliExport] hooked " + cn + "." + m.getName() + m);
                }
                log("[PiliExport] " + cn + " 命中 " + n + " 个放行方法");
            } catch (Throwable t) {
                log("[PiliExport] " + cn + " 未命中：" + t);
            }
        }
    }

    private static boolean isDownloadPath(String path) {
        String p = path.replace('\\', '/');
        for (String pre : ALLOW_PREFIXES) if (p.startsWith(pre)) return true;
        return false;
    }

    private static Integer firstIntArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof Integer) return (Integer) a;
        return null;
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof String) return (String) a;
        return null;
    }

    private static boolean isTargetUid(int uid) {
        int[] u = targetUids;
        if (u == null) return false;
        for (int x : u) if (x == uid) return true;
        return false;
    }

    private static synchronized void resolveUids() {
        if (targetUids != null) return;
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) { log("[PiliExport] 无 Application，暂缓解析"); return; }
            PackageManager pm = app.getPackageManager();
            List<Integer> u = new ArrayList<>();
            for (String pkg : TARGET_PKGS) {
                try { u.add(pm.getPackageUid(pkg, 0)); } catch (Throwable ignored) {}
            }
            int[] arr = new int[u.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = u.get(i);
            targetUids = arr;
            log("[PiliExport] targetUids=" + Arrays.toString(arr));
        } catch (Throwable t) {
            log("[PiliExport] resolveUids 失败：" + t);
        }
    }

    private static void log(String s) {
        try { XposedBridge.log(s); } catch (Throwable ignored) {}
    }
}
