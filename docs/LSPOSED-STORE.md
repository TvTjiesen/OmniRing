# 上架 LSPosed 模块商店（Xposed-Modules-Repo）· 现状、门槛与做法

> **决定已下并执行（2026-10-31，cfg43 / 1.40.0-beta）**：业主选择进商店，所以应用 ID 已经
> 从上游的 `xyz.nextalone.hyperzoomring` 改成 **`io.github.tvtjiesen.omniring`**（商店的免域名通道
> `io.github.<用户名>.<模块名>`）。代码侧所有以包名为主键的东西改为从 `BuildConfig.APPLICATION_ID`
> 派生，并有单测钉住（`ConfigPayloadTest`、`LegacyPackageTest`）。
> 剩下的门槛只有一个：**正式签名密钥**（`tools/make_release_key.sh`，只能发布人本人跑）
> 和**老包迁移**（两个 ID 不能互相覆盖，App 首页已挂警示）。
> 下面的门槛分析保留着，是为了让"为什么要改"这件事以后还能查到人。

## 1. 官方流程（逐字来自仓库 README，不是转述）

来源：`Xposed-Modules-Repo/submission` 的 README（2026-10-31 用 GitHub API 取的正文）。

> Submit your Xposed Modules!
> To submit your module, please fire an issue with title `[submission] packagename`,
> where `packagename` is the application ID of your module. Then our bot will automatically
> create a new repository and invite you to be the maintain.
>
> **Application ID requirement**
> - For reverse domain name notation in your application id, please use something you own rather than random domain.
> - You are require to set a TXT record for your root domain: `lsposed-modules-repo-verification=your_github_username`
> - If you do not own any domain, you may use `io.github.{username}` prefix, …
>
> **Repository content**
> - SUMMARY: the summary of your module, will be shown in the front page
> - README.md: the full description of your module
> - Please do not upload your source code here, we don't need your source code
>
> **Important notes**
> 1. If your repository is incomplete, it won't be shown
> 2. Update of your repository will automatically trigger build and be shown in 5 to 60 minutes.
> 3. … Release Tag: `[version code]-[version name]` … if you edit the release by **only** changing the apk,
>    bot cannot know your editing and the tag won't be updated. So … always submit release with valid apk.

另外两条来自官方渠道的补充信息（不是 README，别当同一强度）：

- LSPosed 官博/公告（Telegram `@LSPosed`，2026-03-17）：因为 AI 生成的新模块提交量暴涨，
  他们计划把仓库升级成**自动审批**，做法仿 Maven 中央仓库 —— 提交者必须证明自己能控制包名所属域名；
  没有域名就用 `io.github.[user name].[module name]`，"其它包名原则上不会被批准"，
  并且**开始检查"仓库包名与发布的 APK 文件不符"的情况**。
  → 也就是说：想蒙过去用一个不属于自己的 applicationId，以后是会被机器抓的方向。
- 收录后的展示：商店清单现在是由 `Xposed-Modules-Repo/modules` 那条流水线构建成 JSON
  （我看到那个仓库 `build.yml` 已经是 "Public JSON Mirror"，从 Cloudflare Pages 镜像到 GitHub Pages），
  管理器读的就是这份。对我们只意味着一件事：**改动不会立刻生效，5~60 分钟**。

