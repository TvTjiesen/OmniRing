# OmniRing（万能变焦环）· 工程交接

给接手这个项目的 agent 看的。**这一份是唯一的活交接文件**，读完这一份再动手；
细节在 `README.md`、`MODIFICATIONS.md`、`docs/*.md` 里，下面只标去哪查、不重复抄。

工程：`E:\dev\hzr`（GPL-3.0，fork 自 NextAlone/HyperZoomRing）· 当前 `HEAD` 是
`1.40.0-beta → 1.40.0`（versionCode 51 / 构建戳 `cfg45`）· 装机身份
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
- **现在 `build/` 里那两份还是回落调试签名**：`OmniRing-1.40.0.apk`（md5 `3741a2a3…`）与
  `OmniRing-1.40.0-legacy.apk`（md5 `a006beed…`，测试者/上架用这份）→ **上架前必须重签一次**。
- 商店流程与可抄文案：`docs/LSPOSED-STORE.md`（一手规则来自 `Xposed-Modules-Repo/submission`；tag 必须是 `<versionCode>-<versionName>`，且 **apk 附件要在创建 release 时就传上去**）。
- 包名迁移代价（对外的硬事实）：`1.39` 及之前是 `xyz.nextalone.hyperzoomring`，两个 ID **互不覆盖**；老用户要先在 LSPosed 关掉旧模块再卸载。App 首页有警示卡，装机脚本会备份/还原 prefs。给测试者的那份已经写了这条。

## 8. 未完成的活（含一条业主已提但**还没批设计**的）

**A. 表冠统一逻辑改造（结构性）** —— 业主 2026-11-01 提出：取消 `顺时针/逆时针 × 快转/慢转` 四槽位，改成"动作随表冠转向定正负"。
讨论中他**已拍两条**：① 无方向的动作（启动指定应用）**直接不支持**；② 转速**只影响幅度**、不再决定是哪个动作。
两条 bug 很可能随之消失（"音量/亮度与滑动概率冲突"来自速度阈值上下反复穿越导致两个槽位交替触发；"音量只能降不能升"来自某侧槽位没配）。
我给的方案是 B（新增 `CrownAction(plus, minus)` 一层，底层 16 个动作 id 不动），并给了迁移与测试设计。
**注意：设计还没得到他批准 → 不许开工**（brainstorming 的硬闸门）。重启这个话题时先复述设计让他点头，再写 spec。
按这个改还可能顺带解决 #58/#59/#70 一部分，但那是推断，别当结论。

**B. 待复现/待修的已知问题**（这些都需要业主给一次触发条件，或按 §2 先测量再改一处）：
#42 旋钮小概率回跳/瞬移 · #43 视频进度概率性卡在某一值 · #59 滚动被识别成点击（微博误开条目） · #70 沉浸态容易误触退出 · #72 诊断页事件总数一直为 0 · #78 第三方 App 内需要"真实触摸"信号 · #83 右上角按钮实时模糊（用热调 A/B 判，别猜） · #74 参照 HyperOS 规范重写深色主题 · #12 收尾（性能/残留） · #60 18 个动作逐天真机体检。

**C. 装机验证欠账**：`cfg45` 只做了离线核验（**没在真机上跑过**），真机上现在是 `cfg44`（那版装机验证过：`build=cfg44`、心跳带新签名、`recv` 从开机 NPE 变 `ok`）。插上电第一件事就是跑 §3 的 `deploy_verify.sh`。

**D. 别再做**：宣传图/海报（他叫停了）；性能基线拿 debug 包量（R8 后 4.7 MB vs debug 47 MB， debug 量出来的数没意义）。

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

## 11. Git 与许可纪律

- 只做本地提交，**不 push**（业主明令）；提交信息写"为什么"，带实测证据；不要改他的全局 git 配置。
- `MODIFICATIONS.md` 是 GPL-3.0 §5a 的显著声明，**每轮都要往下追加**（含版本号口径变更）；`LICENSE` 保持 GPL-3.0；上游署名在 README、本文件、App 内「设置 → 致谢」，**商店 README 里也必须留**。
- 包名/权限名这类"身份"变更要在 `MODIFICATIONS.md` 与 README 写清（含迁移代价），别只留在提交信息里。

## 12. 此刻状态快照（2026-11-01 16:40 左右）

- 工作树干净，`HEAD = 69a7526`（`1.40.0 / versionCode 51 / cfg45`）。测试 219 条 0 失败，四语 234/234。
- 业主手机**当前离线**；最后一次确认的状态：装的是 `cfg44` 调试包、新包名、配置已从旧包名 1:1 迁过来、`hzr_snapshot` 里 `module_enabled true`；调试键已清（`hzr_probe`/`hzr_entry`/`hzr_debug` 回读 null）。
- 我在这台机上留下的一处**自己的失误已修复并如实记录**：一次装机脚本里变量名撞了 bash 只读内建 `UID`，把新包的 prefs 写空并改了属主 → 已按"只覆盖内容、保留 inode 与 SELinux 标签"的方式修好，旧包配置全程未动。教训写进 §2/§3：**改共享配置文件的脚本，变量名别用 `UID`/`PATH`/`HOME` 这类只读内建**。
- 待业主做（不需要你）：把 `OmniRing-1.40.0-legacy.apk` 发给酷友测 OS3；上架前双击 `sign_key.bat` 重签；商店 issue 用他的 GitHub 账号开。
- 待你做：他手机插上后第一件事跑 `tools/deploy_verify.sh` 把 `cfg45` 的三条正面证据补齐；表冠统一逻辑**等他把设计点头**再动。
