# 改动说明（GPL-3.0 §5a 要求的显著声明）

本仓库是 **NextAlone/HyperZoomRing** 的二改版本。下列文件由本次二改**新增或修改**，
改动日期为 **2026-10-11 ~ 2026-10-12**，作者：**TvTjiesen**。原始版本见 `README.upstream.md`，
完整原始许可见 `LICENSE`（GPL-3.0，保持不变）。

二改版在界面上的名字是 **万能变焦环 / OmniRing**（含新启动图标）。
应用 ID 在 **2026-10-31（1.40.0-beta / cfg43）从上游的 `xyz.nextalone.hyperzoomring` 改为
`io.github.tvtjiesen.omniring`**（改动原因与迁移代价见 `docs/LSPOSED-STORE.md` 和本文件第十二批）；
Kotlin 的 `namespace`、源码目录与上游原文件的包声明保持 `xyz.nextalone.*` 不变 ——
改的只是"装机身份"，不是"代码署名"。上游署名仍在本文件、`README.md` 与 App 内「设置 → 致谢」。

代码占比（`tools/code_share.py`，2026-10-12 复算，76 个 .kt / 9493 行）：
本 fork 新增 32%、移植 Miuix 液态玻璃 15%、上游文件被重写 13%、上游原样保留 38%。
上游的署名放在 App 内「设置 → 致谢」和本文件、`README.md`，不再占用界面首屏。

## 为什么要二改

HyperOS 4 把 `dumpsys input` 里的 `InputFilterEnabled` 强制置为 `false`，Android 因此
不再调用 `InputManagerService.filterInputEvent` —— 而上游模块**唯一**的硬件事件捕获点
就是它。结果：上游 13 个动作在这台机器上全部失效，且写 `settings put global
input_filter_enabled 1` 也救不回来（ROM 层忽略）。本次二改把触发源整体换成
mt350x 光学追踪传感器的数据流，绕开输入管线，并在此之上补齐了"表冠式滚动"和
"视频进度环"。

## 新增文件

### 算法与调度（全部是不依赖 Android 的纯类，能在 PC 上跑单测和真机样本回放）

| 文件 | 作用 |
| --- | --- |
| `ring/CrownModel.kt` | 表冠模型：停手重锚定（传感器参考点会归零）、回差棘轮、按真实时间衰减的峰值速度 |
| `ring/ScrollTracker.kt` | 滚动的纯运动部分：单误差直驱 `err = target − applied`，含防误触落指门槛与补位移 |
| `ring/ScrollModeModel.kt` | 滚动的里程表：带符号累计转幅 × 增益 = 注入端要追的绝对目标（方向只跟实际转向走） |
| `ring/ActionRouter.kt` | 每个动作走哪条通道（连续滚动 / 进度 / 拖进度条 / 离散）的纯判断，全量单测钉住；删掉的 `scroll_up`/`scroll_down` 旧存档 id 也在这里兜回滚动通道 |
| `action/InjectionBudget.kt` | 触摸注入的闸门：只数**连续**失败、到上限只冷却 5 秒。旧版是终身累计 `failures>=8` 永久停用，就是他说的"有概率丢失触控、表冠和触摸一起死" |
| `ring/ScrubModel.kt` | 绝对目标进度求对（会话基准 + 节流 + 钳位） |
| `ring/ScrubHoldModel.kt` | "按住不放"式进度拖拽的偏移模型 |
| `ring/SeekGate.kt` | 这一把该调进度还是该滚动（横竖屏 / 播放中 / 暂停 / 竖屏白名单） |
| `hook/SensorRingDriver.kt` | 传感器驱动的调度器（取代上游依赖输入事件的链路） |
| `hook/DebugProbe.kt` | `Settings.Global hzr_probe` 位图诊断通道：不转环也能自测浮窗 / 会话 / 注入 / 配置同步 |
| `action/actions/ScrollAction.kt` | 滚动动作 + 触摸合成注入器（`TouchScrollInjector`、`InjectBridge`） |
| `action/actions/MediaScrubAction.kt` | 视频/音频进度环（表冠微调，绝对 seek） |
| `action/actions/ScrubDragAction.kt` | 横向拖播放器自己进度条的通道（无 MediaSession 时用） |
| `hud/CrownHud.kt` | system_server 内的进度浮窗（异常即永久自闭，不影响主功能） |
| `media/MediaSessionBridge.kt` | 系统进程里访问 MediaSession 的反射桥（`MediaSessionManager` 在 system_server 内不可用） |

### 配置同步（"改了设置没反应"的修法）

| 文件 | 作用 |
| --- | --- |
| `config/ConfigPayload.kt` | 整份配置的线格式（三段：类型 / 键 / 值，用制表符分隔）+ 通道常量；解码坏行只跳过、绝不抛 |
| `hook/ConfigSync.kt` | 钩子侧接收器：签名级权限挡住伪造，收到就整体换掉生效的 ConfigManager；开机主动回要三次 |
| `util/ConfigPush.kt` | App 侧：写盘即推（防抖 250ms）、开机被问就答、进界面再推一次 |
| `util/Heartbeat.kt` | system_server 侧心跳：把"模块是否真在跑、样本数、最近动作、注入成败"写进 Settings.Global（正面证据） |
| `util/ModulePath.kt` | 在 LSPosed 自定义 classloader 下解析出本模块 APK 路径（`codeSource` 是 null） |
| `util/StatusJudge.kt` | 生效判定的纯函数 + 迟滞去抖（有单测），界面只负责翻译成人话 |
| `util/ScopeReload.kt` | 判断模块 APK 是否比 system_server 里已加载的新，并软重启框架使其生效 |

### 界面

