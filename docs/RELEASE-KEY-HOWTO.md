# 正式签名密钥怎么生成（教你一步步做）

> 只有一样东西必须你亲手做：**输入口令**。口令只要经过我一次（落进文件、日志、
> 或任何命令行参数）就等于泄露 —— 拿到 `.jks` + 口令的人，可以以"官方更新"的名义
> 给所有已装机用户推任意包。所以下面第 1 步你在自己终端里跑，跑完把**指纹**（不是口令）发我。

## 0. 这套流程我已经用一次性密钥实测过（不是写给你看的空话）

我用一个专门造来扔掉的密钥把整条链跑通：`make_release_key.sh` 生成 → `--fingerprint` 读到
SHA256 `71:7B:08:CA…` → 用那把钥匙 + 四个环境变量 `assembleRelease` 出包 →
`apksigner verify --print-certs` 回显

```
V2 Signer: certificate DN: CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN
V2 Signer: certificate SHA-256 digest: 717b08caca860b903dccaaa689e6d018761337b92440dfc037dddd7ccc9275a5
```

两个 SHA256 一模一样，说明"生成 → 构建 → 校验"三段是通的。测试用的密钥和口令文件当场删掉了，
没留任何残留。**所以你现在要做的只是把同一个流程用真正的口令再走一遍。**

## 1A. 最简单的路：双击（不用终端，推荐）

双击 **`E:\dev\hzr\tools\make_key.bat`**，会弹出一个**置顶**的小窗口
「OmniRing 正式签名密钥」，里面两个打码输入框：

- 第一个框输口令（≥16 位，别用出现在聊天里的任何值），第二个框再输一遍，点「生成密钥」；
- 两次不一致或空口令 → 明确告诉你"什么都没生成"，重来即可；
- 成功后密钥在 `E:\dev\keys\omniring-release.jks`，而**指纹（公开信息）自动写进
  `E:\dev\keys\omniring-release-key-fingerprint.txt`** —— 你不用抄，我那边直接读得到。

这条链我用一次性口令端到端跑通了两遍（每遍都是新密钥、指纹各不相同、跑完自删，无残留），
并且验证过弹窗在这台机器上确实能显示。**如果跑的时候你屏幕上闪过一个 1 秒的小窗，那是我在自测，不是坏了。**

## 1B. 想用终端的话（和 1A 完全等价）

## 1. 生成密钥（约 1 分钟）

打开 **Git Bash**（Win 键敲 `git` 就能看到「Git Bash」），逐行敲：

```
cd /e/dev/hzr
bash tools/make_release_key.sh
```

它会问你两次口令：

```
将生成：E:/dev/keys/omniring-release.jks
alias=omniring 有效期=10950 天 DN=CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN
  新口令:            ← 打字时不显示光标移动，正常，盲打后回车
  再输一次:          ← 一样，回车
── 已生成 E:/dev/keys/omniring-release.jks
```

口令怎么定（建议照这个来）：

- **长度 ≥ 16 位**，由你自己的密码管理器生成（Bitwarden / KeePass / iCloud 钥匙串都行），别手编；
- 手编的话用一句只有你知道的话 + 数字，比如 `hzr-2026-omniring-key!`（**别真用这个**）；
- 口令**不要**发给我、不要发群里、不要贴在 issue 里。我要的是下一步的指纹，不是口令。

脚本故意做的事，你知道就好：口令只从键盘读进内存（不进 shell 历史、不进命令行参数），
只临时写一个 0600 的文件喂给 keytool 然后立刻删；密钥默认放在**仓库外面**（`E:/dev/keys`），
git 从物理上碰不到它；已经存在密钥时**拒绝覆盖**。

## 2. 立刻做三件事（缺一条就可能毁掉所有老用户）

```
bash tools/make_release_key.sh --fingerprint      # 会让你输一次口令
```

它会打出一行 `SHA256: XX:XX:XX:…`。**把这行发我**（这是公开证书指纹，可以公开），我写进发布说明，
以后每一版我都核对一次：换了把钥匙还以为换了个包，是最贵的事故。

然后：

1. 把 `E:\dev\keys\omniring-release.jks` 再复制一份到另一个地方（网盘或移动盘都行，别只留一块盘上）；
2. 口令单独存进密码管理器（文件在而口令没了 = 和丢了是同一件事）；
3. 别把这个文件提交进仓库。`.gitignore` 已经挡了 `*.jks`/`keystore.properties`，但**别去绕它**。

## 3. 出正式签名的包（密钥有了之后，谁跑都行）

在同一个 Git Bash 里跑下面这几行 —— 注意口令是用 `read -s` 现读的，
所以它只存在这一次会话的内存里，不写文件、不进历史、不出现在命令行参数：

```
cd /e/dev/hzr
export KEYSTORE_FILE=E:/dev/keys/omniring-release.jks
export KEY_ALIAS=omniring
read -rs -p "口令: " KEYSTORE_PASSWORD; KEY_PASSWORD=$KEYSTORE_PASSWORD; export KEY_PASSWORD; echo
./gradlew assembleRelease                                # 双声明那份（你日常机继续用）
./gradlew -Phzr.legacyOnly=true assembleRelease          # 只留传统入口那份（发酷友 / 上架）
```

验一下是不是真用正式钥匙签的（必须出现 `CN=OmniRing`，而且 SHA-256 等于第 2 步那行指纹）：

```
cmd //c "E:\\dev\\android-sdk\\build-tools\\37.0.0\\apksigner.bat verify --print-certs E:\\dev\\hzr\\build\\OmniRing-1.40.0-beta.apk"
```

**没给 `KEYSTORE_FILE` 时构建会回落到调试库签名**（`CN=Android Debug`）—— 那是故意的：
和已装的 beta 同签名，`adb install -r` 才能不卸载覆盖，不丢 LSPosed 的作用域和配置。
但公开发布**不能**用它。

## 4. 万一第 1 步的终端读不到口令（兜底两种）

脚本会明确报 `!! 这个终端读不到输入` 并告诉你走兜底，不会静默瞎跑。兜底 A（最省事，
口令由 keytool 自己问，同样不进命令行参数）：

```
mkdir -p /e/dev/keys
"/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/keytool" -genkeypair \
  -keystore E:/dev/keys/omniring-release.jks -alias omniring \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN"
```

回车后它会提示 `输入密钥库口令:`，盲打两次即可（第二次是密钥口令，直接回车表示与密钥库相同）。

兜底 B：Android Studio → `Build` → `Generate Signed App Bundle / APK` → `Create new…` 新建密钥库，
图形界面里输口令，路径填 `E:\dev\keys\omniring-release.jks`、alias 填 `omniring`。

## 5. 这些情况我会帮你兜住

- **换了正式签名第一次装到你手机上**：调试签名和正式签名不同，`install -r` 会报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须先卸载 —— 卸载会清掉 LSPosed 作用域与配置。
  我们这次已经把配置 1:1 迁过一次（`build/old_prefs_backup.xml` 也在），所以真到那一步，
  我先把当前 prefs 备份到 PC，再由你决定卸不卸。
- **口令忘了 / 文件丢了**：没有恢复途径（这正是它的价值）。所以第 2 步那两条备份不是建议，是要求。
- **要换密钥**（比如怀疑泄露）：必须先卸载重装所有用户，属于"发出去就回不了头"的操作，到时候我们先写迁移说明再动。
