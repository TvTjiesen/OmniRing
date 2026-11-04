# OmniRing（万能变焦环）· 工程交接

给接手这个项目的 agent 看的。**这一份是唯一的活交接文件**，读完这一份再动手；
细节在 `README.md`、`MODIFICATIONS.md`、`docs/*.md` 里，下面只标去哪查、不重复抄。

工程：`E:\dev\hzr`（GPL-3.0，fork 自 NextAlone/HyperZoomRing）· 当前 `HEAD` 是
`1.40.0-beta → 1.40.0 → 1.40.1`（versionCode **52** / 构建戳 `cfg49`，见 §8D）· 装机身份
`io.github.tvtjiesen.omniring`（**2026-10-31 从上游包名改过来的**，原因见
`docs/LSPOSED-STORE.md`）。

---

## 0. 接手后第一件事（按顺序）

1. `source /e/dev/env.sh`（Android SDK、adb、`python` 都在这套路径里；不 source 就会 `adb: command not found`）。
2. 读 §1、§2 —— 违反这两节的代价是**毁掉业主的日常手机**或者**交出一个看着成功其实没生效的包**。
3. 跑一遍 `./gradlew :app:testDebugUnitTest`（基线 **219 条断言 / 0 失败**）。任何改动之后这个数字只许涨不许跌。
4. 需要装机就先跑 `bash tools/deploy_verify.sh`（**不要手敲命令**，理由见 §3）。
5. 有拿不准的产品/观感问题，去问业主本人，别自己替他定（§9）。

## 1. 绝对不能碰的东西

- **设备只有一台能碰**：`c05bb07c` = 小米 17 Ultra 徕卡版（`nezha`，HyperOS 4 / Android 17 / SDK 37），**他的日常机**。所有 adb 一律带 `-s c05bb07c`。
  同时在线的 `3B162D00B3600000`（一加 Pad）**永远不要动**，也不要"顺手"往上面装包验证。
- **锁屏 PIN 等凭证**：绝不落盘、不进文件、不进对话。装机脚本要解锁时口令走环境变量 `PIN`（`build/deliver.sh` 的做法）。猜/试 PIN 更是不允许。
- **开始用手机就停手**：任何注入、装机、软重启之前先看 `mCurrentFocus`；焦点不是桌面/我们的 App，或他在用，就停。
- **不绕 SELinux**：改 `/data/adb/lspd/config/modules_config.db` 这条路是**死的**（`ksu` 域能 create 不能 unlink/改写）。穷尽之后唯一活路是让业主在 LSPosed 界面里把模块关开一次 —— 那一步属于他的设备操作，说清症状交给他。
- **不可逆操作先问**：卸载 App（会带走 LSPosed 模块记录、作用域、他的配置）、整机重启、换签名、`pm uninstall`、对外发布。
- **凭据不由 agent 持有**：正式签名密钥 `E:\dev\keys\omniring-release.jks`，**口令只有业主知道**。生成/使用流程见 `docs/RELEASE-KEY-HOWTO.md`（都是双击：`tools/make_key.bat`、`tools/sign_key.bat`）。别把它抄进任何文档或对话。
- **别动他别的东西**：`E:\dev\wanzai`、`E:\dev\wanzai-hyperos` 是另一个课题（美化报告），源码一个字不许改。
- **GitHub 先别动**：他说过「github 就别动了」→ 只做本地提交，不 push、不开 PR、不改他的 git 全局配置。
- **不外发**：`shots/`、`mc_backup.db`、日志里的账号信息，不进仓库不外传。宣传图那条线他已经叫停（海报他自己做，我做的几版全否）。

## 2. 验证纪律（本项目最贵的部分，都是踩出来的）

**装机成功 ≠ 模块加载；模块加载 ≠ 加载的是新版。** 两个等号都要各自拿正面证据打破。
三条判据必须**同时**满足才可以说"新代码生效了"：

1. 探针回显 `build=cfgNN`（唯一能证明 system_server 里那份 dex 是新的）：
   `settings put global hzr_probe 8192` → 读 `settings get global hzr_entry`。
2. 心跳被**重新写出来**（先 `settings delete global hzr_heartbeat`，再等它回来且带新安装目录的签名）。
3. `maps` 命中只能算旁证：system_server 自己也会 mmap 已安装 APK 去读资源，命中 ≠ 加载。

假证据黑名单（都被用过、都骗过人）：

- 心跳里的 `base.apk|size|mtime` —— 那是从**磁盘路径** stat 出来的，证明不了加载的是哪份 dex。
- "logcat 里没有它的日志" —— buffer 会滚掉；"搜不到" ≠ "没运行"。要否证"没在跑"用**哨兵法**（先写一个哨兵值，看它有没有被活着的那段代码改写）。
- `settings get` 读到了值 —— `Settings.Global` **跨重启留存**，读到值 ≠ 这一版跑过。
- 探针回显停在旧戳 —— `hzr_entry` 只在探针真跑时才刷新，我读到过 `cfg36` 的陈旧残留并差点判成"装了旧包"。现在装机脚本每次强制逼一次。
- 截图肉眼对比 —— 先确认 `mCurrentFocus`，否则量的是他正在看的别的界面。
- **探针位图有 `cmd == lastCmd` 去重**：连续写同一个值，第二次静默不执行 → 读到的是上一轮的值。要么交替（`8192 → 8193`），要么先 `delete`。写错位图号也会**什么都不发生**。
- `settings put global` 会静默丢 → 每次写完必须回读；软重启后再回读一次。
- `settings delete` 一律回 `Deleted 0 rows` 但值确实删了 → 别信回显，一律 `get` 复读。
- 像素证据的尺子是 **density 不是分辨率**（模拟器要 `wm density 560` 才和 17 Ultra 的 3.5 对齐）；成对证据先 `md5sum`（逐字节相同 = 噪声地板是假的）。
- Compose 界面的正文在 `uiautomator` 里**读不到节点**（真机的 LSPosed 管理器、我们的首页卡都是）→ 不许按肉眼估坐标点它。能读到的只有底栏那几个 label。