| 文件 | 作用 |
| --- | --- |
| `ui/GlassBar.kt` | 液态玻璃悬浮底栏（接官方 iOS 液态玻璃导航条） |
| `ui/liquid/**` | 从 Miuix 官方示例移植的 iOS 液态玻璃实现（透镜折射、内阴影、vibrancy、组合 backdrop、阻尼拖拽）；本二改另加了"拖滑块时整条底栏跟着形变" |
| `ui/liquid/VeilWindow.kt` | 顶栏渐变纱的 AGSL 竖向 alpha 软窗口；系统不支持 runtime shader 时自动跳过 |
| `ui/liquid/SmearMath.kt` | 底栏形变的纯函数（拉长 3%、压扁 1.5%、回零、对称、连续性均有单测） |
| `ui/IosChrome.kt` | iOS 顶栏外壳：大标题作为列表首项 + 三段重叠软窗口的**真渐变模糊** + 死区渐进出现 |
| `ui/RestartControl.kt` | 右上角液态玻璃重启钮（按压缩放 + 拖拽橡皮筋 + 弹簧回弹）与 iOS 底部动作卡 |
| `ui/CrownArt.kt` | 表冠的矢量画法（仪表刻度环 + 滚花旋钮本体），不再靠缩放启动图标 |
| `ui/CrownStage.kt` | 表冠沉浸态：点设置页头图放大居中，转动物理变焦环直接旋转它，每 30° 一次段落脉冲，收不到环事件降级为手指旋转 |
| `ui/InteractiveCrown.kt` | 可拧的表冠头图（极角增量驱动，松手弹簧回零位） |
| `ui/AppTheme.kt` | 主题设置：深浅色 / 主题色 / 配色方案 / 调色板风格，接 Miuix 的 `ThemeController` |
| `ui/Labels.kt` | 枚举显示名改走字符串资源（原来硬编码中文，切英文会漏翻）；动作的一行说明也在这里 |
| `ui/screen/Page.kt` | 页面骨架：列表从玻璃底栏下方穿过；条目交错浮入 |
| `ui/screen/StatusCard.kt` | 首页「是否生效」卡：分层判定 + 迟滞去抖 + 配置同步状态 + 未生效时给下一步操作 |
| `ui/screen/GestureListScreen.kt`、`SettingsScreen.kt`、`ThemeScreen.kt`、`CreditsScreen.kt` | 手势独立入口；设置页（主题/语言/重启/关于/致谢）；主题与致谢二级页 |
| `util/DeviceInfo.kt`、`util/Links.kt` | 机型/ROM 读取（getprop，全 runCatching）；仓库与酷安等链接常量 |

### 资源与工具

| 文件 | 作用 |
| --- | --- |
| `res/values*/strings.xml`（zh / en / ru） | 全部界面文案，216 键 × 三份，`tools/i18n_check.py` 校验对齐 |
| `res/drawable/ic_launcher_*.xml`、`mipmap-anydpi-v26/` | 新启动图标（表冠滚花 + "C" 内环 + 红点），含 Android 13+ 单色层；`tools/gen_icon.py` 生成、`tools/preview_icon.py` 预览 |
| `tools/*.py` | 生成/预览图标与顶栏渐变方向、代码占比统计、三语言对齐检查、截图位移互相关、真机 SMP 日志逐方向统计 |
| `app/src/test/kotlin/**` | 112 个 JVM 单测：真机样本离线回放、防误触不变量（含 120 组随机序列）、滚动方向语义、动作路由全量体检、生效判定与迟滞、心跳载荷解析、配置编码往返、底栏形变回零/对称/连续性 |

## 修改的文件

- `app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`：
  Miuix 0.8.8 → 0.9.2（拆分成 ui/core/blur/icons/preference）、compileSdk 36 → 37、
  钉住 `kotlin-stdlib-common` 版本以便完全离线构建。
- `app/src/main/AndroidManifest.xml`：新增 **signature 级**的 `PUSH_CONFIG` 权限
  （配置推送通道的防伪门），并记录 `xposedminversion` 为什么必须是 93。
- `HookEntry.kt`：`loadSystem` 里的 prefs 降级为"开机兜底快照"，启动时拉起 ConfigSync。
- `action/ActionRegistry.kt`：注册滚动与进度类动作。
- `action/actions/MediaAction.kt`：seek 从"相对当前位置跳"改为"绝对目标定位"，改走反射桥。
- `config/ConfigManager.kt`：新增进度环总开关（默认关）、灵敏度、浮窗开关、竖屏视频白名单；
  加了 `fromStore()`；**去掉分应用包名列表的进程内缓存**（缓存一次就再也刷不掉，
  和"改了没反应"是同一类 bug）。
- `hook/InputInterceptorHook.kt`：配置对象改为可整体替换（`replaceConfig`）、暴露
  `appContext`；相机拦截读**当前**配置而不是 `hook()` 当时的参数快照。
- `hook/SensorRingDriver.kt`：滚动目标改由 `ScrollModeModel` 给；分发通道改走 `ActionRouter`。
- `hook/SceneDetector.kt`：新增带缓存的横竖屏判定。
- `hook/ZoomRingSensorListener.kt`：样本转发给新调度器 + 2 秒诊断/心跳节拍。
- `ui/MainActivity.kt` 及 `ui/screen/*.kt`：整套界面按 Miuix 0.9.2 的 Preference 组件重写，
  五格玻璃底栏、每 tab 独立返回栈、二级页返回不重置滚动位置。
- `ui/screen/GestureConfigScreen.kt`：动作选择列表带上"这条到底怎么动"的一行说明。

## 实测踩到的系统限制（给后来人，别再走一遍）

