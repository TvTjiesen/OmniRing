#!/usr/bin/env bash
# 装机 + 端到端验收，一条命令跑完。
#
# 为什么要脚本化：这台机器上有三个必须按顺序、且各有等待时间的坑 ——
#   1) LSPosed 的 db 要等约 30 秒才跟上新的 codePath，装完立刻重启框架会让模块静默不加载；
#   2) 判断"模块活着"只能用正面证据（心跳被重新写出来），不能靠"logcat 里没有报错"；
#   3) Settings.Global 的值跨重启持续存在，所以"读到了值"不等于"这一版跑过"，
#      必须先 delete 再等它被重新写出来。
# 这三条每次手敲都会漏，所以钉成脚本。
#
# 用法：
#   bash tools/deploy_verify.sh              # 完整：恢复映射 + 装机 + 重启框架 + 验收
#   bash tools/deploy_verify.sh --no-reboot  # 只装机和验收，不重启（没改模块代码时用）
set -uo pipefail

ADB=${ADB:-/d/dev/android-sdk/platform-tools/adb.exe}
SER=${SER:?要带 -s 的设备串号，例如 SER=你的串号}
APK=${APK:-D:/dev/hzr/app/build/outputs/apk/debug/app-debug.apk}
PKG=xyz.nextalone.hyperzoomring
PREFS=/data/data/$PKG/shared_prefs/hyperzoomring_config.xml
TMP_DEV=/data/local/tmp/hzr_prefs_restore.xml
NO_REBOOT=0
[ "${1:-}" = "--no-reboot" ] && NO_REBOOT=1

# 临时文件必须放在 Windows 看得见的地方：adb.exe 是 Windows 程序，
# 读不到 Git Bash 的 /tmp（那实际映射到 AppData\Local\Temp），push 会静默失败
TMP_LOCAL=D:/dev/hzr/build/tmp_prefs_restore.xml
mkdir -p D:/dev/hzr/build 2>/dev/null
trap 'rm -f "$TMP_LOCAL"' EXIT

# 设备侧命令一律走 run()：里面**不要再放单引号** —— adb → su -c → sh 要穿三层引号，
# 多一层就会静默把命令截断，排查起来非常费时间。
run() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" shell "su -c \"$1\"" 2>&1 | tr -d '\r'; }
out() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" shell "$1" 2>&1 | tr -d '\r'; }
say() { echo "── $*"; }
die() { echo "!! $*" >&2; exit 1; }
hb_now() { run "settings get global hzr_heartbeat"; }
dev_apk() { out "pm path $PKG" | sed -n "s/^package://p" | head -1; }

# ---------- 0. 人在用机就别动手 ----------
say "检查是否空闲（10 秒内两次采样心跳）"
A=$(hb_now); sleep 10; B=$(hb_now)
[ "$A" = "$B" ] || die "心跳计数在涨（$A → $B），他正在用机，先不要装机/重启框架"
[ "$B" = "null" ] && say "注意：现在没有心跳（模块本来没加载），装机后要看它能不能起来"

# ---------- 1. 把配置恢复成他的原始映射 ----------
# 测试期间我用 root 直接改过这份文件（顺时针慢转一度是 brightness_up）。
# 新构建里 App 会把整份配置推给 system_server，所以**推之前**必须先改对，
# 否则一次推送就把测试值变成正式配置。
say "恢复手势映射（先停 App，免得它用内存里的旧快照盖回来）"
# App 开机后没跑过时，ksu 域连它的 /data/data 都 stat 不到（实测报 No such file，
# 起来一次之后又能看见）—— 所以先拉起来一下再停掉，路径才稳定
out "am start -n $PKG/$PKG.ui.MainActivity" >/dev/null
sleep 4
out "am force-stop $PKG" >/dev/null
sleep 1
run "cp $PREFS /data/local/tmp/hzr_prefs.bak" >/dev/null
run "cat $PREFS" > "$TMP_LOCAL" || die "读不出设备上的配置"
[ -s "$TMP_LOCAL" ] || die "设备上的配置是空的，先确认 App 数据目录在不在"
python tools/restore_prefs.py "$TMP_LOCAL" || die "生成恢复用的 xml 失败"
MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" push "$TMP_LOCAL" "$TMP_DEV" >/dev/null 2>&1 || die "push 失败"
# 用 > 覆盖内容而不是 cp/mv 整个文件：这样属主和 SELinux 标签都还是原文件的
run "cat $TMP_DEV > $PREFS" >/dev/null
run "rm -f $TMP_DEV" >/dev/null
N=$(run "grep -c gesture_ $PREFS")
[ "${N:-0}" -ge 4 ] || die "配置恢复失败（只找到 $N 条 gesture 键）"
say "现在的映射：$(run "grep gesture_ $PREFS" | tr -d " " | tr "\n" " ")"

