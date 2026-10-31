#!/usr/bin/env bash
# 生成 OmniRing 的**正式签名密钥**（一次性的，之后所有版本都必须用它）。
#
# 为什么这一步必须你亲手跑、我不能代劳：
#   口令只要经过我一次（落进文件、日志、或任何命令行参数），就等于泄露 ——
#   拿到 .jks + 口令的人可以以"官方更新"的名义给所有已装机用户推任意包。
#   而密钥一旦丢了更狠：老用户永远收不到更新，只能卸载重装，卸载又会连带清掉
#   LSPosed 的模块记录、作用域和你那份配置。两件都不可逆，所以脚本故意做成：
#     · 拒绝覆盖已存在的密钥；
#     · 密钥默认放在**仓库外面**（E:/dev/keys），git 从物理上碰不到它；
#     · 口令走 /dev/tty 静默读入，只临时写进一个 0600 的文件传给 keytool，用完立刻删
#       （-storepass:口令 那种写法不行：命令行参数在同机任何进程都能读到）。
#
# 用法（Git Bash）：
#   bash tools/make_release_key.sh                 # 生成（会问两次口令）
#   bash tools/make_release_key.sh --fingerprint   # 事后复核指纹：证明"以后每版都是同一把"
set -uo pipefail

KEY_DIR=${KEY_DIR:-E:/dev/keys}
KEY_FILE=${KEY_FILE:-$KEY_DIR/omniring-release.jks}
ALIAS=${KEY_ALIAS_NAME:-omniring}
VALID_DAYS=${KEY_VALID_DAYS:-10950}   # 30 年
DN=${KEY_DN:-"CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN"}

KT=$(command -v keytool || true)
[ -n "$KT" ] || KT="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/keytool"
[ -x "$KT" ] || { echo "!! 找不到 keytool（需要 JDK 17+）" >&2; exit 1; }

TMP=""
cleanup() { [ -n "$TMP" ] && rm -f "$TMP"; }
trap cleanup EXIT

ask_once() { bash -c 'read -rs -p "  $1: " p; printf "%s\n" "$p"' _ "$1" </dev/tty; }

# 口令交给 keytool 的唯一安全通道：0600 的临时文件，函数返回后由调用方负责删
to_passfile() { TMP=$(mktemp -t hzrks.XXXXXX); chmod 600 "$TMP"; printf '%s\n' "$1" > "$TMP"; }

if [ "${1:-}" = "--fingerprint" ]; then
  [ -e "$KEY_FILE" ] || { echo "!! 还没有 $KEY_FILE" >&2; exit 1; }
  echo "复核 $KEY_FILE（alias=$ALIAS）"
  to_passfile "$(ask_once 密钥口令)"
  "$KT" -list -v -keystore "$KEY_FILE" -alias "$ALIAS" -storepass:file "$TMP" 2>&1 \
    | grep -iE "SHA256|SignatureAlgorithm|Valid from" | head -4
  echo "↑ 把这行 SHA256 抄进发布说明；以后每版都对一次，就不会出现"
  echo "  「换了把钥匙还以为换了个包」那种把老用户全掉光的事。"
  exit 0
fi

if [ -e "$KEY_FILE" ]; then
  echo "!! $KEY_FILE 已经存在 —— 不覆盖（覆盖了就等于把所有老用户的更新通道剪断）。" >&2
  echo "   要复核就：bash tools/make_release_key.sh --fingerprint" >&2
  exit 1
fi

mkdir -p "$KEY_DIR"
echo "将生成：$KEY_FILE"
echo "alias=$ALIAS 有效期=${VALID_DAYS} 天 DN=$DN"
P1=$(ask_once 新口令); echo
[ -n "$P1" ] || { echo "!! 空口令不放行" >&2; exit 1; }
P2=$(ask_once 再输一次); echo
[ "$P1" = "$P2" ] || { echo "!! 两次不一致，什么都没生成" >&2; exit 1; }

to_passfile "$P1"
"$KT" -genkeypair -keystore "$KEY_FILE" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
  -validity "$VALID_DAYS" -storepass:file "$TMP" -keypass:file "$TMP" -dname "$DN" >/dev/null 2>&1
GEN=$?
rm -f "$TMP"; TMP=""
[ "$GEN" = 0 ] || { echo "!! keytool 失败（没生成，也没覆盖任何东西）" >&2; exit 1; }
echo "── 已生成 $KEY_FILE"

cat <<EOF

── 打正式包时给构建用的四个环境变量（构建脚本只认这四个）：
   export KEYSTORE_FILE=E:/dev/keys/omniring-release.jks   # 正斜杠的 Windows 路径，别用 /e/…
   export KEYSTORE_PASSWORD=…   export KEY_ALIAS=$ALIAS   export KEY_PASSWORD=…
   ./gradlew assembleRelease                              # 双声明那份（你自己的日常机）
   ./gradlew -Phzr.legacyOnly=true assembleRelease        # 只留老入口那份（发给酷友 / 上架）
   # 不给 KEYSTORE_FILE 时构建会回落调试库 —— 那是"能覆盖已装 beta"的便利，不是正式发布。

── 三条备份，缺一条就可能毁掉所有老用户：
   1) .jks 再复制一份到另一个物理位置（网盘/移动盘）；
   2) 口令单独存密码管理器 —— 文件在而口令没了，和丢了是同一件事；
   3) 第一次装到已装过 beta 的机器上时，先 \`adb install -r\` 试覆盖：
      报 INSTALL_FAILED_UPDATE_INCOMPATIBLE 就说明这把钥匙和已装那份签名不一致，
      必须先卸载（会清掉 LSPosed 作用域和配置）。别顺手卸载，先把作用域截图记下来再装回来。
EOF