1. **HyperOS 4 掐死输入捕获**：`InputFilterEnabled=false` → 上游唯一的硬件事件入口
   `InputManagerService.filterInputEvent` 根本不被调用；`settings put global
   input_filter_enabled 1` 扛过重启也没用。所以触发源换成传感器。
2. **App 的设置到不了 system_server**（"全局手势改了没反应"的真根因），三条路全实测走死：
   - YukiHook 的 `prefs()` 快照是**冻结**的 —— 改文件、杀框架、整机重启都读不到新值，
     且 1.3.0 的 `clearCache()` 反编译看是空方法（`0: return`）；
   - system_server 直接读 App 私有 prefs：探针实测 `exists=false canRead=false`
     （SELinux 把 stat 都掩成"不存在"）；
   - 自己反射 `de.robv.android.xposed.XSharedPreferences`：`ClassNotFoundException`。
   所以配置只能由 App **主动推**：`ConfigPush` → 签名级权限保护的广播 → `ConfigSync`。
3. **`adb install -r` 会换 codePath，而 LSPosed 的 db 要一会儿才跟上**。装完几秒就
   `kill system_server`，开机时 lspd 会报 `Failed to open APK '<旧路径>': I/O error`
   并**静默完全不加载模块**（表现像"新代码没生效"）。装完等 ~30 秒再重启框架。
4. **`xposedminversion` 写 100 会让这台机器的 LSPosed 不加载本模块**（回 93 才正常），
   所以 LSPosed 那条"使用了已废弃功能"的提示不能靠这个字段消掉。
5. **合成触摸的两条硬规矩**：DOWN→UP 之间位移不足 touch slop 会被判成**点击**
   （真机复现过"手没碰屏幕却打开了列表项"）；同一帧里发多个事件必须给**不同的
   eventTime**，否则应用侧算出的位移是 0，同样被判成点击。

## 第三方代码来源

- `app/src/main/kotlin/.../ui/liquid/`（`LiquidGlassNavigationBar.kt`、`Lens.kt`、
  `InnerShadow.kt`、`CombinedBackdrop.kt`、`Vibrancy.kt`、`Theme.kt`，以及
  `ui/liquid/animation/` 下的 `DampedDragAnimation.kt`、`InteractiveHighlight.kt`）：
  整体取自 Miuix 官方示例的 **iOS 液态玻璃导航条**
  （`example/shared/src/commonMain/kotlin/component/liquid/`，Apache-2.0，
  https://github.com/compose-miuix-ui/miuix ），而 Miuix 那份又是
  **Kyant0/AndroidLiquidGlass**（Apache-2.0，https://github.com/Kyant0/AndroidLiquidGlass ）
  的改编。对源文件只做了两类改动：包名改成本模块的
  `xyz.nextalone.hyperzoomring.ui.liquid[.animation]`，以及把示例里依赖 demo 主题的
  `isInDarkTheme()` 换成本地实现（跟随系统深浅色）。原始 SPDX 版权头全部保留。
  这块提供的是**选中项那块会滑过去的玻璃滑块 + 透镜折射 + 阻尼回弹**，
  不是自己写的近似实现。
- 依赖 YukiHookAPI、KavaRef、Miuix 均为 Apache-2.0，与本项目的 GPL-3.0 兼容。

## 第八批（2026-10-16 晚）：底栏选中态、应用内表冠缓动、配置通道与 release 交付

| 变更 | 文件 | 说明 |
| --- | --- | --- |
| 底栏滑块改采样对象 | `ui/liquid/LiquidGlassNavigationBar.kt` | 删掉给滑块当折射素材的隐藏镜像行与 `CombinedBackdrop`，滑块改成只采**页面那一层**；选中态同时用文字/图标颜色区分。原因：滑块原来采的是一整排 tab 的副本，选中项被糊一遍再折射；Miuix 对空样本画实心黑，快速点底栏会跳黑块。（这一行当晚写的是「滑块不再采样」，第九批把滑块还回来了，见文末） |
| 底栏容器加玻璃存活闸门 | `ui/GlassBar.kt` | `isBlurActive = backdrop != null && backdropIsLive(backdrop)` |
| 应用内表冠逐帧缓动 | `ui/RingEase.kt`（新增）、`ui/MainActivity.kt` | 200ms 一拍改成累加目标 + 帧循环按 `1-exp(-dt/tau)` 追平；纯函数 + 7 条单测；热调 `hzr_ring_tau_ms` / `hzr_ring_px_per_unit` |
| 配置接收器注册重试不再挂在传感器样本上 | `hook/CrownHooks.kt`、`hook/ConfigSync.kt` | Boot 节拍起来后每 2 秒继续 `tick`；`tick` 加 `@Synchronized` |
| 玻璃热调改事件驱动 | `ui/GlassTuning.kt`、`ui/MainActivity.kt` | `ContentObserver(Settings.Global.CONTENT_URI)` 取代 800ms 轮询，15 秒兜底；带 `observerHits/pollHits` 计数自证 |
| 热调值必须穿透到子组件 | `ui/RestartControl.kt`、`ui/MainActivity.kt` | `liveBlur` 由调用方 `remember(glassTick)` 算好传参；新增 `hzr_glass_btn_live` |
| release 交付 | `app/build.gradle.kts`、`app/proguard-rules.pro`、`gradle.properties`、`settings.gradle.kts` | R8 全模式 + 资源收缩；keep 住"靠名字"的四处（Xposed 入口类、Miuix 采样层方法名、枚举、行号表）；无 `KEYSTORE_FILE` 时签名回落调试库；`localmaven/` 绕开 dl.google.com 超时；关掉 release lint |
| 品牌与发布材料 | `brand/`、`.gitignore`、`.github/workflows/build.yml` | 海报由本地脚本（不进仓库）生成，仓库里只放定稿 `brand/poster_final.png|jpg`；CI 产物改名 `OmniRing-*`；`shots/`、`localmaven/`、`brand/tmp/`、`backups/` 加入 ignore |