# ---------- 2. 装机 ----------
APK_LOCAL=$(printf '%s' "$APK" | sed -e "s|^\([A-Za-z]\):|/\1|" -e "s|^/D|/d|")
[ -f "$APK_LOCAL" ] || APK_LOCAL=$APK
MD5_LOCAL=$(md5sum "$APK_LOCAL" 2>/dev/null | cut -c1-32)
say "装机 $APK"
MSYS_NO_PATHCONV=1 "$ADB" -s "$SER" install -r -g "$APK" 2>&1 | tail -2 | grep -q Success || die "装机失败"
MD5_DEV=$(run "md5sum $(dev_apk)" | cut -c1-32)
if [ -n "$MD5_LOCAL" ] && [ "$MD5_LOCAL" != "$MD5_DEV" ]; then
  die "设备上那份 APK 和本地不是同一个（$MD5_LOCAL vs $MD5_DEV）"
fi
say "设备上 md5 与本地一致：$MD5_DEV"
NEW_MT=$(run "stat -c %Y $(dev_apk)")

# ---------- 3. 等 LSPosed 换路径，再重启框架 ----------
if [ "$NO_REBOOT" = "1" ]; then
  say "--no-reboot：跳过重启，直接验收"
else
  say "等 35 秒让 LSPosed 的 db 更新 codePath（装完立刻重启会静默不加载）"
  sleep 35
  say "重启 system_server（不是整机重启）"
  run "settings delete global hzr_heartbeat" >/dev/null
  # 注意 out() 只吃第一个参数，命令必须整体加引号 —— 之前写成 out pidof system_server
  # 结果 PID 是空的，kill 空跑一场，模块根本没重启，白等 150 秒
  PID=$(out "pidof system_server")
  [ -n "$PID" ] || die "拿不到 system_server 的 pid"
  run "kill $PID" >/dev/null
  sleep 8
  [ "$(out "pidof system_server")" != "$PID" ] || die "kill 之后 pid 没变（$PID），框架没重启成功"
  say "system_server 已重启（$PID → $(out "pidof system_server")）"
  LOADED=""
  for i in $(seq 1 25); do
    sleep 6
    H=$(hb_now)
    if [ -n "$H" ] && [ "$H" != "null" ]; then
      case "$H" in
        *"$NEW_MT"*) LOADED=1; say "模块已带新签名起来：$H"; break ;;
        *) say "心跳签名还是旧的：$H" ;;
      esac
    else
      say "t=$((i * 6))s 还没心跳"
    fi
  done
  if [ -z "$LOADED" ]; then
    # 实测：lspd 的 db 偶尔还是慢半拍，第一次重启没加载上；再重启一次框架就好了
    # （不用整机重启，也不用去 LSPosed 里手动开关）
    say "第一次没加载上，再重启一次 framework"
    P2=$(out "pidof system_server")
    run "kill $P2" >/dev/null
    for i in $(seq 1 25); do
      sleep 6
      H=$(hb_now)
      if [ -n "$H" ] && [ "$H" != "null" ]; then
        case "$H" in *"$NEW_MT"*) LOADED=1; say "第二次起来就正常了：$H"; break ;; *) say "心跳签名还是旧的：$H" ;; esac
      else
        say "t=$((i * 6))s 还没心跳"
      fi
    done
  fi
  [ -n "$LOADED" ] || die "两次重启都没带着新签名起来 —— 去 LSPosed 里把模块关开一次"
fi

# ---------- 4. 验收：配置推送通道 ----------
say "等 App 把配置推过来（开机后钩子会主动要三次：2s / 8s / 20s）"
STATE=""
for i in $(seq 1 12); do
  sleep 5
  STATE=$(run "settings get global hzr_cfg_state")
  echo "  t=$((i * 5))s hzr_cfg_state=$STATE"
  case "$STATE" in ""|null|0\;*) ;; *) break ;; esac
done
case "$STATE" in
  ""|null|0\;*)
    say "推送还没到：打开一次 App 就会推"
    out "am start -n $PKG/$PKG.ui.MainActivity" >/dev/null
    sleep 5
    STATE=$(run "settings get global hzr_cfg_state")
    echo "  再读：$STATE"
    ;;
esac

# 探针 640 = 128（导出钩子解析到的映射）+ 512（回显推送通道状态）
run "settings put global hzr_probe 0" >/dev/null
sleep 3
run "settings put global hzr_probe 640" >/dev/null
sleep 5
say "钩子读到的映射：$(run "settings get global hzr_map")"
say "配置通道回显：  $STATE / $(run "settings get global hzr_cfg")"
case "$STATE" in
  ""|null|0\;*) say "结论：推送通道没通 —— 去看签名权限与接收器注册日志" ;;
  *) say "结论：推送通道通了（改设置不用再重启框架）" ;;
esac

# ---------- 5. 收尾：清掉调试键 ----------
say "清理调试键"
for K in hzr_probe hzr_map hzr_cfg hzr_debug; do
  run "settings delete global $K" >/dev/null
done
for K in hzr_probe hzr_map hzr_cfg hzr_debug; do
  V=$(run "settings get global $K")
  [ "$V" = "null" ] || echo "  残留：$K=$V"
done
say "热调参数没动：hzr_scrub_y=$(run "settings get global hzr_scrub_y")"
