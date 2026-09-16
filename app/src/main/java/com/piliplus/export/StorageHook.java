package com.piliplus.export;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 系统层存储 hook。
 *
 * 目标：让 com.example.piliplus / com.example.piliplut 对 /storage/emulated/0
 * 获得 FULL 视图，从而可以直接用 File API 写 Download，
 * 无需 MANAGE_EXTERNAL_STORAGE 授权、无需 MediaStore 中转。
 *
 * 原理：Android 11+ 的 scoped storage 由 StorageManagerService
 * .getExternalStorageMountMode(uid, pkg) 决定每个 uid 的 FUSE 挂载视图。
 * 返回 MOUNT_EXTERNAL_FULL(4) 等价于拥有 MANAGE_EXTERNAL_STORAGE 的完整视图。
 *
 * 作用域：本 hook 必须注入 system_server（LSPosed 里勾选「系统框架」）。
 */
public final class StorageHook {

    private StorageHook() {}

    private static final String[] TARGET_PKGS = {
            "com.example.piliplus",
            "com.example.piliplut"
    };

    /** android.os.storage.StorageManager.MOUNT_EXTERNAL_FULL */
    private static final int MOUNT_EXTERNAL_FULL = 4;

    private static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String p : TARGET_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    /** 注入 system_server。 */
    public static void hookSystem(ClassLoader cl) {
        log("[PiliExport] system_server 注入");

        // Android 13：getExternalStorageMountMode(int, String) 定义在 StorageManagerService 本身，
        // 由内部类 StorageManagerInternalImpl 转发。两个候选都试。
        String[] classNames = {
                "com.android.server.StorageManagerService",
                "com.android.server.StorageManagerService$StorageManagerInternalImpl"
        };

        boolean hooked = false;
        for (String cn : classNames) {
            try {
                XposedHelpers.findAndHookMethod(cn, cl, "getExternalStorageMountMode",
                        int.class, String.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                String pkg = (String) p.args[1];
                                if (isTarget(pkg)) {
                                    p.setResult(MOUNT_EXTERNAL_FULL);
                                }
                            }
                        });
                hooked = true;
                log("[PiliExport] 已 hook getExternalStorageMountMode @ " + cn);
                break;
            } catch (Throwable t) {
                log("[PiliExport] 候选类未命中：" + cn + " :: " + t);
            }
        }

        if (!hooked) {
            log("[PiliExport] getExternalStorageMountMode 全部候选未命中");
        }
    }

    /** 注入 mediaprovider 进程（备用/诊断）。 */
    public static void hookMediaProvider(ClassLoader cl) {
        log("[PiliExport] mediaprovider 注入（诊断）");
    }

    private static void log(String s) {
        try { XposedBridge.log(s); } catch (Throwable ignored) {}
    }
}