体积与性能实测见 README 的「性能与体积」一节；所有数字都在真机（Xiaomi 17 Ultra 徕卡版）上量得。


## 第九批（2026-10-16 深夜）：把底栏液态玻璃滑块还回来 + 宣传图定稿

上一批「修糊字」时我砍过头了：他要的是**把那个区域的模糊修好 + 文字变色区分**，
我直接把整块玻璃滑块换成纯色胶囊 —— 那是把功能删了。这一批改回来，只保留三个真因的修复。

| 变更 | 文件 | 说明 |
| --- | --- | --- |
| 滑块恢复真液态玻璃 | `ui/liquid/LiquidGlassNavigationBar.kt` | `drawBackdrop(backdrop = 页面层, vibrancy + blur×2.2 + lens×0.5)`；`onDrawSurface` 深/浅各给一层白；**不再采镜像 tab 行**，所以清晰那份不会被糊第二遍 |
| 滑块高光补回 | 同上 | `pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)`，上一版随滑块一起被我删了 |
| 抖动仍按根因关掉 | 同上 | 滑块 `graphicsLayer` 只留 `translationX`；velocity 拉伸与按压 78/56 气球不再挂到滑块上（spring 欠阻尼回弹会来回过冲） |
| 黑块仍有存活闸门 | `ui/GlassBar.kt`、同上 | `isBlurActive` 为假时滑块退化成 accent 半透明胶囊，宁可「不糊」也不能画实心黑 |
| 宣传图（后被他自己做的版本取代） | `brand/poster_final.png|jpg` | 我那几版（含 AI 底图与渲染脚本，都在本地不进仓库）全部作废，对外只用他这张（横放机身 + 赛璐璐手绘手 + 镜组中心换成自家 C 弧 logo），文字全部我们自己排；出 16:9 主图 / 1:1 酷安首图 / 2:1 GitHub 社交卡 |

版本号：`versionCode 38` / `versionName 1.28.0-beta` / `DebugProbe.BUILD_TAG = cfg32`。

---

## 第十批（2026-10-30）· 全面适配 HyperOS 3 + 相机误触发 + 远程测试的证据面

业主手上只有一台 OS4 的 17 Ultra，OS3 要交给酷友测。所以这一轮的规矩是：
**能在 PC 上证死的全证死，证不死的写清代价和兜底**，判据全在 `docs/HYPEROS3-SELFCHECK.md`，
给测试者的回执在 `docs/HYPEROS3-TESTER.md`。

| 变更 | 文件 | 为什么（不是"顺手改"，都有根因） |
| --- | --- | --- |
| 双入口租约式互斥 | `ring/RingSourceArbiter.kt`（新）+ 两条入口各接一处 | OS3 上输入侧（AOSP `filterInputEvent`）和传感器侧会各自执行一遍动作。选"传感器路做主、输入侧只吞事件"，因为累计量/广播契约/手感全是在这条路上实测调校过的，而 OS3 的 `AXIS_SCROLL` 语义我无法离线判定 —— 不敢让它做主。租约而不是硬关：假设错了最坏是 700ms 空窗，硬关错了是 OS3 整套失灵 |
| 时钟倒挂不许锁死 | 同上 + 单测 | 戳比现在还新时 `age<0` 会永久让路 = 传感器路被自己关死；这台机这几天动过（工程整盘从 D: 迁到 E:），不能赌时钟单调 |
| 相机误触发的两条闸门 | `ring/ForegroundGate.kt`（新）、`SensorRingDriver.deliver`、`InputInterceptorHook` | ① 老代码"先占时间戳再查"，查询抛异常时 `null` 被当有效值复用 200ms —— 恰好把最容易读空的转场窗口放大；② 这台 ROM 的抓包里真的出现过 `mCurrentFocus=null` 而焦点仍在原应用，所以刚换应用的 600ms 窗口内不执行 `injectsTouch` 类动作 |
| 闸门放在共用段 | 两条入口都接了同一个 `ForegroundGate` | 上一轮"App 内不注手指"只加在硬件路径、漏了传感器路径，结果误触复活。这次两条路共用同一份判据 |
| 心跳加第 8 段（输入侧执行/只消费） | `util/Heartbeat.kt` | 没有 OS3 机器，判据必须能从别人手机里带回来：`0/5` = 输入侧活着且被挡住（正常），`3/0` = 真在双倍执行（我最怕的那一行）。7 段老载荷照旧解析，有单测钉住 |
| 定向日志两条 | 两条入口 | `FG 执行注入 …` / `FG 转场窗口内跳过 …`，带 `fg`/`cam`/`切换后 Nms`。一次复现就能分清"包名不在白名单"还是"读到空"。故意不复用已有的 2 秒节流 —— 两条日志挨太近会互相吞，那是假阴性 |
| 适配口径改诚实 | `util/Compatibility.kt` | `TESTED_MIN_SDK` 原来写 36，等于宣称跑过 Android 16，而唯一实测机是 OS4/SDK 37。改成 37，OS3 显示 WARN + 部分适配 |
| 技术路径那行不再写死 | `Compatibility`、`AdaptationCard`、4 份 strings | 值改从心跳来；OS3 上输入侧也在收事件，这一行正是远程测试最想看见的一句话 |
| OS3 版本串钉住 | `DeviceInfoTest` 补 2 条 | 真实世界有两种写法（`OS3.0.34.0.WPCCNXM` / 无前缀 `3.0.301.3.WPAMIXM`），后者必须退回 `ro.mi.os.version.name` 而不是把增量号当版本号 |