## 2. 我们这个包，逐项对照差什么

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 应用 ID 归属 | ⛔ **唯一的硬门槛** | `xyz.nextalone.*` 不是我们的域名，也过不了"包名与 APK 相符"的检查。要么证明拥有域名（加 TXT `lsposed-modules-repo-verification=TvTjiesen`），要么按 README 给的免域名通道改 `io.github.tvtjiesen.omniring` |
| 官方仓库里是否重名 | ✅ 已查（两条正面证据） | ① `Xposed-Modules-Repo/xyz.nextalone.hyperzoomring` → API 404；② 在该 org 内搜 `hyperzoomring` → **0 条**（上游本身没上架，所以不存在"抢别人条目"，但也不能借它的包名提交）。同一作者另一个模块在架上的名字是 `Xposed-Modules-Repo/ltd.nextalone.pkginstallerplus` —— 说明 `nextalone` 这一族的 ID 归属确实是他，我们更不该用。`io.github.tvtjiesen.*` 也查过，404，可用 |
| 正式版签名 | ◐ | 现在 release 会**回落到调试库**签名（`README.md` 写了这条回落）。密钥必须由业主亲手生成（`bash tools/make_release_key.sh`，口令不经过我）。**这把钥匙要在全世界第一次对外发布之前就定死**，因为上架之后再换签名 = 所有已装机用户更新不了，只能卸载重装 |
| GPL-3.0 义务 | ✅ | 源码仓库已公开（`TvTjiesen/OmniRing`），`MODIFICATIONS.md` 是 §5a 要求的显著声明；`LICENSE` 保持 GPL-3.0 |
| 上传哪一份 APK | ◐ 待定 | 建议**上架那份用 legacy-only 包**（`-Phzr.legacyOnly=true`）：老 LSPosed 撞上双声明会一条都不加载（`HYPEROS3-SELFCHECK.md` §6 实测），商店用户里一定有人是老版本框架，"能加载 + 一条废弃警告" 比 "界面一切正常但完全失灵" 好得多。要发双声明那份也行，但必须在说明里写清 LSPosed 需 ≥2.2.x |
| SUMMARY / README / 图标 | ◐ 待写 | 首页只露 SUMMARY 一行；图标来自 APK 自己的启动图标（我们是自适应 XML，没有 512px PNG）—— 上架前建议补一张，效果差别很大 |
| Release 规范 | ✅ 规则清楚 | Tag 必须是 `<versionCode>-<versionName>`（我们现在是 `50-1.40.0`），而且**创建 release 时就把 APK 附件一起传上去**；事后只替换附件，机器人看不到，商店里版本号就不会更新 |
| 商店仓库存不放源码 | ✅ | README 明说不要往那个仓库传源码 —— 我们照常把源码放在自己的仓库 |

## 3. 包名：两条路怎么挑，以及最后走了哪条

**A. 沿用 `xyz.nextalone.hyperzoomring`，不上架** —— 已装机的人能一直原地覆盖，作用域与配置不动；
代价是管理器里搜不到。
**B. 改 `io.github.tvtjiesen.omniring`，走商店** —— 商店那条硬规则过得去；
代价是老包不会被覆盖，两边并存会各自抓一次环，必须"先卸老包再装新包"，
而卸载会带走 LSPosed 的模块记录、作用域勾选和那份手势/场景配置。

**2026-10-31 业主选 B，已执行完（cfg43 / 1.40.0-beta）。** 执行记录：

- 真实字面量是 **11 处**（我最初估的 7 处偏少，因为只数了引号恰好包住包名的那些；
  广播 action、签名级权限名都是"包名嵌在长字符串里"，第一遍没被抓到）。
  逐类清掉的方式是**取消字面量、改为派生**，而不是逐处替换字符串：
  - `app/build.gradle.kts`：`applicationId` 改新值；**`namespace` 保持 `xyz.nextalone.hyperzoomring`**
    （源码目录、`package`/`import`、R 类、KSP 生成的入口类名一个字不动，装机身份才变）。
  - `util/ModulePath.kt`：`const val PKG` → `val PKG = BuildConfig.APPLICATION_ID`
    （不再是 const，调用方都是运行时表达式，编译通过即安全）。
  - `config/ConfigPayload.kt`：4 条 action + 1 条权限名全部 `"${ModulePath.PKG}…"`。
  - `hook/InputInterceptorHook.kt`：`ACTION_ZOOM_RING_EVENT` 派生化 + `setPackage(...)` 用 `ModulePath.PKG`。
  - `hook/SensorRingDriver.kt`：`setPackage(...)` 同上。
  - `AndroidManifest.xml`：两行改成 `${applicationId}.permission.PUSH_CONFIG`（AGP 内建占位符，
    已用合并后的清单核对：`io.github.tvtjiesen.omniring.permission.PUSH_CONFIG` 出现两次）。
  - `tools/deploy_verify.sh`、`tools/legacy_bench_verify.sh`、`build/deliver.sh` 的 `PKG=`/`P=` 同步。
- 为什么 action 和权限名必须跟着包名（不只是整洁）：
  ① 权限名在设备上是全局注册项，新旧两包用不同签名声明同名 signature 权限会互斥，必有一个装不上；
  ② 配置推送那条是**不带 setPackage 的隐式广播**，action 同名时新旧两个模块会各自收对方一份配置，
  症状就是"改了设置时灵时不灵"；③ 环事件广播同名会让界面收到两份事件。