## 3. 装机与脚本（有脚本就用脚本，手敲一定漏）

| 脚本 | 干什么 | 关键处 |
| --- | --- | --- |
| `bash tools/deploy_verify.sh`（需 `SER=c05bb07c`） | 装机 + 端到端验收（默认软重启框架） | 内含：空闲判定 → 恢复映射并把**总开关掰回 true**（会打印）→ 装机 → md5 比对 → 等 35 s 让 LSPosed 的 db 追上 codePath → 删心跳 → `kill system_server` → 轮询"带新签名的心跳"（没起来再重启一次）→ **探针逼一次 `build=cfgNN` 回显，对不上直接 die** → 配置通道 → 清调试键 |
| `--no-reboot` | 只装机验收、不重启框架 | 改了模块代码就别用；用了之后**探针戳对不上就是硬错**，别绕过 |
| `bash tools/legacy_bench_verify.sh` | 在自建虚拟机上验"legacy-only 包在老 LSPosed 上真能加载" | 判据 = `entry=legacy` + `legacyHooked=` + `runtime=started=true` 三项一起 |
| `bash build/deliver.sh <apk> <期望build> [串号]` | 交付轮闭环（含 LSPosed 重扫的实测顺序） | 注意它是 **GBK 编码**，改它只能做字节级 ASCII 替换 |
| `python tools/restore_prefs.py <prefs.xml>` | 恢复映射 + 把 `module_enabled` 掰回 true 并明说 | 装机脚本会调它 |
| `python tools/i18n_check.py` | 四语字符串必须齐（当前 234/234） | 加文案后必跑 |

物理事实（不记就会白等几分钟）：`adb install -r` **每次换一个随机安装目录**并删掉旧的，而 LSPosed 的 `apk_path` 要等约 30~35 秒才跟上；**装完立刻重启框架 = 模块静默不加载**。软重启 `kill $(pidof system_server)` 十几秒即可，不必整机重启。HyperOS 锁屏下 `install` 直接报 `INSTALL_FAILED_USER_RESTRICTED`，`am start` 还会假报组件不存在 —— 那不是包坏了。

## 4. 架构不变量（改代码前先读这段）

- **两条模块入口**：`assets/xposed_init → HookEntry`（YukiHook，传统）和 `META-INF/xposed/{module.prop,java_init.list} → CrownModule`（libxposed API 102）。LSPosed 2.2.1 走 102；**老版本 LSPosed（v1.11.0 实测）撞上双声明会一条都不加载**。所以有 `-Phzr.legacyOnly=true` 这条发布车道（排除 `META-INF/xposed/**`）给测试者和商店用。判"走的哪条"看探针 `entry=`。
- **两条入口共用的那半截运行时**在 `InputInterceptorHook.startRuntime()`（传感器监听、ConfigSync、SceneDetector）。新逻辑一律加在**共用段**，别只加在一条路上（历史上"App 内不注手指"只加在硬件路径、漏了传感器路径，误触当场复活）。
- **HyperOS 4 上 `InputFilterEnabled=false`**：环只走光学传感器 `mt350x OPTICALTRACKING`；**HyperOS 3 上输入侧也会收到环事件**，于是两条路各执行一遍 → `ring/RingSourceArbiter.kt` 用**租约（700 ms，fail-open）**仲裁，传感器做主、输入侧只吞事件。别改成"硬关"：假设错了最坏是 700 ms 空窗，硬关错了是整套失灵。
- **配置只能由 App 推**（system_server 读不到 App 私有 prefs，YukiHook 那份快照启动即冻结）：签名级权限广播 + `Settings.Global` 的 `hzr_snapshot` 开机自愈。三条通道一起用，别合并成一条。
- **心跳载荷字段只增不改序**（`util/Heartbeat.kt`，第 8 段是输入侧 `执行数/只消费数`）；分隔符是 `;` 不是 `|`（签名自己带 `|`，用过就错位，有单测钉着）。
- **`Settings.Global` 的 `hzr_*` 键是全设备共享的**：新旧两个包名同时启用会互相盖快照 —— 所以界面上有"检测到旧包名"的警示（`util/LegacyPackage.kt` + 首页卡），别把它删掉。
- 广播 action 与签名级权限名**必须从 `BuildConfig.APPLICATION_ID` 派生**（清单里用 `${applicationId}` 占位符）。写回字面量会让新旧两个包互收配置、权限声明互斥。锁在 `ConfigPayloadTest`。

## 5. 手感参数：定案与规矩