热调键（都不用重装就能 A/B）：`hzr_ring_lease_ms`（700，0=关仲裁）、`hzr_fg_stable_ms`（600，0=关转场闸门）、`hzr_debug`（1=开定向日志）。

版本号：`versionCode 45` / `versionName 1.35.0-beta` / `DebugProbe.BUILD_TAG = cfg39`。
测试：33 类 / 202 条断言 / 0 失败（基线 175，本轮 +27）。

---

## 第十一批（2026-10-31）· 相机硬规则 + 启动动画收尾 + "环直接失效"的真根因 + 老 LSPosed 的发布车道

| 变更 | 文件 | 为什么（不是"顺手改"，都有根因） |
| --- | --- | --- |
| 相机内**硬**否决注手指 | `ring/ForegroundGate.allowsInjectedAction`、`SensorRingDriver.deliver` | 相机误触发那条我上一轮是靠 `override_camera` 判的，而他那份 prefs 里 `override_camera=true`（他自己都不知道什么时候打开的）—— 于是一个"允许覆盖"的开关把闸门整个让过去了。现在改成：相机在前台时**不论这个开关**都不注入手指，开关只能往更严的方向调。真机取证：`MIUIInput` 里 ACTION_DOWN/UP 间隔 17ms 那批幽灵触摸消失 |
| 启动动画：浅色主题下齿必须换墨色 | `ui/launch/LaunchOverlay.kt`、`ForegroundGate.toothOnLight/luma` + 单测 | 虚拟机是暗色主题，所以第一版在真机（浅色）上滚花齿画在白底上等于没画，表冠变成一个光边圆盘。判据是录屏逐帧量的：齿带半径 21.8~27.6，深色表盘只到 19.2 —— 齿本来就在页面背景上，必须跟着底色换色 |
| 动效收尾参数烘进代码 | `ui/RingEase.kt`（`DEFAULT_PX_PER_UNIT = 2.5f`）+ `RingEaseTest` 锁 | 应用内表冠的 2.5 是他真机调到顺眼才定的，之前**只活在调试键** `hzr_ring_px_per_unit` 里；键被清掉/换机就悄悄退回"顿顿的" 1.2。现在默认值进代码，单测钉住不许偷偷统一回 1.2，调试键仍然可以覆盖做 A/B |
| `hzr.legacyOnly` 发布开关 | `app/build.gradle.kts`、`tools/legacy_bench_verify.sh`（新） | 见 `docs/HYPEROS3-SELFCHECK.md` §6：包里的**双入口声明**在老 LSPosed（v1.11.0）上会让模块**一条都不加载**，而界面看起来一切正常。酷友的 OS3 机器我们预装不了，他们手上大概率就是老版本，所以给测试者发的是"只留 legacy"这一份（`-Phzr.legacyOnly=true`），老新框架都吃这一条 |
| 探针不再只报 modern 的计数 | `DebugProbe`（8192）、`InputInterceptorHook.legacyHookedSummary/runtimeSummary` | bench 上第一次读回显是 `entry=legacy installed=0`，差点判成"legacy 加载了但没装钩子"。真原因是 `installed` 这本账只记 102 那条入口，而 logcat 几分钟就被滚干净。现在 legacy 装了哪几个钩点、共用的运行时起没起、接收器注册成没成，全部落进一行可回读的探针回显 |
| 装机验收加两道硬闸门 | `tools/deploy_verify.sh`、`tools/restore_prefs.py` | ① **总开关**：`module_enabled=false` 时 `SensorRingDriver` 收到样本第一件事就是 return，于是"模块活着、环不动"，而我们所有验收项照样全绿 —— 这台机上"变焦环直接失效"就是这个。脚本现在会在恢复映射时把它掰回 true 并打印明说，装完再断言一次。② **build 戳**：心跳签名只是"文件多大/几点装的"，证明不了 system_server 加载的是哪份 dex；现在必须用探针逼出一次 `build=cfgNN` 回显，对不上直接 fail（而不是"看起来好了"） |

热调键新增：`hzr_ring_px_per_unit`（默认已烘成 2.5）。
`hzr_scrub_y` 在他机上还是 0.27（代码默认 0.45，README 也写的 0.45）—— 这条**没烘**，因为没找到 0.27 是被确认过的证据，只当残留待他判。

版本号：`versionCode 49` / `versionName 1.39.0-beta` / `DebugProbe.BUILD_TAG = cfg42`。
测试：34 类 / 214 条断言 / 0 失败（上一轮 202，本轮 +12 条含 RingEase 默认值锁与启动动画 8 条）。

---

## 第十二批（2026-11-01）· 应用 ID 换成自己的（进 LSPosed 商店的前提）+ 旧包迁移警示

业主决定上架 LSPosed 模块仓库。那里的硬规则不是审核质量而是**"应用 ID 必须属于提交者"**：
要么证明你控制那个域名（根域 TXT `lsposed-modules-repo-verification=<github user>`），
要么用免域名通道 `io.github.<用户名>.<模块名>`。`xyz.nextalone.*` 是上游作者的域名族
（他在架上的另一模块叫 `ltd.nextalone.pkginstallerplus`），借不了。所以这一批改的就是这件事。

