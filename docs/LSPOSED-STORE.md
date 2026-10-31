# 上架 LSPosed 模块商店（Xposed-Modules-Repo）· 现状、门槛与做法

> 结论先说：**商店这条路的门槛不是审核质量，而是"包名归谁"**。
> 我们现在的应用 ID 是 `xyz.nextalone.hyperzoomring` —— 这是**上游作者的域名反写**，
> 按官方规则我们既证明不了对 `nextalone.xyz` 的控制权，也不该用它去申请。
> 所以「上架」真正要做的是一个决定：**换不换包名**（换 = 现有装机用户不能原地更新）。
> 其余都是体力活，我已经把可复制的文案准备好了。

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
| Release 规范 | ✅ 规则清楚 | Tag 必须是 `<versionCode>-<versionName>`（例如 `49-1.39.0`），而且**创建 release 时就把 APK 附件一起传上去**；事后只替换附件，机器人看不到，商店里版本号就不会更新 |
| 商店仓库存不放源码 | ✅ | README 明说不要往那个仓库传源码 —— 我们照常把源码放在自己的仓库 |

## 3. 包名这件事，两条路怎么挑

**A. 沿用 `xyz.nextalone.hyperzoomring`，不上架**
- 好处：已装机的人（他自己 + 酷友）永远能原地 `install -r` 覆盖，LSPosed 作用域、配置、
  `per_app_configured_packages` 全都不用重来；我们这套"心跳/探针/快照"验证链一次都不用改。
- 代价：只在 GitHub Release + 酷安 传播，管理器里搜不到。

**B. 改 `io.github.tvtjiesen.omniring`，走商店**
- 代码上只有 **7 处**真正绑死应用 ID（其余 380 多处都是 Kotlin 的 `package` 声明，跟安装包名无关）：
  - `app/build.gradle.kts:12`：`applicationId = ...`（**`namespace` 不用改**，源码目录和包声明一个字不动）
  - `util/ModulePath.kt:14` `const val PKG`（注意：改成 `BuildConfig.APPLICATION_ID` 就不再是 `const`，
    这一处要连着看调用方；顺手把下面两处也换成同一个来源，以后不可能漂移）
  - `hook/InputInterceptorHook.kt:324` 与 `hook/SensorRingDriver.kt:353` 的 `setPackage("…")`
  - `config/ConfigPayload.kt:49` 的签名级权限名 `…permission.PUSH_CONFIG`，
    和 `AndroidManifest.xml:9-12` 的 `<permission>` / `<uses-permission>` 两行
    —— **这一组不改会有真冲突**：权限名是设备全局的，如果哪天有用户同时装着上游
    `xyz.nextalone.hyperzoomring` 和我们的新包，两个 App 用不同签名声明同名 signature 权限，
    必有一个装不上（这也是"顺手把权限名跟着包名走"的理由，不只是整洁）
  - 作用域默认勾选、`ScopeReload.installedPaths` 的过滤都是按包名走的，改完必须重新跑一遍
    `deploy_verify.sh`（现在的闸门会自己把 `build=` 戳和 `module_enabled` 都验掉，
    不用信我说的"应该没问题"）
- 用户侧代价（这是真正的决定点）：新包名 = **新 App**。老包不会被覆盖，两边并存会同时抓环
  （两套模块、两条注入路径），所以必须卸载老包 → LSPosed 模块记录、作用域勾选、
  他调好的手势映射和场景配置**全部清零重设**。他自己的日常机也一样。
- 缓解做法：商店条目发出去之后，在 App 里加一次性提示"检测到旧包名 `xyz.nextalone…` 仍在安装，
  请先卸载再启用新版"，并把迁移清单写进发布说明。

**我的推荐：先 A 不动包名，把正式版签名和商店文案都备好；等 HyperOS 3 那轮回执回来、
参数收敛成 1.x 正式版之后再改 B。** 理由很直白：现在换包名等于在还没收敛的版本上
再叠一层"所有人重装"的成本，而商店条目本身不会因为我们晚两周提交而少用户；
反过来说，签名密钥必须现在就定 —— 那条是"以后永远不能变"的东西，越早定越便宜。

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
6. 建 GitHub Release：Tag `49-1.39.0`，Title `1.39.0`，正文=changelog，
   **同一个 release 里把 APK 附件一起传上去**，Home Page 指回源码仓库或支持群。
7. 等 5~60 分钟；在 LSPosed 管理器 → 商店 里按包名搜一次，确认图标/SUMMARY/版本号都对，
   并且**在测试机上验一次"从商店点更新"能不能装上**（商店→安装走的也是签名，
   正式签名和 beta 的调试签名不同，老 beta 用户必须卸载，这条要在说明里写清）。

## 5. 已经帮你写好的文案（可以直接粘）

- SUMMARY（商店首页一行，中英文各一版）：
  - `把小米大师变焦环变成通用旋钮：滚动、音量、亮度、视频进度，全在环上`
  - `Turn the Xiaomi Master Zoom Ring into a universal dial: scroll, volume, brightness, video seek`
- issue 标题：`[submission] io.github.tvtjiesen.omniring`
- Release Tag / Title：`49-1.39.0` / `1.39.0`
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