**规矩**：调好的参数**必须烘进代码默认值**，不能只活在 `Settings.Global` 调试键里（键会被清掉、换机会丢，业主原话是"你又搞砸了"）。热调键保留是为了当场 A/B。

已定案（真机调出来的，别"顺手统一"）：应用内表冠 `RingEase.DEFAULT_PX_PER_UNIT = 2.5`（有单测锁，不许退回 1.2）、`hzr_ring_tau_ms` 默认 45；传感器侧 `CrownModel` 的 `deadband 6 / backlash 40 / backlashSpeed 0.03 / backlashCap 110 / maxStepUnits 400`；来回转动靠**单误差直驱**（`err = target − applied`，甩指/惯性/信用瞬移那套整条被删过——别加回来）；撞边必须立刻换指不许压零速。

热调键（`settings put global`，2 秒内生效，不用重装）：`hzr_ring_px_per_unit`、`hzr_ring_tau_ms`、`hzr_ring_lease_ms`（0=关仲裁）、`hzr_fg_stable_ms`（0=关转场闸门）、`hzr_scroll_*`、`hzr_seek_*`、`hzr_scrub_y`、`hzr_glass_*`、`hzr_debug 1`（定向日志）。

**一处待业主判**：他机上 `hzr_scrub_y` 还是 `0.27`，而代码默认与 README 都是 `0.45` —— 没烘也没删，因为没有证据说明 0.27 是被确认过的值。**别自作主张二选一**，问他或按 §2 取证。

## 6. HyperOS 3 适配的真实进度（别提前替它吹）

- 代码侧完成：双入口租约仲裁、相机/转场两道闸门、心跳第 8 段远程判据、首页「技术路径」带计数（`input+sensor 挡N/执M`）。
- 自检验证：`docs/HYPEROS3-SELFCHECK.md`（分"能证死的"和"没机器证不死的"两类，别混着讲）。
- 老框架发布包在虚拟机（A15 + **LSPosed v1.11.0**）实测过加载：`entry=legacy legacyHooked=filterInputEvent,injectInputEvent runtime=started=true build=cfg42`。
- **仍然等业主找酷友给回执**。在拿到正向证据前，`util/Compatibility.kt` 的 `TESTED_MIN_SDK` 保持 **37**（写 36 等于宣称跑过 Android 16，那是虚的），OS3 的适配卡就该显示 WARN。
- 回执不要 adb：一张首页截图就够判双倍执行（`docs/HYPEROS3-TESTER.md` 第一节是可复制转发的话术，第四节才是我们自己看的取证）。

## 7. 发布车道（密钥 / 签名 / 商店 / 迁移）

- 密钥**已生成**：`E:\dev\keys\omniring-release.jks`，alias `omniring`，证书指纹
  `22:2F:C2:90:A5:AD:E9:39:8B:01:00:E9:68:31:27:11:82:86:F5:E1:45:DE:C6:81:B5:7A:E9:89:19:45:BC:1B`
  （公开，可写进发布说明；口令只有业主知道）。复核：`tools\make_key.bat` → `--fingerprint`。
- **打正式包 = 双击 `tools\sign_key.bat`**（弹框输一次口令 → 两份 release → 自己用 `apksigner` 回读并核对指纹，对不上会拦住"别发"）。
- **2026-11-02 20:09 已重签出包**（业主本人双击，口令没过我手）：
  `build/OmniRing-1.40.0-signed.apk`（md5 `b30d4c69080b2725351228267a984557`，444 entries，`META-INF/xposed` 2 条）
  与 `build/OmniRing-1.40.0-signed-legacy.apk`（md5 `70705b8dfb977c251e13e060ca58a4b6`，442 entries，0 条）；
  我独立用 `apksigner` 回读，两份 **V2 signer SHA-256 = `222fc290a5ade9398b0100e9683127118286f5e145dec681b57ae9891945bc1b`（= 正式指纹）**，
  `aapt2` 均 `io.github.tvtjiesen.omniring` / versionCode 51 / 1.40.0 / 「万能变焦环」，dex 戳 `cfg46`。**上架用 legacy 那份。**
- `build/OmniRing-1.40.0.apk`（md5 `3741a2a3…`）与 `OmniRing-1.40.0-legacy.apk`（md5 `a006beed…`）是旧的**调试签名**产物，别再拿去发。
- 商店流程与可抄文案：`docs/LSPOSED-STORE.md`（一手规则来自 `Xposed-Modules-Repo/submission`；tag 必须是 `<versionCode>-<versionName>`，且 **apk 附件要在创建 release 时就传上去**）。
  **2026-11-04 刷新**：versionCode 已抬到 **52 / 1.40.1**（`cfg49` 是行为修复：探针"清 0"协议、反馈包三行、自测按钮连按），
  下一版 tag 为 **`52-1.40.1`**、附件名 `OmniRing-1.40.1-signed-legacy.apk`。
  旧的 `51-1.40.0` 那段记录如下（历史）：
  `assembleRelease` 两种变体都已从当前源码验过能出（`build/rc_dual.apk` / `build/rc_legacy.apk`，dex 戳 `cfg46`），
  正式签名包已于 2026-11-02 20:09 出好（见上一条，md5 `70705b8d…`），**下一步只剩提 issue + 建 release 两个网页操作**。
  商店方图用 `python tools/gen_store_icon.py` 出（`build/store_icon_512.png`，与 APK 自适应图标同一份几何）；
  贴上架的三样文案已生成在 `build/store/`（`SUMMARY` / `README.md` / `RELEASE_NOTES.md`）。