| 变更 | 文件 | 为什么（都有实测/官方原文支撑） |
| --- | --- | --- |
| `applicationId` → `io.github.tvtjiesen.omniring` | `app/build.gradle.kts` | 商店那条规则。**`namespace` 保持 `xyz.nextalone.hyperzoomring`**：换的是装机身份，不是代码署名，源码目录/`package`/R 类/KSP 生成的入口类名全部不动 |
| 包名相关字符串全部改为**派生**，不再逐处替换 | `util/ModulePath.kt`、`config/ConfigPayload.kt`、`hook/InputInterceptorHook.kt`、`hook/SensorRingDriver.kt`、`AndroidManifest.xml` | 真实字面量是 **11 处**（我一开始数成 7 处，漏的是"包名嵌在长字符串里"的 4 条广播 action 和权限名 —— 第一版 grep 只匹配引号恰好包住包名的那些）。清单里用 AGP 内建 `${applicationId}` 占位符，合并后的清单已核对展开成 `io.github.tvtjiesen.omniring.permission.PUSH_CONFIG`（两次：`<permission>` + `<uses-permission>`） |
| 广播 action 与签名级权限名跟着包名走（不只是整洁） | 同上 | ① 配置推送是**不带 setPackage 的隐式广播**，新旧两包 action 同名会各收对方一份配置 → "改了设置时灵时不灵"；② 权限名在设备上是全局注册项，两包用不同签名声明同名 signature 权限必有一个装不上；③ 环事件广播同名会让界面收到两份 |
| 旧包迁移警示 | `util/LegacyPackage.kt`（新）、`ui/screen/HomeScreen.kt`、4 份 strings | 两个 ID 是两个独立 App，`install -r` 覆盖不到老包；两边都启用就是"桌上两份"，症状和我们刚修的双倍执行一模一样。查包走 IO（不卡首帧），查不到退化成"不提示"而不是吓唬人 |
| 两条判据锁 | `ConfigPayloadTest`、`LegacyPackageTest`（新） | 前者钉"权限与 4 条 action 必须从 `BuildConfig.APPLICATION_ID` 派生"且 ID 必须 `io.github.` 开头；后者钉"旧 ID ≠ 当前 ID"（有人改回去的话警示会永久挂着）+ 三态判据 |
| 装机/验收脚本同步 | `tools/deploy_verify.sh`、`tools/legacy_bench_verify.sh`、`build/deliver.sh` | 这些脚本查的是**装机身份**（`pm path`、`/data/data`、LSPosed db 的 `module_pkg_name`），不改就会一路查错包得到假阴性。`deliver.sh` 是 GBK 编码，用字节级替换只动 ASCII 那行，没整文件转码 |
| 发布车道 | `tools/make_release_key.sh`（新）、`.gitignore` | 见第十一批的签名一节；`.gitignore` 补 `*.jks`/`keystore.properties`/`*passfile*` |

已验证（PC 与虚拟机侧）：218 条断言 0 失败；`aapt2 dump badging` 两份 release 都是
`package name='io.github.tvtjiesen.omniring' versionCode='50' versionName='1.40.0-beta'`；
dex 里有 `cfg43`；legacy 那份仍只留 `assets/xposed_init` + `META-INF/yukihookapi_init`。
警示卡在 bench_a15 上实测：老包装着 → `installed=true why=installed → 警示=true` 且列表整体位移；
卸载老包 → `false`。（**这条我第一次量错了**：卡片在首屏之下，不滚就截图会得到"两张图完全相同"的
假结论，是滚动之后才看出差异的。）

还没验证（不含糊）：**改完包名后还没有在任何真机上跑过"模块真加载 + 配置真推达"**。
你手机这一轮一直没插回来；bench 那台的 LSPosed 1.11.0 管理器是 Compose 界面，
`uiautomator` 抓不到可读节点，我没有靠肉眼猜坐标去点它。插上后走 `tools/deploy_verify.sh`，
三条证据（`build=cfg43` 回显 + `module_enabled` 为真 + 删掉心跳后它被重新写出来）齐了才算闭环。
另外这次装机是**新包**：需要你在 LSPosed 里给它重新勾作用域（旧模块请关掉并卸载）。

版本号：`versionCode 50` / `versionName 1.40.0-beta` / `DebugProbe.BUILD_TAG = cfg43`。

### 第十二批补充（同日，真机 14:19 抓到并修掉的一条：#46 的一条真根因）

装机验收改名后的机器时发现：心跳里的签名是新的（`base.apk|48148492|…`），
`hzr_loaded_apk` 却还停在上一版（`base.apk|47121321|…`）。判据链是闭合的、不用猜：
`flush()` 里"解析签名"和"发布签名"在同一条 `signature.isEmpty()` 分支 ——
心跳能带上新签名说明这一支**跑过、解析成功**，而键里是旧值，说明**同一次里的
Settings.Global 写没落地**（开机头几十秒 SettingsProvider 还不接受写，这条链路上见过同类失败），
而它**一辈子只发一次、永不重试**。App 的"是否待重启"判的正是 `installed == loaded`，
于是模块明明好了，首页还一直叫他去重启作用域。

- 修法：`Heartbeat` 记住上次落过的值（`publishedSignature`），只要签名非空且和落过的不一样就重写；
  纯判据抽成 `shouldPublish(current, lastPublished)`，`HeartbeatParseTest` 四条断言钉住
  （含"已经落过就别每两秒写一次 XML"）。
- 真机前后对照（就是这台 nezha，cfg44 装机 + 软重启后回读）：
  `hzr_loaded_apk` 从 `47121321` 翻成 `48148492`；探针 `entry=modern102 installed=6 … build=cfg44`；
  `recv` 从开机早期的 `NullPointerException` 变成 `ok`（注册是重试成功的，不是死锁）。
- 同一轮还证明了改名后的链路在真机上通：新包（`io.github.tvtjiesen.omniring`）的配置
  `hzr_cfg_state=1;14` 被模块吃到、`module_enabled true` 在快照里，
  而且你刚才转环转出了 156 个样本、115 次成功分发（`CW_FAST/CCW_SLOW → scroll`）。

版本号：`versionCode 50` / `versionName 1.40.0-beta` / `DebugProbe.BUILD_TAG = cfg44`。测试 219 条 0 失败。