- 锁：`ConfigPayloadTest.广播与权限名都从 applicationId 派生`（含"ID 必须以 `io.github.` 开头"这条
  商店规则）+ `LegacyPackageTest`（旧 ID 与当前 ID 必须不同，防止有人改回去把警示变成永久挂显示）。
- 迁移警示已进界面：`util/LegacyPackage.kt` + 首页那张卡，四种语言都有文案。
  bench 上实测：老包装着时 `installed=true why=installed → 警示=true` 且列表整体位移
  （卸载老包后同一操作路径 `false`）；文案本身的可读性要等他手机插上再用截图确认一次。
- 还没做的验证（说明白，不含糊）：**改完包名之后还没在任何设备上跑过"模块真加载 + 配置真推达"**。
  bench 那台 LSPosed 1.11.0 的启用要在管理器界面里点，而那个界面在新版本上是 Compose，
  `uiautomator` 抓不到可读节点，我停在"不靠肉眼猜坐标"这条规矩上；
  他手机现在没插回来。所以下一次装机必须走 `tools/deploy_verify.sh` 的全套闸门
  （`build=cfg43` 回显 + `module_enabled` + 心跳重建），三条齐了才能说这次改名是端到端通的。

## 4. 真要提交时的操作顺序（B 路，一次做完）

1. `bash tools/make_release_key.sh`（业主本人，口令不落盘）→ 记下 SHA256 指纹。
2. 改 `applicationId`（3 处代码 + 构建脚本），本地跑 `./gradlew :app:testDebugUnitTest`
   与 `assembleDebug`；装机跑 `bash tools/deploy_verify.sh`，必须看到 `build=cfgNN` 与
   `总开关：开` 两行都过。虚拟机侧再跑一次 `bash tools/legacy_bench_verify.sh`。
3. 出一个正式版：去掉 `-beta`（`versionName` 与 `versionCode` 一起对齐，商店 tag 用
   `<versionCode>-<versionName>`，**注意商店不会认 beta 语义，但认 tag 格式**），
   两套产物分别构建：
   `./gradlew assembleRelease`（双声明）与 `./gradlew -Phzr.legacyOnly=true assembleRelease`（上架那份）。
4. 在 `Xposed-Modules-Repo/submission` 开 issue，标题一字不差：
   `[submission] io.github.tvtjiesen.omniring`
   → 机器人建仓库并邀请你为 maintainer（**Description/Title/Home Page 只能在浏览器里改，
   README 明说 API 改不了**）。
5. 在新仓库填 `SUMMARY`（一行）与 `README.md`（详细介绍），**不要**传源码。
6. 建 GitHub Release：Tag `50-1.40.0`，Title `1.40.0`，正文=changelog，
   **同一个 release 里把 APK 附件一起传上去**，Home Page 指回源码仓库或支持群。
7. 等 5~60 分钟；在 LSPosed 管理器 → 商店 里按包名搜一次，确认图标/SUMMARY/版本号都对，
   并且**在测试机上验一次"从商店点更新"能不能装上**（商店→安装走的也是签名，
   正式签名和 beta 的调试签名不同，老 beta 用户必须卸载，这条要在说明里写清）。

## 5. 已经帮你写好的文案（可以直接粘）

- SUMMARY（商店首页一行，中英文各一版）：
  - `把小米大师变焦环变成通用旋钮：滚动、音量、亮度、视频进度，全在环上`
  - `Turn the Xiaomi Master Zoom Ring into a universal dial: scroll, volume, brightness, video seek`
- issue 标题：`[submission] io.github.tvtjiesen.omniring`
- Release Tag / Title：`50-1.40.0` / `1.40.0`
- README.md 骨架：直接用源码仓库 README 的"它是什么 / 怎么装 / 需要 root + LSPosed /
  作用域要勾哪些 / 支持的系统（HyperOS 3 待回执、HyperOS 4 已实测）/ 已知问题 /
  免责声明（改系统输入路径，刷机风险自担）/ 上游致谢 NextAlone/HyperZoomRing + GPL-3.0"。
  上游致谢这条**必须留在商店 README 里**（不只是 App 内）—— 这是 GPL 二改的署名要求，
  也是商店审核最容易挑的一眼。

## 6. 我这边没做的（说明白，不含糊）

