#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 里装 JDK17 + Gradle + Android SDK，然后编译 LSPosed 模块
# 用法： su -M 10427 -c 'bash /sdcard/Download/PiliPlus_LSPosed/install_toolchain.sh'

set -x
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=$PREFIX/bin:$PATH
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

log() { echo "[$(date +%H:%M:%S)] $*"; }

cd $HOME || exit 1

log "=== 1/5 apt update ==="
apt-get update -y 2>&1 | tail -5

log "=== 2/5 安装 JDK17 + gradle + 解压工具 ==="
apt-get install -y openjdk-17 gradle wget unzip 2>&1 | tail -10

log "=== 版本确认 ==="
java -version 2>&1 | head -2
gradle --version 2>&1 | grep Gradle | head -1

log "=== 3/5 下载 Android cmdline-tools ==="
mkdir -p $ANDROID_HOME/cmdline-tools
cd $ANDROID_HOME/cmdline-tools
if [ ! -d latest ]; then
    wget -q --show-progress -O cmdtools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q cmdtools.zip
    rm -f cmdtools.zip
    mv cmdline-tools latest 2>/dev/null || true
fi
ls -d $ANDROID_HOME/cmdline-tools/latest

log "=== 4/5 安装 platform 35 + build-tools 35 ==="
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -8

log "=== 5/5 编译 LSPosed 模块 ==="
cd /sdcard/Download/PiliPlus_LSPosed || exit 1
chmod +x BUILD.sh 2>/dev/null || true

gradle assembleRelease --no-daemon 2>&1 | tail -30

APK=app/build/outputs/apk/release/app-release.apk
if [ -f "$APK" ]; then
    log "=== 构建成功 ==="
    ls -la "$APK"
    cp -f "$APK" /sdcard/Download/PiliPlusExport.apk && \
      log "已复制到 /sdcard/Download/PiliPlusExport.apk"
else
    log "=== 构建失败：无 APK ==="
fi

log "=== 全部完成 ==="