### 版本号口径变更（2026-11-01 下午）

`versionCode 51` / `versionName **1.40.0**`（去掉 `-beta`）/ `DebugProbe.BUILD_TAG = cfg45`。
原因是这一版要进 LSPosed 模块仓库：商店页是给普通用户看的，而且那边要求 release tag
是 `<versionCode>-<versionName>`，带 `-beta` 看着就是测试包。以后再进实测轮，重新回到 `x.y.z-beta`。

同一轮还加了一条"没有 adb 也能取证"的判据：首页「技术路径」从 `sensor` / `input+sensor`
升级成带计数的 `input+sensor 挡N/执M`（`Heartbeat.Snapshot.pathEvidence`，纯函数 + 单测），
这样测试者只要发一张首页截图，就能把"输入侧收到事件但被挡下"和"真在双倍执行"分开。

产物：`build/OmniRing-1.40.0.apk`（双声明，md5 `3741a2a3…`）与
`build/OmniRing-1.40.0-legacy.apk`（只留传统入口，md5 `a006beed…`，给测试者与上架那份）。
**两份都还是回落的调试签名** —— 上架前必须用正式密钥重签一次（`tools/sign_key.bat`，见
`docs/RELEASE-KEY-HOWTO.md`）。`cfg45` 尚未装到真机（数据线中途拔掉），所以这版只有离线核验：
219 条断言 0 失败、四语 234/234 对齐、`aapt2` 回读包名/版本号、dex 里的 `cfg45` 戳、
两份的 `META-INF/xposed` 差别（2 个 vs 0 个）。真机上是上一个 `cfg44`，那版装机验证过
（探针 `build=cfg44`、心跳带新签名、`recv` 从开机早期 NPE 变 `ok`）。

## 第十三批（2026-11-02）· 表冠统一逻辑（四槽位 → 两槽位）设计稿

**本轮只出设计，未改一行功能代码。** 业主 2026-11-01 提出"表冠统一逻辑"，
先出设计第一版供他修改。新增 `docs/CROWN-ACTION-V2-DESIGN.md`：把「转向 × 转速」四槽位
（`CW_SLOW`/`CW_FAST`/`CCW_SLOW`/`CCW_FAST`）塌成只看转向的两槽位（`PLUS`/`MINUS`），
转速从"决定执行哪个动作"降级为"只决定幅度"。

**病灶判据（源码行，不是推测）**：两个各自独立的分类器都以"每样本瞬时速度"决定槽位 ——
传感器路 `hook/SensorRingDriver.kt:128-137`（`fast = abs(velUnitsPerSec) > FAST_UNITS_PER_SEC`）、
输入路 `ring/ZoomRingDetector.kt:11-31`（200ms 窗口 `count > speedThreshold`）。
速度在阈值上下抖动 → 相邻样本落到不同槽位 → 两个动作交替执行 = 他报的"音量/滑动概率冲突"；
某一格没配时 `resolveActionId(...) ?: return`（`SensorRingDriver.kt:178`、
`InputInterceptorHook.kt:347`）当场丢样本 = "音量只能降不能升"。
而"转速只影响幅度"**今天已经成立**（离散动作 `discreteAccum += abs(velUnitsPerSec)/60f`
＋ `DISCRETE_STEP_UNITS` ＋ 60ms 节流，`SensorRingDriver.kt:271-276`），所以本设计**不碰幅度数值**。

**迁移策略**：不迁移、只读时兼容 —— 沿用 `ConfigManager.kt:17-20` `migrateActionId` 的既有模式，
新键 `gesture_{PLUS,MINUS}_*`（三分作用域同形）读取时三级回落 `…PLUS… → CW_SLOW → CW_FAST`、
`…MINUS… → CCW_SLOW → CCW_FAST`；写只写新键，旧 4 键**只读不删**（退回旧版行为不变）。
设计里点名三个必须提前挡住的陷阱：`DefaultMapping.shouldSeed` 必须走同一条回落链
（否则老用户升级后被重新种上 `scroll`，冲掉自定义映射）、`removeConfiguredPackage` 要清 6 份键、
配置推送给钩子是整份 `p.all`（`util/ConfigPush.kt:75`）所以两侧回落链行为一致。

对外契约影响仅三处且都可控：心跳第 4 段 `gesture` 词汇 4→2（字段数/顺序/分隔符全不变）、
探针 `hzr_map`/`hzr_cfg` 行文随之、四语 strings 由删 4 增 2（234 → 226）。
`tools/deploy_verify.sh` 不用改（只读键、不比字符串）。16 个动作 id 一个不动；
`Action` 接口新增 `directional`（默认 true），`LaunchAppAction` 覆盖为 false 即被排除出两槽位。

**待业主拍板 6 点**（设计稿 §8）：旧"快慢不同配"取哪条 / 旧 `launch_app` 回落成滚动还是不动作 /
两槽位出厂默认值 / 界面文案 / 幅度是否同轮改 / 方向翻转是否清零离散累计。

## 第十四批（2026-11-02）· 表冠统一逻辑（四槽位 → 两槽位）实现 + 自测

**业主已批准设计稿 §8 全部 6 点**（原文：「全部听你的」，2026-11-02）：旧"快慢不同配"取**慢转**那条 /
旧 `launch_app` 配置**回落成 `scroll`** / 两槽位出厂默认**都 = `scroll`** / 文案「顺时针（＋）」
「逆时针（−）」/ **幅度数值一点不改** / 方向翻转时**清零**离散累计。

### 模型层
- `ring/GestureType.kt` **删除**，新增 `ring/CrownDir.kt`（`PLUS`/`MINUS`；枚举 `name` 就是配置键名，
  注释里写明不许再改名，旧键名只允许出现在回落链里）。