- 没有替你生成密钥，也没碰过任何口令（凭据类一律不动）。
- 没有改包名，也没提交任何 issue / 没有对外发布任何东西。
- 商店的**审核细节**（他们到底看哪些点、图标尺寸要求）README 没写，我也没找到一手文档：
  README 里原本指路的示例仓库 `org.meowcat.example` 已经 404（那条在正文里被划掉了），
  所以我只能保证第 1 节那些是原文，不保证"图标必须 512×512"这类二手说法。
  真要提 issue 之前，我建议先在他们的 Telegram 群 `@LSPosed` 问一句当前元数据要求 ——
  这句话是二手信息，我把它和一手内容分开写着。

## 7. 现在能直接抄走的那一页（2026-11-01，正式签名已生效）

**签名身份（已定，以后不能换）**：证书 `CN=OmniRing, OU=TvTjiesen, O=TvTjiesen`
指纹 SHA256 `22:2F:C2:90:A5:AD:E9:39:8B:01:00:E9:68:31:27:11:82:86:F5:E1:45:DE:C6:81:B5:7A:E9:89:19:45:BC:1B`

**要上传的那一份**（只留传统入口，新老 LSPosed 都能加载）：
`build/OmniRing-1.40.0-beta-signed-legacy.apk` · 4,720,515 B · md5 `d750e303ebc1a7d8c8e68de8eb4fb989`
（自己日常机继续用 `OmniRing-1.40.0-beta-signed.apk`，双声明那份 · 4,720,852 B · md5 `dbd148d0e7bb5712641961d4dbaf79d6`）
两份都已用 `apksigner` 回读核过签名 = 上面那行指纹，且 dex 里的构建戳都是 `cfg44`。

**① 提交 issue**（仓库 `Xposed-Modules-Repo/submission`，标题一字不差）：

```
[submission] io.github.tvtjiesen.omniring
```

机器人会建仓库并邀请你当 maintainer。之后 Title / Description / Home Page 只能在浏览器里改（它 README 明说 API 改不了）。

**② 新仓库里填这两样**（源码不要放进来，它明说不要）：

`SUMMARY`（商店首页只露这一行）：

```
把小米大师变焦环变成通用旋钮：滚动、音量、亮度、视频进度，全在环上
```

`README.md` 用这版（中英各一段，署上游 + GPL 必须留）：

```markdown
## OmniRing 万能变焦环
把 Xiaomi 17 Ultra（含徕卡版）的大师变焦环当成通用旋钮用：像数码表冠那样跟手滚列表，
看视频时当进度环，也可以映射成音量 / 亮度 / 启动应用等动作。
需要 root + LSPposed（作用域勾 System Framework）。
Fork of [NextAlone/HyperZoomRing](https://github.com/NextAlone/HyperZoomRing), GPL-3.0.
Source & changelog: https://github.com/TvTjiesen/OmniRing
```

**③ 建 Release**（关键：tag 格式 + 附件必须在创建 release 时就传上去）：

| 字段 | 值 |
| --- | --- |
| Tag | `50-1.40.0` |
| Release title | `1.40.0` |
| 附件 | `OmniRing-1.40.0-beta-signed-legacy.apk` |
| 正文 | 下面这份 changelog |

```
- 应用 ID 改为 io.github.tvtjiesen.omniring（老包名 xyz.nextalone.hyperzoomring 需卸载，两者互不覆盖）
- 相机在前台时一律不注入手指（修"相机里概率触发上滑/下滑"）
- 冷启动进入动画（表冠被拨一下停在某一格；浅色主题下滚花齿自动换深色墨）
- HyperOS 3 双通道互斥（输入侧与传感器侧不再各执行一遍），并新增可回读证据：hzr_entry / 心跳第 8 段
- 应用内表盘灵敏度烘进默认值 2.5（原来只在调试键里）
- 这份包只保留传统模块入口：老版本 LSPosed 撞上双声明会一条都不加载，代价是会重新出现"使用已废弃功能"的黄字提示，不影响功能
```

**④ 提交后**：等 5~60 分钟；在 LSPosed 管理器 → 仓库 里按包名搜一次，确认图标 / SUMMARY / 版本号都对，
**并且真从商店点一次"安装"**（商店装的就是这份签名，和已装的调试签名 beta 不通用，老 beta 用户要先卸载）。

**⑤ 还没查清的一件事**（不当结论用）：官方没写图标尺寸要求，示例仓库 `org.meowcat.example` 已 404，
所以提交前建议在 Telegram `@LSPosed` 问一句当前元数据规范。这条是二手信息，我和上面的一手内容分开写。
