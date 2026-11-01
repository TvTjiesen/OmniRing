#!/usr/bin/env bash
# 在自建虚拟台 bench_a15（AOSP Android 15 + **LSPosed v1.11.0**）上验"legacy-only 包"能不能真加载。
#
# 为什么值得单独钉成脚本：这台虚拟机就是我们没有 HyperOS 3 真机时唯一的替身 ——
# 它的 LSPosed 版本和酷友那批人大概率的一致，而"双声明包在这台机上静默不加载"
# 是 2026-10-31 实测出来的。发布出去的 legacy-only 包必须在这儿跑过一次，
# 不能只靠"zip 里少了两个文件"就宣称它能用。
#
# 判据不是"logcat 里没报错"，是探针 8192 回显的那一行：
#   entry=legacy + build=当前戳 + legacyHooked=<装了哪几个钩点> + runtime=started=true…
# 三个都齐才叫"老框架上真的能用"。
set -uo pipefail
ADB=${ADB:-/e/dev/android-sdk/platform-tools/adb.exe}
SER=${SER:-emulator-5554}
EMU=${EMU:-/e/dev/android-sdk/emulator/emulator.exe}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-E:/dev/avd_home}
APK=${APK:-E:/dev/hzr/build/omniring-1.39.0-legacy.apk}
PKG=io.github.tvtjiesen.omniring
# 期望的探针戳：从源码里读，不写死，免得下次改 cfg 忘了同步这里
WANT=$(grep -o 'BUILD_TAG = "[^"]*"' E:/dev/hzr/app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/DebugProbe.kt | sed 's/.*"\(.*\)"/\1/')
[ -n "$WANT" ] || { echo "!! 读不出 BUILD_TAG"; exit 1; }

sh_() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" shell "$1" 2>&1 | tr -d '\r'; }
say() { echo "── $*"; }
die() { echo "!! $*" >&2; exit 1; }

running=$(MSYS_NO_PATHCONV=1 "$ADB" devices | grep -c "^$SER")
if [ "$running" != "1" ]; then
  say "虚拟机没在跑，拉起来"
  nohup "$EMU" -avd bench_a15 -writable-system -gpu auto -no-audio -no-boot-anim \
    > E:/dev/hzr/build/emulator_bench.log 2>&1 &
  MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" wait-for-device
fi
for i in $(seq 1 40); do
  sleep 5
  [ "$(sh_ 'getprop sys.boot_completed')" = "1" ] && { say "已开机（$((i * 5))s）"; break; }
done
[ "$(sh_ 'getprop sys.boot_completed')" = "1" ] || die "虚拟机没开机"

say "装 $APK"
MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" install -r -g "$APK" 2>&1 | tail -1 | grep -q Success || die "装机失败"
say "装到的是：$(sh_ "pm path $PKG")"
say "等 35 秒让 lspd 的 db 跟上新 codePath"
sleep 35

# 这台机上 adb 拿不到 root（su 被拒 / adb root 不管用），所以只能整机重启，
# 而虚拟机 adb reboot 会把 emulator 进程本身带走 —— 于是重启=退出，再手动拉起来。
say "重启（在这台机上等价于退出 emulator 再拉起）"
MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" reboot 2>/dev/null
sleep 25
nohup "$EMU" -avd bench_a15 -writable-system -gpu auto -no-audio -no-boot-anim \
  >> E:/dev/hzr/build/emulator_bench.log 2>&1 &
MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" wait-for-device
for i in $(seq 1 40); do
  sleep 5
  [ "$(sh_ 'getprop sys.boot_completed')" = "1" ] && break
done
[ "$(sh_ 'getprop sys.boot_completed')" = "1" ] || die "重启后没开机"
say "重启后再等 30 秒让钩子和传感器重试跑完一轮"
sleep 30

sh_ "settings put global hzr_probe 8192"
sleep 8
E=$(sh_ "settings get global hzr_entry")
[ "$E" = "null" ] && { sh_ "settings put global hzr_probe 8193"; sleep 8; E=$(sh_ "settings get global hzr_entry"); }
say "入口回显：$E"
H=$(sh_ "settings get global hzr_heartbeat")
say "心跳：$H"

case "$E" in *build=$WANT*) : ;; *) die "system_server 里跑的不是 $WANT（$E）" ;; esac
case "$E" in *entry=legacy*) : ;; *) die "没走 legacy 入口（$E）" ;; esac
case "$E" in *legacyHooked=none*|*legacyHooked=null*) die "legacy 入口一个钩点都没装上（$E）" ;; esac
case "$E" in *"started=true"*) : ;; *) die "共用的运行时没起来（$E）" ;; esac
say "结论：老 LSPosed 上 legacy-only 包真的能用（入口、钩点、运行时三项都有正面证据）"