- `ring/ZoomRingDetector.kt`：`onEvent` 只看方向（`CW_* -> PLUS`、`CCW_* -> MINUS`、无方向信息按 PLUS，
  保留旧语义）；**速度只喂 `currentIntensity`，公式一字未动**。
- `hook/SensorRingDriver.kt`：`currentGesture()` 只看 `model.dirSign`；删掉从此无消费者的
  `FAST_UNITS_PER_SEC`；离散累计抽成新文件 `ring/DiscreteAccum.kt`（`onSample(dirSign, velUnitsPerSec, tms)`：
  方向翻转清零 → `accum += abs(v)/60f` → 未到 `120f` 不过 → 距上次不足 60ms 不过；原三字段
  `discreteAccum`/`lastActionAt`/`lastDirSign` 随之删除）。抽出类只是为了让这段逻辑**可测**
  （`SensorRingDriver` 是 object，状态进程级私有），判定逻辑逐字照搬。
- `action/Action.kt` 新增 `val directional: Boolean get() = true`；`LaunchAppAction` 覆盖为 `false`；
  过滤点放在 `ActionRegistry.allForScope`（唯一调用点是手势选择页，所以页面本身不用改）——
  **无方向动作"启动应用"从此不出现在两槽位选择器里**；`all()` 仍返回 16 个 id（id 是对外契约，一个不动）。
- `config/DefaultMapping.kt`：两槽位默认都 `scroll`；`shouldSeed` 判断走回落链（老用户不会被重新种上
  `scroll` 冲掉自定义映射，设计稿 §3.2 陷阱 1）。

### 存储与兼容
- 新键 `gesture_{PLUS,MINUS}_action_id|_action_config`，分应用 `app_<pkg>_…`、分场景 `scene_<key>_…` 同形。
- **读**：`PLUS → CW_SLOW → CW_FAST`、`MINUS → CCW_SLOW → CCW_FAST`（`_action_config` 同一条链），
  三个作用域都走；**写**：只写新键，**旧 4 键只读不删**（退回旧版行为不变）。
- `ConfigManager.migrateActionId` 追加 `launch_app -> scroll`（读时归一，老存档里配过"启动应用"的槽位
  自动变成滚动）。
- `removeConfiguredPackage` 清 **6 槽 × 2 键**（新 2 + 旧 4）。
- 新增只读探测 `fun hasLegacySlots(): Boolean` 与 `var legacyNoticeShown`（键 `legacy_notice_shown`）。

### UI / i18n
- 9 个 ui 文件 + 四语 `strings.xml` 由队友 ui-i18n 执行、我复核：`GestureType` 在 `app/src/main` 里
  已 **0 命中**；`ui/CrownStage.kt` 的角度兜底保留旧协议字面量（`"CW_SLOW","CW_FAST" -> 12f`、
  `"CCW_SLOW","CCW_FAST" -> -12f`），因为老版本 App 仍会按旧词发广播。
- 新增一次性合并提示 `ui/screen/LegacyMergeDialog.kt`，条件 `hasLegacySlots() && !legacyNoticeShown`
  （挂在 `MainActivity.onCreate` 的 `Box` 里，与开机动画并列），关闭时置 `legacyNoticeShown = true` ——
  **只提示，不动配置**。
- 门槛滑条文案从"判定为快转的门槛"改成"幅度灵敏度门槛"：2026-11-02 起 `speedThreshold` 只决定
  `currentIntensity` 幅度，不再参与选槽位（四语同步）。
- 四语 strings 总数 234 → **235**（删 4 增 2 后 + 新增 3 条提示文案）。

### 工具
- `tools/restore_prefs.py` 的 `MAPPING` 由 4 条扩到 **6 条**（新两键 + 旧四键，值都是 `scroll`）：
  既是回滚路径（退回 cfg45 时旧键还在），也保住 `tools/deploy_verify.sh:65` 的
  `grep -c gesture_ "$PREFS" ≥ 4` 门槛。
- 探针 `DebugProbe.BUILD_TAG` `cfg45` → **`cfg46`**；`hzr_map` 行文变成 `PLUS:scroll|MINUS:scroll`，
  `hzr_cfg` 里的 `CW_SLOW=` 改名 `PLUS=`。

### 有意与设计稿字面不同的一处
两个分发点（`hook/SensorRingDriver.kt:270-273`、`hook/InputInterceptorHook.kt:350-352`）的
`if (action is LaunchAppAction) action.targetPackage = …` 特例**没有删**。理由：`launch_app` 已从选择器
消失、老配置又被 `migrateActionId` 归一成 `scroll` ⇒ 这段不可达；留着是零成本兜底，删了反而多一处回归面。

### 证据（本机实测，2026-11-02）
- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL in 26s**，
  46 actionable tasks all executed，exit 0（只有既有的 KSP/YukiHook 警告）。
- `app/build/test-results/testDebugUnitTest/*.xml` → **tests=239 / failures=0 / errors=0 / skipped=0**
  （37 个 XML；基线 219 条，本轮改写 4 个测试文件 + 新增 `ring/DiscreteAccumTest.kt`、
  `action/ActionRegistryDirectionalTest.kt`）。
- `python tools/i18n_check.py` → `values=235 values-b+zh+Hant=235 values-en=235 values-ru=235` OK。
- debug APK：`app/build/outputs/apk/debug/app-debug.apk` md5 `e150334568322a3ed77f898a9ac8e91e`，
  939 entries，`META-INF/xposed` 2 条，dex 里带 `cfg46` 戳 —— 这份就是 `tools/deploy_verify.sh` 会推的包。
- **本轮只本地提交，未 push。真机验收（P2）等 17 Ultra（`c05bb07c`）接上再做。**