- **2026-11-02 20:21–20:24 商店提交已做完**（agent 用 `gh`、业主账号 `TvTjiesen`、`repo` scope）：
  submission issue [#2407](https://github.com/Xposed-Modules-Repo/submission/issues/2407)（机器人已自动关）→
  仓库 `Xposed-Modules-Repo/io.github.tvtjiesen.omniring` 建成 → 维护者邀请 `341041488` 已 `PATCH` 接受（权限 `pull` → `maintain+push`）→
  `SUMMARY`(99 B) / `README.md`(1809 B) 已推（未传源码）→ Release tag **`51-1.40.0`** / title `1.40.0` /
  附件 `OmniRing-1.40.0-signed-legacy.apk`（4,722,279 B，`state=uploaded`）。
  **唯一没做完的**：仓库描述（About → Description `OmniRing 万能变焦环`、Website 源码地址）**只能浏览器里填**，
  REST `PATCH /repos/…` 因 maintain≠admin 回 404，机器人公告也写明"API 没权限、必须在浏览器做、不填描述就永远不出现"。
  **→ 2026-11-02 20:36:58 业主已在浏览器填好**（`description = "OmniRing 万能变焦环"`、`homepage = 源码地址`，agent 回读确认）⇒
  机器人两个必要条件（描述非空 + 至少一条 release）**全部满足，60 分钟倒计时从 20:36:58 起算**。
  踩坑记录：业主第一次填到了**源码仓库** `TvTjiesen/OmniRing`（不是商店读的那个组织仓库），两处描述要分开填。
  验收只能在他手机上做（LSPosed 管理器 → 仓库 → 搜 `omniring`）；本机到 `modules.lsposed.org` 被 TencentEdgeOne 回 **403 空体**，
  换 allorigins / r.jina.ai 代理也不通 ⇒ **agent 侧无法直读商店 JSON**。
- **HyperOS 3 一句话口径**：代码侧 9 条能证死的已证死（`docs/HYPEROS3-SELFCHECK.md` §1），
  但**没有任何 OS3 真机正向证据**，所以 `TESTED_MIN_SDK` 保持 37、适配卡在 OS3 显示 WARN/部分适配。
  商店 README 只写「HyperOS 4 已实测」，别替 OS3 吹。
- 包名迁移代价（对外的硬事实）：`1.39` 及之前是 `xyz.nextalone.hyperzoomring`，两个 ID **互不覆盖**；老用户要先在 LSPosed 关掉旧模块再卸载。App 首页有警示卡，装机脚本会备份/还原 prefs。给测试者的那份已经写了这条。

## 8. 未完成的活

**A. 表冠统一逻辑改造（结构性）** —— 业主 2026-11-01 提出：取消 `顺时针/逆时针 × 快转/慢转` 四槽位，改成"动作随表冠转向定正负"。
讨论中他**已拍两条**：① 无方向的动作（启动指定应用）**直接不支持**；② 转速**只影响幅度**、不再决定是哪个动作。
两条 bug 很可能随之消失（"音量/亮度与滑动概率冲突"来自速度阈值上下反复穿越导致两个槽位交替触发；"音量只能降不能升"来自某侧槽位没配）。
我给的方案是 B（新增 `CrownAction(plus, minus)` 一层，底层 16 个动作 id 不动），并给了迁移与测试设计。
**✅ 2026-11-02 这条链已走完**：设计第一版 `docs/CROWN-ACTION-V2-DESIGN.md`（提交 `f685b65`）→ 他回
「全部听你的」= 设计稿 §8 六个待拍板点**全按我的推荐批准** → 实现 + 自测 + 真机验收（提交 `09a2e38`，
`cfg46` 在 system_server 里跑通，细节见 `MODIFICATIONS.md` 第十四批）。
最终口径：无方向动作（启动应用）**从选择器消失**；转速**只喂 `currentIntensity` 幅度**、不再选槽位；
离散累计抽成 `ring/DiscreteAccum.kt` 且**方向翻转即清零**；旧四键**只读不删**（回落链
`PLUS→CW_SLOW→CW_FAST`、`MINUS→CCW_SLOW→CCW_FAST`），`launch_app` 读时归一成 `scroll`。
**唯一没验的**：App 两槽位界面与一次性合并提示的**肉眼**确认（截图拍到的都是锁屏/通知栏）——请业主看一眼手机。
按这个改还可能顺带解决 #58/#59/#70 一部分，但那是推断，别当结论。

**B. 待复现/待修的已知问题**（这些都需要业主给一次触发条件，或按 §2 先测量再改一处）：
#42 旋钮小概率回跳/瞬移 · #43 视频进度概率性卡在某一值 · #59 滚动被识别成点击（微博误开条目） · #70 沉浸态容易误触退出 · #72 诊断页事件总数一直为 0 · #78 第三方 App 内需要"真实触摸"信号 · #83 右上角按钮实时模糊（用热调 A/B 判，别猜） · #74 参照 HyperOS 规范重写深色主题 · #12 收尾（性能/残留） · #60 18 个动作逐天真机体检。

**C. 装机验证欠账：✅ 已清（2026-11-02）**。`cfg45` 从未上过他的手机，它被 `cfg46` 直接取代：
`bash build/make_and_run.sh`（绕 §10 的环境变量坑，串号写死）跑完 `tools/deploy_verify.sh`，退出码 0，
三条正面证据齐全——**装机 md5 一致** `e150334568322a3ed77f898a9ac8e91e`、**`system_server` 重启后心跳带新签名**
`base.apk|47158549|1793595760235;…`、**探针回显 `build=cfg46`**；配置推送通道也验了（`hzr_map` 回显映射、
`hzr_cfg` 回显 `pushed=1 keys=12 … reg=ok`）。设备 prefs 里新两键 + 旧四键全 `scroll`，`legacy_notice_shown`
未写 ⇒ 下次启动 App 会弹一次性合并提示。

**D. 别再做**：宣传图/海报（他叫停了）；性能基线拿 debug 包量（R8 后 4.7 MB vs debug 47 MB， debug 量出来的数没意义）。

## 8D. 本批新增的两个功能（`cfg47` → `cfg48` 真机验证过）

- **作用域自动勾选**：新增 `app/src/main/resources/META-INF/xposed/scope.list`（`system`）。
  LSPosed 对 modern 模块只用这个文件当"推荐作用域"，没有它就只能手工勾「系统框架」。
  `res/values/xposed_scope.xml` 是 legacy 车道用的，保留不动。
  ⚠ 只在"当前勾选为空"时生效（`ScopeAdapter` 的 `emptyCheckedList` 分支），
  所以**老装机看不出变化，要卸载重装才能验证**——这条至今没验过。
- **反馈包导出**：`util/DiagReport.kt` + 诊断页两个按钮（复制 / 存到 `下载/OmniRing/`）。
  三条真机踩出来的硬事实（**别再退回旧写法**）：
  1. **App 侧 `Settings.Global.put*` 一律静默失败**（普通应用 uid 没有 `WRITE_SECURE_SETTINGS`，
     `runCatching{}.isSuccess` 恒为 true）。所以 `DiagReport.writeGlobal` 必须走
     `su -c "settings put global …"` 并**回读校验**，不能只看调用没抛异常。
     同一个 bug 也让诊断页「运行注入自测」一直是坏的（写 `hzr_probe` 无效果），
     `cfg48` 改成 `DiagReport.requestProbe(context, 32L)` 才修好。
  2. 探针是**边沿触发**（`DebugProbe.tick`），而 `hzr_entry`/`hzr_cfg`/`hzr_map`
     **只在探针触发那一刻写**。反馈包因此要先清 0 → 等一拍 → 写位图 8832（128|512|8192）
     → 等三拍让探针跑完，否则三个键是过时快照（真机第一版跑出来三个键全空）。
     **这里有两处必须同时成立，少一处就静默失效**（`cfg49` 才补齐，详见 §8D-补）：
     - `tick` 读到 0 时必须**把 `lastCmd` 也清成 0**。老写法
       `if (cmd == 0L || cmd == lastCmd) return` 读到 0 直接 return、不更新记忆，
       于是"清 0 再写回同一个位图"整招是假的。
     - 清 0 与写位图之间必须**跨过探针轮询周期**（`PROBE_INTERVAL_MS` = 2000ms，
       `ZoomRingSensorListener.kt:23`）。间隔只有 400ms 时，0 与位图落在同一个 2 秒窗口里，
       那一拍只看得到位图，"清 0"等于没发生。现用 `PROBE_RESET_GAP_MS = 2400L`。
  3. logcat 用 `-b main,system -s <tag>` 直取并按 tag 筛，**不要抓 4000 行大缓冲再筛**：
     `-t 4000` 只覆盖最近十几秒，模块开机日志早滚掉了，剩下的全是 uiautomator 噪声。
     crash 缓冲**不能整段留**（第一条可能是别的 App 的 native tombstone，几十行寄存器就把
     800 行预算吃光），要用 `keepCrashLine` 白名单只挑"哪崩了 + 为什么"。
- 真机验收过的（2026-11-03 20:34，`cfg48`）：反馈包 16,580 B，头 15 行齐全
  （`hzr_entry=…build=cfg48 recv=ok reg=ok`、`hzr_cfg=… PLUS=scroll reg=ok`、
  `hzr_map=PLUS:scroll|MINUS:scroll`、`enabled=true dispatch=GLOBAL speedThreshold=5`、
  `map.PLUS=scroll`、`legacy_slots=true`），`kept=146`，uiautomator/chatty **0 行**；
  自测按钮点击后 `settings get global hzr_probe` 从 8832 变 32（真进程写进去了）。

### 8D-补：`cfg49` 修掉了"清 0 不起作用"（2026-11-04）

11-03 那次验收是**碰巧成功**的：`hzr_probe` 当时是别的值、位图与上一轮不同，
所以第一次就触发了。11-04 复测立刻暴露问题 —— 反馈包里 `hzr_cfg=pushed=0 PLUS=-`、
`hzr_map=PLUS:-|MINUS:-`，而 `hzr_cfg_state` 明明是 `2;17;…`（配置早推成功了）。
手工复现：`settings put global hzr_probe 0` → 等 1s → 写 8832 → `hzr_cfg` **一动不动**。

两处一起修（缺一不可）：
- `hook/DebugProbe.kt`：读到 0 时 `lastCmd = 0L` 再 return；边沿判定抽成
  `internal fun shouldRun(cmd, last)` 便于测试。
- `util/DiagReport.kt`：`PROBE_RESET_GAP_MS` `400L` → **`2400L`**（跨过 2 秒轮询），
  `PROBE_WAIT_MS` `4600L` → `6000L`；`requestProbe`（自测按钮）也补上了同一个间隔 ——
  它原来写 `写0 && 立刻写32`，**第一次按能通、第二次按就再没反应**。
- 新增 `app/src/test/kotlin/…/hook/DebugProbeEdgeTest.kt`（4 条）钉住语义：
  `zeroIsIdleNotACommand` / `sameValueTwiceOnlyRunsOnce` / `resetThenSameBitmapRunsAgain`
  / `differentBitmapRunsWithoutReset`。

验收：单测 **39 XML / 248 断言 / 0 失败**；四语 244 键；装机端 md5 与本地一致
（`b2f13d2b57441b12ac99d514cc2f6e25`），`kill -9 $(pidof system_server)` 后
`build=cfg49`；真机连做两轮「清 0 → 等 3s → 写同一个 8832」，`hzr_cfg` 的 `ageMs`
从 `16653` 变到 `26666`（两轮都真跑了），修复前它恒为 `-1`。

## 9. 和业主协作的方式（他明确表达过的偏好）

- 结论先说、直白；**别乱改他的东西**，改了要说改了什么、为什么。
- 长活**实时报进度**，别让他问"干得咋样了"；一个回合推到最远状态，别老说"下一轮"。
- 多要求任务先列显式清单，逐项标 ✅/◐/⬜，不许口头"都做了"却漏项。
- 工程岔口要给**带理由的推荐**，不是摊一堆选项让他选。
- 缺信息不是停手理由：能自己逆向/实测拿到的必须自己拿到；拿不到就说清缺哪条证据。
- **需要他本人拍板的四类闸门**：装真机、凭据、不可逆操作、观感与手感。其余做完就进下一条。
- 手感/观感只能他判（"跟不跟手"这种不要替他下结论）；他习惯"我打算用/直接不支持"这种一句话拍板，听到就照办、别复议。
- 交付要落到最终环境复验；证据链要写清"我实际验了什么"，没验的就写"没验"（他把这个看得比进度还重）。

## 10. 环境与工具事实（省下几小时）

- `source /e/dev/env.sh` 之后才有 adb/python/gradle 的正确路径；`python` 在 `/e/dev/bin`，Windows 那个 `python` 是商店占位（会 Permission denied）。
- `adb.exe` 是 Windows 程序：**读不到 Git Bash 的 `/tmp`**，push 用的临时文件要放 `E:/dev/hzr/build/`；命令行里的 `/sdcard/...` 会被 MSYS 改写成 `C:/Program Files/Git/...` → 一律 `MSYS_NO_PATHCONV=1`。
- `aapt`/`adb` 不吃中文路径与 `/e/...` 形式：先拷到 ASCII 的 `E:/...`。
- `adb → su -c → sh` 是**三层引号**，设备侧命令里再放单引号会被静默截断 → 复杂命令写成脚本 push 上去再 `sh` 跑。
- `pidof` 一类取值要整体加引号（`out "pidof system_server"`），否则空 PID 静默 kill 空跑。
- Gradle：`--offline` + 仓库根 `localmaven/`（这台机到 `dl.google.com` 超时）；`local.properties` 必须正斜杠；AGP 拒绝中文工程路径；release 的 lint 已关（`checkReleaseBuilds=false`），验收靠真机实测不靠 lint。
- 自建虚拟机 `bench_a15`（A15/x86_64/Magisk + LSPosed v1.11.0）：启动要 `ANDROID_AVD_HOME="E:/dev/avd_home"` + `nohup ... &`；**adb 拿不到 root**（`su` 被拒），所以只能整机重启（`adb reboot` 会把 emulator 进程带走，要再手动拉起）；没有变焦环传感器，日志里 `Gave up waiting for SensorService` 是预期。它的用途就一个：模拟"老版本 LSPosed"。
- PowerShell 侧两个坑（都踩过）：`.ps1` 必须 **UTF-8 BOM**，否则中文注释让解析器崩；原生 exe 的 `-storepass:file` 这类带冒号参数要用**数组 splat**，直接写会"意外的标记"。

- **DSH 侧 pinned bash 缺 `cygpath`/`md5sum`（2026-11-02 实测，会假装成功）**：那套 MSYS2 的
  `/usr/bin` 里两样都没有，直接跑 `./gradlew` 会 `cygpath: command not found` → `exec: : not found`，
  **可退出码仍是 0**（管道把状态喂给了 `tail`）——看着 BUILD 成功，其实一条测试都没跑。
  修法一行：`export PATH="/e/dev/msys64/usr/bin:$PATH"`（`cygpath`/`md5sum`/`sha256sum` 都在那）。
  跑基线务必 `:app:testDebugUnitTest --rerun`，再读
  `app/build/test-results/testDebugUnitTest/*.xml` 里的 `tests/failures` 计数；
  `UP-TO-DATE` 的 `BUILD SUCCESSFUL` **不是**证据。本次实测 `tests=219 failures=0 errors=0 skipped=0`。

- **DSH 的 win_bash 通道不把环境变量传给子进程（2026-11-02 实测，浪费了一小时）**：我敲的命令里
  `VAR=value cmd` 前缀会被通道吃掉；脚本内 `export VAR=…` 只对**同一个** shell 有效，
  fork 出去的子进程一概拿不到（连 `PATH` 前缀也丢：探针打印 `3 forked child SER=[]`）。
  `( exec env VAR=v bash -c 'echo $VAR' )` 能传（B2），`( exec bash -c … )` 不能。
  ⇒ 凡是要给脚本传参（`SER=…`）、或要改子进程 PATH 的，**别指望环境变量**：
  用 `bash build/make_and_run.sh` —— 它把 `tools/deploy_verify.sh` sed 成
  `build/deploy_verify_run.sh`（串号写死、`python` 换成 `/c/Users/*/AppData/Local/Python/bin/python` 绝对路径）再跑。
- `adb pull`/`push` 的本地路径要写 **Windows 形式**（`E:/dev/hzr/build/x.xml`）；`/e/...` 会
  `cannot create file/directory: No such file or directory`。`adb shell` 里的 `/sdcard/...` 照旧配 `MSYS_NO_PATHCONV=1`。
- **applicationId ≠ Kotlin namespace**：真启动项是
  `io.github.tvtjiesen.omniring/xyz.nextalone.hyperzoomring.ui.MainActivity`；
  写 `$PKG.ui.MainActivity` 会 `Error type 3 ... does not exist`（`tools/deploy_verify.sh` 里那两处
  `am start` 因此静默失败很久，2026-11-02 已修成 `$ACT`）。`cmd package resolve-activity --brief $PKG` 可查。
- 取证小坑：屏幕 `mWakefulness=Dozing` 或 `mCurrentFocus=…NotificationShade` 时，`screencap` 拍到的
  是锁屏/通知栏而不是 App；先 `input keyevent 224` + `input keyevent 4` + `cmd statusbar collapse`，
  并且**别去输锁屏 PIN**。

- **MSYS 会把 `adb push <本地> /data/...` 的目标改写成 `E:/dev/msys64/data/...`**（2026-11-03 踩了一小时）：
  症状是 `remote secure_mkdirs() failed: No such file or directory`，**而 adb 同时打印
  "1 file pushed"**（看着成功，其实写进了不存在的目录）。
  `MSYS_NO_PATHCONV=1` 只在"当行前缀"时可靠；`export` 到 shell、套 `timeout`/`eval`/函数、
  以及 `MSYS2_ARG_CONV_EXCL` 都可能失效。**最稳的办法是绕开 bash**：
  用 Windows 原生 `python.exe` + `subprocess.run([ADB, "-s", <serial>, "push", local, remote])`，
  参数不过 MSYS，路径原样到达（现成脚本 `build/push_chunks.py`，只在本地产物目录里，不入库）。
- **`adb install` 在长线路上会写出坏包**：2026-11-03 20:36 那次 streamed install 报 Success，
  但设备上 `base.apk` 的 md5 与本地不同（`ae05aae0…` vs `1cd70569…`），App 一启动就
  `ClassNotFoundException: androidx.startup.InitializationProvider` +
  `Bad checksum (419d6781, expected 57376778)`。**装完必须核对设备上 base.apk 的 md5**
  （`su -c 'md5sum $(pm path <pkg> | sed s/package://)'`），别信 `Success`。
- 同一条链路上 `adb push` 超过 ~4 MB 就容易把 USB 链路拉挂（`device offline` / 设备从
  `adb devices` 彻底消失，Windows PnP 里 `USB\VID_18D1&PID_4E11\C05BB07C` 也随之不见）。
  恢复只能靠业主重新插线；`kill-server`/`start-server` 在设备已从 PnP 消失时无效。
- **坏包会把整个 LSPosed 拖死（2026-11-04 事故，业主报"连lsp都炸了"）**：lspd 在
  `LSPSystemServerService: starting new bridge` 时解析已装模块的 APK，碰到 dex 校验和损坏的包
  就在 `libdaemon.so` 的 JNI 里 `Fatal signal 11 (SIGSEGV, SEGV_ACCERR)`，1.5 秒内 exit 139，
  `modules_*.log` 只留一行 `----part 1 start----`。症状看起来像 LSPosed 自己坏了（`pidof lspd` 恒空、
  system_server 反复 `E LSPosedBridge: service is dead`），实际上是 Device 上那个 md5 不对的包。
  **判据**：坏包在设备上的 md5 与本地产物不符 + App 一启动就 `Bad checksum`。**修法**：换回好包
  （装完核对 md5）→ 还原 `modules_config.db{,-wal,-shm}` → 整机重启。取证细节见
  `build/lsposed_crash_forensics.md`（本地产物，不入库）。
  **排查套路（省时间）**：① `su -c 'pidof lspd'` 空 ⇒ 框架没在跑；② 找
  `/data/adb/lspd/log/daemon-*-crash-*.log` 看 `Fatal signal` 与 backtrace 落在哪个 `.so`；
  ③ 手动 `su -c '/data/adb/modules/zygisk_lsposed/daemon --force &'` 能起来且不崩 ⇒ 不是 daemon 二进制坏了，
  是"某个输入"坏了；④ 再 `kill -9 $(pidof system_server)` 复现（崩溃点只在 bridge 建立时）。
  ⑤ DB 里把怀疑模块 `enabled` 置 0 可做二分（改前先备份 `db{,-wal,-shm}` 三个文件，改完要还原）。


## 11. Git 与许可纪律

- **推送规则已变（2026-11-02 业主授权）**：原来"只做本地提交、不 push"；现在**业主明确说过"直接发布上传github吧"**，
  推送命令必须走显式 443 端口（本机 `.gitconfig` 有 `insteadOf` 把普通 push 改写成 gh-proxy 镜像，会报
  `No anonymous write access`）：
  `git -c credential.helper= -c credential.helper='!f() { echo username=x-access-token; echo "password=$(gh auth token)"; }; f' push https://github.com:443/TvTjiesen/OmniRing.git main`
  —— token 不进命令行、不落盘。**每次推送前照例跑一遍 PIN 自查**（工作树 + `git rev-list --objects --all` 全 blob）。
  但"发版"（建 release / 改版本号）仍要业主当次点头。
- `MODIFICATIONS.md` 是 GPL-3.0 §5a 的显著声明，**每轮都要往下追加**（含版本号口径变更）；`LICENSE` 保持 GPL-3.0；上游署名在 README、本文件、App 内「设置 → 致谢」，**商店 README 里也必须留**。
- 包名/权限名这类"身份"变更要在 `MODIFICATIONS.md` 与 README 写清（含迁移代价），别只留在提交信息里。

## 12. 此刻状态快照（2026-11-04 11:40 左右）

- 工作树：`versionCode 52 / versionName 1.40.1`、`BUILD_TAG cfg49`；单测 **39 XML / 248 断言 0 失败**，四语 **244/244**。
- 设备 `c05bb07c` 在线且已解锁，装的是 **debug 包 1.40.1**（md5 `986e30fb8eb59bdc66032df1fd1a249f`，装机端已回读核对一致）。
- `cfg49` 真机端到端**已验完**（自测按钮连按两次都有 `PROBE start`、反馈包三行非空、crash 段只剩 10 行
  `DeadSystemException`、logcat 噪声 0 行）——详见 `MODIFICATIONS.md` 第二十一批。
- **发布 1.40.1 卡在一步**：`assembleRelease` 出来的 `app-release.apk`（4,744,848 B / md5 `acbfa06a81e6077f6b1c021e8fc4ad43`）
  仍是**回落调试签名**（`CN=Android Debug` / `8785e490…`），要业主双击 `tools/sign_key.bat` 才会出
  `build/OmniRing-1.40.1-signed.apk` 与 `-signed-legacy.apk`；签好后建 release tag **`52-1.40.1`**、附件用 legacy 那份。
- 老 `51-1.40.0` 那次提交记录见 §7（历史），**商店是否已收录仍只能业主手机上搜 omniring 确认**。

## 13. 历史快照（2026-11-02 13:10，已被 §12 取代）

- 工作树干净；提交链 `09a2e38`（**表冠统一逻辑 v2 实现**，四槽位→两槽位）← `f685b65`（设计稿）
  ← `9f75343`（§10 环境坑）← `67c9196`（交接文档），本轮再追加一个文档/工具提交（P2 验收证据 +
  `deploy_verify.sh` 活动类名修复 + §10 新坑）。单测 **239 条 0 失败**（基线 219），四语 **235/235**。
- 业主手机**在线**：`c05bb07c`（nezha / 25128PNA1C / SDK 37，KernelSU root）。**真机上早已不是 cfg44**：
  2026-11-02 13:05 装上并验过 **`cfg46`** 调试包（md5 `e150334568322a3ed77f898a9ac8e91e`、探针 `build=cfg46`、
  心跳带新签名）；设备 prefs 里新两键 + 旧四键全 `scroll`，`legacy_notice_shown` 未写 ⇒ 下次启动 App 弹合并提示；
  `hzr_scrub_y=0.27` 未动。
- **没验的一项**：App 两槽位界面与合并提示的**肉眼**确认（本轮截图拍到的都是锁屏/通知栏，
  当前模型也没有图像输入能力）。
- 待业主做：① 给 `hzr_scrub_y` 一句话定案（我的建议：他机 0.27 与代码默认/README 0.45 **两者都保持现状**）；
  ② 决定是否重打 `build/OmniRing-1.40.0*.apk`（正式签名要他双击 `sign_key.bat`，口令只有他知道）；
  ③ 老样子：`OmniRing-1.40.0-legacy.apk` 发酷友测 OS3、商店 issue 用他账号开。
- 待你做（下一步候选）：§8B 的 bug 复现（多数要业主给触发条件）；HyperOS 3 回执跟进；
  两槽位界面肉眼复验（等业主）。
- 历史（2026-11-01 16:40，已被上面取代）：HEAD `69a7526` / 装 `cfg44` / 测试 219 条 / 四语 234；
  以及那次 `UID` 只读内建撞名事故的修复记录（教训仍在 §2/§3：改共享配置文件的脚本，
  变量名别用 `UID`/`PATH`/`HOME` 这类只读内建）。
