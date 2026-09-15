#!/data/data/com.termux/files/usr/bin/bash
# 在 proot Debian 里构建 LSPosed 模块
# 前置：openjdk-17-jdk、gradle、Android SDK (platforms;android-35, build-tools;35.0.0)

set -e

: "${ANDROID_HOME:?请先设置 ANDROID_HOME}"

cd "$(dirname "$0")"

# 用系统 gradle（不依赖 gradle-wrapper.jar）
if command -v gradle >/dev/null 2>&1; then
    echo "[1/2] gradle assembleRelease"
    gradle assembleRelease --no-daemon
else
    echo "缺少 gradle。在 Debian 里执行： apt install -y gradle"
    exit 1
fi

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
    echo
    echo "[2/2] 构建成功"
    ls -la "$APK"
    cp -f "$APK" /sdcard/Download/PiliPlusExport.apk 2>/dev/null \
      && echo "已复制到 /sdcard/Download/PiliPlusExport.apk" \
      || echo "（复制到 sdcard 失败，可手动取）"
else
    echo "构建失败：未生成 APK"
    exit 1
fi
