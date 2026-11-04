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
- **本轮只本地提交，未 push。**

### 真机验收 P2（2026-11-02，小米 17 Ultra `c05bb07c`，HyperOS 4 / SDK 37）

`bash build/make_and_run.sh`（= `tools/deploy_verify.sh` 的副本：串号写死、python 换绝对路径，
因为 DSH 的 win_bash 通道不向子进程传环境变量，见 HANDOVER §10）退出码 **0**，全套通过：
- 空闲检查通过（10 秒两次心跳一致）；恢复映射回读 **6 键全 `scroll`**（新两键 + 旧四键）——
  回落链与 `restore_prefs.py` 的 6 条 `MAPPING` 在真机生效；总开关 `true`。
- 装机 md5 与本地一致 `e150334568322a3ed77f898a9ac8e91e`。
- `system_server` 重启 `5328 → 18151` 后**模块带新签名起来**：心跳 `base.apk|47158549|1793595760235;0;0;;;0;0;0/0`。
- 探针回显 **`build=cfg46`**（`entry=modern102 … runtime=started=true ctx=ok cfg=ok recv=ok`）
  ⇒ system_server 里跑的就是本轮代码，不是旧包。
- 配置通道：`hzr_map` = `PLUS:volume_up|MINUS:-`、`hzr_cfg` = `pushed=1 keys=12 src=snapshot … reg=ok` ⇒ 推送通。
- 验收脚本已清掉调试键（`hzr_probe/hzr_map/hzr_cfg/hzr_debug/hzr_entry/hzr_inj`）；
  **热调参数未动：`hzr_scrub_y=0.27`**。
- 验收后读回设备配置（转存 `build/p2_prefs_after.xml`，981 B）：新两键与旧四键全 `scroll`、`module_enabled=true`、
  `defaults_seeded=true`、`dispatch_mode=global`、`per_app_configured_packages=com.mfcloudcalculate.networkdisk`；
  **`legacy_notice_shown` 尚未写入**（4 个旧键仍在 ⇒ 下次启动 App 应弹出一次性合并提示——UI 层未肉眼验证。
- **未能取证的一项**：App 界面截图没拿到——`exec-out screencap` 拍到的一直是锁屏/通知栏
  （`mCurrentFocus=Window{… NotificationShade}`、`mWakefulness=Dozing`），`uiautomator dump` 只看到锁屏文本；
  本次模型没有图像输入能力。两槽位文案与合并提示的**界面**验证仍需业主看一眼手机。
- 顺带修掉一个静默 bug：`tools/deploy_verify.sh` 里 `am start -n $PKG/$PKG.ui.MainActivity`（两处）
  因为 applicationId ≠ Kotlin namespace 一直 `Error type 3` 失败且输出被丢进 /dev/null，
  已改为 `$ACT=$PKG/xyz.nextalone.hyperzoomring.ui.MainActivity`。

## 第十五批（2026-11-02）· 上架 LSPosed 商店的前置核对（无功能改动）

这一批只动文档与工具，**App 代码零改动**（所以不产生新的构建戳，包内仍是 `cfg46`）。

- **`docs/LSPOSED-STORE.md` §7 刷新**：商店 tag 从历史值 `50-1.40.0` 抬到 **`51-1.40.0`**
  （`versionCode` 已是 51）；附件名从 `-beta-signed` 改为 **`OmniRing-1.40.0-signed-legacy.apk`**；
  changelog 补上表冠统一逻辑（两槽位、老四键只读不删、诊断页事件总数改读心跳累计）三条。
  并注明：11-01 那两份 `-beta-signed` 包**已不在磁盘上**（`release_sign_report.txt` 里还留着 md5），
  上架前必须重新双击 `tools\sign_key.bat` 出包。
- **新增 `tools/gen_store_icon.py`**：按 `tools/gen_icon.py` **同一份几何**烘一张 512×512 的商店方图
  （自适应图标是 108dp 图层只露 72dp，系统放大 1.5 倍再裁圆，所以这里也按"72dp 铺满画布"渲染，
  与用户在桌面看到的比例一致）。产物 `build/store_icon_512.png`（108,457 B）与 `build/store_icon_192.png`；
  抽样核对过中心玻璃面 `(11,15,24)`、外圈 `(149,167,192)`、红点 `(229,50,43)`、四角仍是斜向石墨底色。
- **构建侧已先验过（不带口令的那半条链）**：`./gradlew --offline assembleRelease` 与
  `-Phzr.legacyOnly=true assembleRelease` 都 exit 0，产物分别是
  `build/rc_dual.apk`（md5 `089f91efd9c30e70654c93540d4dd49f`，444 entries，`META-INF/xposed` 2 条）
  与 `build/rc_legacy.apk`（md5 `0738fc61875ea09966587ecc2ec96dcb`，442 entries，0 条），
  两者 `aapt2 dump badging` = `io.github.tvtjiesen.omniring` / `versionCode 51` / `1.40.0` / 「万能变焦环」，
  dex 戳均为 `cfg46`，`apksigner` 回读均为 Android Debug（`8785e490…`）——**重签是业主本人那一步**。
- **HyperOS 3 口径未变**：`util/Compatibility.kt` 的 `TESTED_MIN_SDK` 仍是 **37**（OS3 = SDK 36 显示 WARN /
  部分适配）。代码侧能证死的 9 条仍见 `docs/HYPEROS3-SELFCHECK.md` §1，缺的仍是那份**真机回执**
  （一张首页截图看心跳第 8 段 `input+sensor 挡N/执0` + 四句话）。
  所以商店 README 只写「HyperOS 4 已实测」，**不写适配 OS3**。
- **同日 20:09 业主双击 `tools\sign_key.bat` 出了正式签名包**（口令全程没过我手）：
  `build/OmniRing-1.40.0-signed.apk`（md5 `b30d4c69080b2725351228267a984557`，444 entries，`META-INF/xposed` 2 条）
  与 `build/OmniRing-1.40.0-signed-legacy.apk`（md5 `70705b8dfb977c251e13e060ca58a4b6`，442 entries，0 条），
  两份 `apksigner verify --print-certs` 均为 **V2 signer / SHA-256 `222fc290a5ade9398b0100e9683127118286f5e145dec681b57ae9891945bc1b`**
  （= 正式证书指纹，我独立回读、不采信脚本自述），`aapt2` 仍是 `io.github.tvtjiesen.omniring` / 51 / 1.40.0，
  dex 戳 `cfg46`。**签名关已过，剩下的只有提 issue 与建 release 两个网页操作。**
- **同日 20:24 商店提交已由 agent 代做完毕**（`gh` CLI、业主账号，业主授权「你可以操作我的电脑」）：
  issue `#2407` → 仓库 `Xposed-Modules-Repo/io.github.tvtjiesen.omniring` 建成 → 邀请 `341041488` 接受 →
  `SUMMARY` + `README.md` 推送（无源码）→ Release `51-1.40.0` 带 legacy 附件上传成功。
  **仓库描述仍需业主在浏览器填**（REST 无权限，机器人明确要求），不填则商店不收录。

## 第十六批（2026-11-02）· 代码来源统计改成可复现判据 + 首次推送 GitHub

本批**没有任何 App 功能改动**（不产生新构建戳，包内仍 `cfg46`），只改统计工具与文档。

- **`tools/code_share.py` 换判据**：旧版靠两张手写字面清单（`NEW`/`MOD`）给文件分类，
  凡是不在清单里的新文件一律被算成"上游原样"，于是 README 里长期挂着"上游原样保留 40 文件 38%"。
  改成**跟上游工作副本逐文件比 md5**（`--upstream <dir>`），并归一化 CRLF —— 本仓库提交会把 LF 改 CRLF，
  不归一化会把这 8 个其实一字未改的小文件误判成"重写"。旧版 README 的 57 个 .kt / 6534 行也已过时。
- **复核出的真实占比**（上游 HEAD `55b3bb7`）：上游 `app/src` 31 个 .kt / 2153 行；本仓库 132 个 .kt / 17369 行。
  其中**上游一行没动的只有 8 个文件 / 227 行 = 现体量 1.3%**（音量 `VolumeAction`、亮度 `BrightnessAction`、
  手电筒 `FlashlightAction`、`ConfigScope`/`DispatchMode`/`SceneType`/`ZoomRingConstants`/`ZoomRingEvent`），
  另有 3 个非代码文件逐字节相同（`assets/xposed_init`、`resources/META-INF/yukihookapi_init`、
  `res/values/xposed_scope.xml`）；重写 22 个 / 3507 行；新增 102 个 / 13635 行；删除 1 个（`ring/GestureType.kt`）。
- **`README.md` §代码来源与占比 重写**：换成上述实测表 + 复跑命令，并明确写"上游代码只剩 1.3%，但衍生关系仍成立"——
  包名 `xyz.nextalone.hyperzoomring`、`Action` 接口与 16 个动作 id、`ConfigManager` 键名、目录结构都源自上游，
  因此 GPL-3.0 的署名与 §5a 显著修改声明**继续保留**（删掉反而有法律风险，保留成本为零）。
- **首次推送到 GitHub（业主 2026-11-02 明确授权「直接发布上传github吧」，此前 §11 一直是"只本地提交不 push"）**：
  26 个本地提交推到 `TvTjiesen/OmniRing` 的 `main`（`6a885cc` → `7390dbc`）。本机 `.gitconfig` 的
  `url."https://gh-proxy.com/https://github.com/".insteadOf` 会把推送地址改写成镜像并报
  `No anonymous write access`，绕法是用显式端口 `https://github.com:443/...`（前缀不匹配规则），
  凭据用 `-c credential.helper='!f(){ echo username=x-access-token; echo "password=$(gh auth token)"; }; f'`，
  token 不进命令行也不落盘。
- **推送前的红线自查**（业主锁屏 PIN 绝不外发）：把 6 位串拆成两段在脚本内拼接，
  扫工作树（排除 `.git`/`build`）与**所有 commit 的全部 blob** —— 均为 0 命中。

## 第十七批（2026-11-02）· 作用域自动勾选 + 反馈包导出（`cfg47`）

业主一句话两条需求：「给模块再加个自动勾选作用域的功能」+（承接上一条）「提取 log 的功能」。本批只动这两件。

**(1) 作用域自动勾选 —— 查清机制后补一个文件**
- 机制（读 LSPosed `app/.../util/ModuleUtil.java` 原文，不是猜的）：模块被判定为 **modern**（APK 里有 `META-INF/xposed/`）
  时，作用域**只**从 `META-INF/xposed/scope.list` 读（每行一个包名），`AndroidManifest` 里的
  `<meta-data name="xposedscope" android:resource="@array/xposed_scope"/>` **完全被忽略**；
  legacy 模块才读 meta-data。而 manager 侧 `ScopeAdapter` 的行为是：`scopeList`（声明值）当"推荐作用域"，
  **只有当模块当前勾选为空时**才自动勾上推荐项。
- 本工程之前的实情：双入口包里 `scope.list` **不存在** ⇒ LSPosed 把它当 modern 读 ⇒ 推荐作用域为空 ⇒
  用户装完必须自己进作用域页手点「系统框架」。（legacy 单入口包反而没这问题：它读 `@array/xposed_scope` 的 `android`，
  `ModuleUtil.getScopeList()` 里有历史别名转换 `android ↔ system`。）
- 改动：新增 `app/src/main/resources/META-INF/xposed/scope.list`，内容一行 **`system`**
  （不是 `android`：manager 内部表示里 `system` 才是系统框架，`android` 只是 legacy 别名，见
  `AppHelper.getAppList()` 把 `android` 改名为 `system` 那段）。`xposed_scope.xml` 原样保留给 legacy 车道。
- `-Phzr.legacyOnly=true` 车道照旧 `excludes += "META-INF/xposed/**"`，因此不会带进 legacy 包。
- 已验证 APK 内容：`app-debug.apk`（940 entries）里 `META-INF/xposed/` 三条齐了
  （`java_init.list` / `module.prop` / `scope.list`），`scope.list` 读回 `system\n`。

**(2) 反馈包导出 —— 新 `util/DiagReport.kt` + 诊断页两个按钮**
- 口径按业主纠正过的前提走：**本模块必须 root 才能装，用户必然有 su**，所以日志就该直接问他
  `su -c "logcat -d -v time -t 4000"`（`ScopeReload` 已有同样的 su 调用先例，超时/拒绝都走"明确失败"分支，不假装成功）。
- 拿不到 su 时退回读本进程 `logcat`，并在文本里**写明** `NOTE root 不可用…`，避免用户发来的包"看着干净"被误判成模块没跑。
- 只保留能说明"跑没跑"的行（tag 名单：`HyperZoomRing`/`HyperZoomRingUI`/`LSPosed`/`Xposed`/`AndroidRuntime`/`libxposed`/
  `KernelSU`/`Magisk`），上限 800 行；筛选函数抽成 `internal fun keepLine` 供 JVM 单测钉住（`DiagReportTest`，3 条断言）。
- 反馈包前半段是环境证据：版本/机型/SDK、`hzr_entry`、`hzr_cfg`、`hzr_map`、`hzr_hb`、`hzr_loaded_apk`、
  开关与分发模式、两槽位映射与 `action_config`、`legacy_slots` —— 这些都是 `Settings.Global`（读不需要权限）与
  `ConfigManager` 的现成 API，不新增任何写操作。
- 出口两条，都不用额外权限/不新增清单声明：**复制到剪贴板**；**存成文件**走 `MediaStore.Downloads`
  （`下载/OmniRing/omniring-diag-<时间>.txt`，API 29+ 无需 `WRITE_EXTERNAL_STORAGE`，也不需要 FileProvider）。
- 四语文案同步新增 7 个键（`diag_report_*`）：`tools/i18n_check.py` 报 242/242/242/242 OK。

**构建与自测**
- `DebugProbe.BUILD_TAG` → **`cfg47`**；dex 实锤：`app/build/outputs/apk/debug/app-debug.apk`（md5 `7fcf73b730c037876dafb3f5870765d4`，
  940 entries）里 `cfg47` 落在 `classes9.dex`。
- `./gradlew --offline :app:testDebugUnitTest --rerun` → **37+1 个 XML、242 条断言、0 失败 0 错误 0 跳过**
  （本批新增 3 条；上一批 239 条）。
- 尚未做：真机验证（设备此刻不在线）。上机要看的：①装机后 LSPosed 作用域页里「系统框架」是否**已自动勾上**；
  ②诊断页「反馈包 → 存成文件」是否在 `下载/OmniRing/` 出文件、内容里 `rooted=true` 且含 `HyperZoomRing` 行。


## 第十八批（2026-11-03）· 反馈包与自测按钮在真机上修好（`cfg48`）

第十七批把反馈包和自测按钮做出来了，但**第一次上真机就发现三处都是坏的**。本批只修这三处，
没有动转环逻辑、没有改配置键。

### 修了什么

1. **App 侧 `Settings.Global.put*` 一直静默失败**（老 bug，不是第十七批引入的）。
   App 是普通应用 uid、没有 `WRITE_SECURE_SETTINGS`，而 `Settings.Global.putString` 对无权调用者
   **不抛异常、直接丢**，所以 `runCatching{}.isSuccess` 恒为 true —— 上一版据此报"成功"就是谎话。
   现在 `util/DiagReport.kt` 的 `writeGlobal` 走 `su -c "settings put global <k> <v>"`
   （本模块必须 root，用户必然有 su），并**回读校验**值真变了才算成功。
   同一个 bug 让诊断页「运行注入自测」从来没生效过：它写 `hzr_probe` 写了等于没写。
   现改为 `DiagReport.requestProbe(context, 32L)`，点击后 `settings get global hzr_probe`
   确实从 8832 变 32。
2. **反馈包三个关键行全空**（`hzr_entry` / `hzr_cfg` / `hzr_map`）。根因不在读取，而在**探针是边沿触发的**：
   `hook/DebugProbe.kt:48-56` 里 `cmd == lastCmd` 就直接 return，而这些键只在探针被触发那一拍写入。
   于是 `compose()` 现在先清 0 → 等 400ms → 写位图 8832（128|512|8192）→ 等 4.6s 让两拍探针跑完，
   再读键、再抓日志；写不进去时在包里明写 `NOTE 探针没能触发（su 未授权？）`，不假装成功。
3. **logcat 段 13 行里 12 行是噪声**（`D/AndroidRuntime … uiautomator.Launcher`）。
   旧写法抓 `logcat -t 4000` 再筛，但 `-t` 只覆盖最近十几秒，模块开机日志早滚掉了。
   改为 `logcat -d -v time -b main,system -s HyperZoomRing:V HyperZoomRingUI:V LSPosed:V Xposed:V libxposed:V`
   按 tag 直取（不带 `-t` 才能追到开机），crash 缓冲单独取并加 `=== crash buffer ===` 分隔行。
   新增 `NOISE_TAGS = uiautomator / chatty` 先排；crash 段**不能整段留**
   （真机第一条是 11-01 别的 App 的 native tombstone，几十行寄存器把 800 行预算吃光），
   改用 `keepCrashLine` 白名单只挑 `FATAL EXCEPTION` / `Process:` / `DeadSystemException` /
   `Cmdline:` / `pid:` / `signal ` / `Cause:` / `Abort message` / `CRASH` / `HyperZoomRing`。

### 验证

- `./gradlew --offline :app:testDebugUnitTest --rerun`：**38 个 XML / 244 条断言 / 0 失败 0 错误 0 跳过**
  （新增 `keepsAndroidCrashHeaderButNotAmNoise`、`crashSectionKeepsBlameNotRegisters`；
  后者钉住"寄存器转储必须掉、`DeadSystemException` 必须留"）。
- 四语 `strings.xml` 各加 2 键（`diag_selftest_sent` / `diag_selftest_no_root`）→ 各 244 键，
  `python tools/i18n_check.py` 通过。
- 真机（`c05bb07c`，nezha / HyperOS 4 / SDK 37）2026-11-03 20:34 实测反馈包：
  16,580 B，`kept=146`，头 15 行齐全（`build=cfg48` / `recv=ok` / `reg=ok` /
  `PLUS=scroll` / `dispatch=GLOBAL` / `legacy_slots=true`），uiautomator 与 chatty **各 0 行**。
- 构建戳 `cfg46` → `cfg47`（第十七批）→ **`cfg48`**（本批）；`META-INF/xposed/` 三条齐
  （`java_init.list` / `module.prop` / `scope.list`=`system`）。

### 没验的（写清楚，别当已通过）

- **`scope.list` 自动勾选仍未验证**：LSPosed 只在"当前勾选为空"时才用推荐作用域补勾
  （`ScopeAdapter` 的 `emptyCheckedList` 分支），业主这台早已手工勾过「系统框架」，
  所以看不出差别。要验得卸载重装——**本批没做**，因为设备在该验之前掉线了。
- 装完后设备从 Windows PnP 消失（`USB\VID_18D1&PID_4E11\C05BB07C` 不见了），
  本批最后一次构建（含 `keepCrashLine`）**没有跑到真机上**；已在设备上跑过的是同一份
  源码的上一版（差别只有 crash 段白名单）。

## 第十九批（2026-11-04）· 坏包拖死 LSPosed 的事故复盘与恢复（无代码改动）

本批**没有改任何源码**，只记录一次真机事故的取证与恢复，以及由此定下的纪律。

### 事故经过

2026-11-03 20:36 我用 `adb install -r` 装 `cfg48` 调试包，adb 报 `Success`，但这次传输在抖动的
USB 链路上写出了**校验和损坏的 APK**：设备上 `base.apk` 的 md5 是 `ae05aae09a3aa11e8ff0d1590db01398`，
而本地产物是 `1cd70569d11771d3fc4ce7edf654d7cd`。App 一启动就
`ClassNotFoundException: androidx.startup.InitializationProvider` +
`Failure to verify dex file ...: Bad checksum (419d6781, expected 57376778)`。

更严重的连带后果是**整个 LSPosed 全废**：lspd 在 `LSPSystemServerService: starting new bridge`
时去解析已装模块的 APK，碰到这个坏包就在 `libdaemon.so` 的 JNI 里段错误
（`Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)`，`exited unexpectedly, code=139`，存活约 1.5 秒），
`modules_*.log` 只留一行 `----part 1 start----`。业主看到的现象是「连 lsp 都炸了」。

### 取证（决定性对照实验）

| # | 设备上的包 | lspd 启动 | system_server 重启 | 结果 |
|---|---|---|---|---|
| 1 | 坏包 `ae05aae0…` | 系统开机自启 | — | 1.5 秒内 SIGSEGV（原始症状） |
| 2 | 坏包 `ae05aae0…` | 手动 `daemon --force` | 不重启 | 存活 30+ 秒不崩 |
| 3 | 坏包 `ae05aae0…` | 手动（已在跑） | `kill -9 system_server` | 立刻 SIGSEGV |
| 4 | 坏包 + 本模块 `enabled=0` | 手动 | `kill -9 system_server` | 存活、模块日志正常增长 |
| 5 | **好包 `1cd70569…`** + 本模块 `enabled=1` | 手动 | `kill -9 system_server` ×2 | **全程存活，未崩** |
| 6 | 好包 | **整机 reboot**（走正常开机流程） | — | **存活，6 个模块正常加载** |

结论：触发点是"解析模块 APK"这一步，**坏包是唯一变量**；禁掉本模块也能绕过（第 4 行），
但那只是掩盖问题（坏包仍在设备上，随时会把别的模块解析路径带崩）。

### 恢复动作（全部已执行并核验）

1. 重装好包，**核对设备端 md5** = `1cd70569d11771d3fc4ce7edf654d7cd`。
2. 还原 LSPosed 配置库 `/data/adb/lspd/config/modules_config.db{,-wal,-shm}`
   （备份放在 `/data/local/tmp/lspdb_backup/`；实验期曾把本模块 `enabled` 置 0，必须还原）。
3. 整机 `adb reboot`，让 LSPosed 走正常开机流程。
4. 核验：`pidof lspd` = 5090 存活 200+ 秒、crash 文件 0 个、`modules_*.log` 195 KB、
   正常加载 6 个模块（`btm.m.os4.systemuihook` / `com.juren233.hyperlyricsenhanced` /
   `com.kiminonawa.HyperLight` / `com.tsng.hidemyapplist` / `com.xposed.wetypehook` /
   `moe.chenxy.huaweipods`）。
5. **业主数据零漂移核验**：16 个模块的 `enabled` 值逐一比对全一致；`scope` 表 134 行完全一致；
   本模块 `enabled=1`。

### 由此定下的纪律（已写进 HANDOVER §10）

- **`adb install` 的 `Success` 不是证据。** 装完必须
  `su -c 'md5sum $(pm path <pkg> | sed s/package://)'` 与本地 md5 比对，不一致就重装。
- 大文件走这条 USB 链路要用 Windows 原生 Python 驱动 adb（`subprocess.run`，参数不过 MSYS），
  别信 bash 里的 adb：MSYS 会把远端路径改写成 `E:/dev/msys64/...`，传输超 ~4 MB 就拉挂链路。
- 怀疑 LSPosed 炸了，先看 `/data/adb/lspd/log/daemon-*-crash-*.log` 的 `Fatal signal` 与
  backtrace 落在哪个 `.so`，再检查设备上各模块 `base.apk` 的 md5 是否与本地产物一致。

### 本批顺带补记（第十八批"没验的"一条已补上）

第十八批留了两条没验。本轮把第一条补上了：**含 `keepCrashLine` 的那版（`1cd70569…`）确实在
真机上跑过**，装的就是它，探针回读 `build=cfg48`。第二条（`scope.list` 自动勾选）仍需
卸载重装才看得出，**依然未验**。

## 第二十批（2026-11-04）· 探针"清 0"协议失效（`cfg49`）

上一批（第十八批）以为反馈包已经修好了 —— 11-03 20:34 那份 16,580 B 的包确实三行齐全。
**那次是碰巧**：当时 `hzr_probe` 里是别的值，位图和上一轮不同，第一次触发就成功了。
11-04 复测，同一个包只剩下 `hzr_cfg=pushed=0 … PLUS=-`、`hzr_map=PLUS:-|MINUS:-`，
而 `hzr_cfg_state` 写着 `2;17;…`（配置明明早推成功了）。

### 根因（两处，缺一不可）

1. **`hook/DebugProbe.kt` 读到 0 不更新记忆**。老写法是
   `if (cmd == 0L || cmd == lastCmd) return` —— 清 0 那一拍直接 return，`lastCmd`
   还停在上一轮的 8832，于是"再写回 8832"被判成"没变"。清 0 这个动作**在边沿判定里
   等于没发生过**。
2. **清 0 与写位图的间隔小于探针轮询周期**。`ZoomRingSensorListener.PROBE_INTERVAL_MS`
   是 2000ms（`:23`，`postDelayed` 每拍调一次 `probeLoop`），而 `DiagReport` 里
   `PROBE_RESET_GAP_MS` 只等 400ms。0 和位图落进同一个 2 秒窗口，那一拍**只看得到位图**。
   所以只修第 1 条没用 —— 必须有这条间隔配合。

同一个缺陷也吃掉了诊断页「运行注入自测」按钮：`requestProbe` 原来写
`writeGlobal(0) && writeGlobal(32)`，中间没有间隔，**第一次按能通、第二次按就没反应**。
用户会以为按钮坏了。

### 改了什么

- `hook/DebugProbe.kt`：`cmd == 0L` 时先 `lastCmd = 0L` 再 return；边沿判定抽成
  `internal fun shouldRun(cmd: Long, last: Long): Boolean`，纯函数、可单测。
  `BUILD_TAG` `cfg48` → **`cfg49`**。
- `util/DiagReport.kt`：`PROBE_RESET_GAP_MS` `400L` → **`2400L`**（跨过 2 秒轮询），
  `PROBE_WAIT_MS` `4600L` → `6000L`（位图写进去后还要等一拍才轮到它跑）；
  `requestProbe` 补上同一个 `Thread.sleep(PROBE_RESET_GAP_MS)`。
- 新增 `app/src/test/kotlin/xyz/nextalone/hyperzoomring/hook/DebugProbeEdgeTest.kt`：
  `zeroIsIdleNotACommand`（0 不是命令）、`sameValueTwiceOnlyRunsOnce`（挂着不重跑）、
  `resetThenSameBitmapRunsAgain`（清 0 后同一个位图必须再跑，走完整时序脚本）、
  `differentBitmapRunsWithoutReset`（位图不同不必先清 0）。

### 验证（都跑过，不是推断）

- `./gradlew --offline :app:testDebugUnitTest --rerun` → **39 个 XML / 248 条断言 /
  0 失败 0 错误 0 跳过**（上一批 38 / 244，新增的就是上面 4 条）。
- `python tools/i18n_check.py` → 四语各 **244** 键 OK（本批没动文案，数字未变）。
- `assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` md5
  **`b2f13d2b57441b12ac99d514cc2f6e25`**、48,716,048 B。
- 装机核过设备端 md5（`su -c 'md5sum $(pm path … | sed s/package://)'`）**与本地一致**。
- `su -c 'kill -9 $(pidof system_server)'` 后探针回读 `build=cfg49`。
- **真机连做两轮**「清 0 → 等 3s → 写同一个 8832」：`hzr_cfg` 的 `ageMs`
  从 `16653` 变到 `26666`，两轮都真跑了；修复前它恒为 `-1`（一次都没触发）。
- 本轮 `hzr_cfg` 回读：`pushed=2 keys=17 src=push … PLUS=scroll build=cfg49 reg=ok`，
  `hzr_map=PLUS:scroll|MINUS:scroll`（18 行那批全空的情况不再出现）。

### 没验的

- 界面上的端到端点按未复测：11-04 11:26 起设备锁屏（`isKeyguardShowing=true`、
  `mWakefulness=Dozing`），**我没有去绕锁屏**，等业主自己解锁后再走一遍
  （自测按钮连按两次都应生效 + 反馈包三行非空）。
- `scope.list` 自动勾选依然没验（要卸载重装，见第十八批）。

### 教训

**"碰巧成功过一次"不等于修好了。** 这个 bug 的触发条件是"位图与上一次不同"，
而第一次跑总是满足的 —— 所以首测必过、复测必挂。以后凡是"改完 A 让 B 重新执行"
的协议，都要**连续做两次**、并且确认**第二次的输出时间戳真的变了**（这次靠的就是
`ageMs` 从 -1 变成正数这个判据）。

## 第二十一批（2026-11-04）· 探针协议修好后真机端到端验收 + 发 1.40.1（versionCode 52）

### 为什么这轮要动版本号
`cfg49` 是行为修复（探针"清 0"协议、反馈包三行、自测按钮连按），不是纯文档改动，
商店那份 `51-1.40.0` 已经对外，同版本号重发商店不会刷新，所以必须升版本。

### 改动
- `app/build.gradle.kts:39-40`：`versionCode 51 → 52`、`versionName "1.40.0" → "1.40.1"`。
  注释口径不变（`versionCode` = 第几轮交付，每次 +1；1.40.x 不加 `-beta`）。
- 本批**没有其它代码改动**，`cfg49` 已在上批确定（`DebugProbe.BUILD_TAG` 仍为 `cfg49`）。

### 真机端到端验收（`cfg49`，11-04 11:36–11:38，设备已解锁）
1. **自测按钮连按两次**：诊断页「运行注入自测」(297,1756) 点两次，两次都回显"已发出"。
   日志给出决定性证据（`logcat -b main,system -s HyperZoomRing:V`）：
   `11-04 11:36:56.935 I/HyperZoomRing( 6425): PROBE start cmd=32` 与
   `11-04 11:37:02.951 I/HyperZoomRing( 6425): PROBE start cmd=32` —— **两次按压两次执行**。
   修复前第二次不会有任何反应（`requestProbe` 清 0 与写位图之间没有间隔，且 `tick` 读到 0 不更新记忆）。
2. **反馈包三行全部非空**（`/sdcard/Download/OmniRing/omniring-diag-20261104-113739.txt`，5,231 B）：
   `hzr_entry=…runtime=started=true ctx=ok cfg=ok recv=ok bootWait=1 build=cfg49`
   `hzr_cfg=pushed=2 keys=17 src=push stage=false bootRestore=17;1793762744084 snap=489B ageMs=677445 PLUS=scroll build=cfg49 reg=ok`
   `hzr_map=PLUS:scroll|MINUS:scroll`
   （11-04 11:21 那份还是 `pushed=0 / PLUS=-`，同一台机同一个包，差别只在探针协议修没修。）
3. **crash 段收敛**：只剩 10 行，全是 11-04 11:25 我 `kill -9 system_server` 造成的
   `DeadSystemException: The system died`（`com.android.phone` / `com.android.systemui` /
   `com.android.bluetooth`），**没有**再被别的 App 的 native tombstone 吃掉 800 行预算。
   `keepCrashLine` 白名单按设计生效。
4. logcat 段 `rooted=true, kept=46`，全部是 `HyperZoomRing` / `HyperZoomRingUI`，
   **uiautomator / chatty 噪声 0 行**。

### 构建
- `./gradlew --offline -q :app:testDebugUnitTest --rerun` → **39 XML / 248 断言 / 0 失败 0 错误 0 跳过**。
- `python tools/i18n_check.py` → `values=244 values-b+zh+Hant=244 values-en=244 values-ru=244 OK`。
- `assembleRelease` → `app/build/outputs/apk/release/app-release.apk` 4,744,848 B、
  md5 `acbfa06a81e6077f6b1c021e8fc4ad43`、445 entries、`META-INF/xposed/` 三条
  （`java_init.list` / `module.prop` / `scope.list`）、`cfg49` 在 `classes.dex`。
  `aapt2 dump badging`：`io.github.tvtjiesen.omniring` / `versionCode='52'` /
  `versionName='1.40.1'` / `application-label:'万能变焦环'`。
  **签名仍是回落调试签名**（`CN=Android Debug` / SHA-256 `8785e490…ef4ef`）⇒
  发商店前必须双击 `tools/sign_key.bat` 重签。
- `assembleDebug` → 47,197,217 B、md5 `986e30fb8eb59bdc66032df1fd1a249f`、940 entries、`cfg49` 在 `classes9.dex`。
- **装机并回读设备端 md5**：`su -c 'md5sum $(pm path io.github.tvtjiesen.omniring | sed s/package://)'`
  = `986e30fb8eb59bdc66032df1fd1a249f` ✅ 与本地一致；`dumpsys package` 显示
  `versionCode=52` / `versionName=1.40.1`。**`Success` 不算证据，md5 一致才算**（见第十九批事故）。

### 没验的
- `scope.list` 自动勾选仍没验过（要卸载重装；老装机已被手工勾过，LSPosed 只在"当前勾选为空"时补推荐作用域）。
- 正式签名包还没出（jks 口令只有业主知道，不经我手）；本次上架的 release 用他签的那份。

## 第二十二批（2026-11-04）· 1.40.1 正式签名并发布到 LSPosed 商店

### 签名（业主双击 `tools\sign_key.bat`，口令只进进程内存，不存盘、不进命令行、不经 agent）
- `build/OmniRing-1.40.1-signed.apk`：4,744,848 B、md5 `5799074800c7ae84d5903ccac6814a2c`、445 entries、
  `META-INF/xposed/` 三条齐全（双入口）。
- `build/OmniRing-1.40.1-signed-legacy.apk`：4,744,375 B、md5 `e70c13d9698534442fed40f1d333a2f1`、442 entries、
  **无 `META-INF/xposed`**（legacyOnly 车道，上架与发酷友用这一份）。
- **独立回读（不采信脚本自述）**：`apksigner verify --print-certs` 两份均
  `CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN` /
  SHA-256 `222fc290a5ade9398b0100e9683127118286f5e145dec681b57ae9891945bc1b` ⇒ 与
  `E:\dev\keys\omniring-release-key-fingerprint.txt` 一致（`CN=Android Debug` 的回落签名已消失）。
- `aapt2 dump badging` 两份均 `io.github.tvtjiesen.omniring` / `versionCode='52'` / `versionName='1.40.1'` /
  `application-label:'万能变焦环'`；dex 戳 `cfg49` 在 `classes.dex`。

### 发布
- GitHub Release：`Xposed-Modules-Repo/io.github.tvtjiesen.omniring`、tag **`52-1.40.1`**、title `1.40.1`、
  附件 `OmniRing-1.40.1-signed-legacy.apk`（4,744,375 B、`state=uploaded`、`draft=False`、`prerelease=False`，
  **已标记 Latest**）。回读 `releases/tags/52-1.40.1` 与 `gh release list` 双向确认。
- 商店仓库 `description` 仍为 `OmniRing 万能变焦环`、`homepage` 指向源码仓库（两个收录条件保持满足）。

### 真机
- 用**正式签名**的双入口包覆盖安装（签名与设备上调试签名的包不兼容，`adb install -r` 直接 `Success` 说明
  设备认了新证书；随后 `su -c 'md5sum $(pm path …)'` = `5799074800c7ae84d5903ccac6814a2c`，与桌面那份一致）。
- `dumpsys package`：`versionCode=52` / `versionName=1.40.1` / `lastUpdateTime=2026-11-04 11:50:23`。
- `kill -9 $(pidof system_server)` 后探针回读：`build=cfg49`、`recv=ok`、`PLUS=scroll`、
  `pushed=1 keys=17 src=snapshot`、`reg=ok`；`pidof lspd`=5090 存活、无新 crash 文件。

### 文档
- `docs/LSPOSED-STORE.md`：tag / 附件 / release 表格五处刷新到 `52-1.40.1`，旧值标为历史。
- `HANDOVER.md`：§7 记本轮发布与两份包 md5；§12 快照更新。
- 酷安「只发更新」文案改成 1.40.1（`build/coolapk_update_post.md`，草稿不入库；桌面副本 `酷安更新文案.md`）。

### 没验的
- `scope.list` 自动勾选仍未验（要卸载重装；`adb install -r` 保留数据与已勾选作用域，看不出自动补勾效果）。


## 第二十三批（2026-11-04）· 明确放弃适配 HyperOS 3：只改口径，不拆防护

### 业主决策
- m03380 原话：「我想直接放弃适配os3，想在os3正常使用的就自己适配，反正我公开所有源代码」；
  m03389：「动手吧，不保证一定可用」。

### 为什么只改"说法"、不改判定逻辑
- GPL-3.0 已经把 fork 的权利给出去了，我们放弃的只是**跟进承诺**，不是别人的权利。
- 但**防护不能跟着放弃**：`RingSourceArbiter` 的 700ms 租约、双入口互斥、转场 600ms 闸门、
  时钟倒挂防护，保护的是"两条入口并存的系统"这一事实，不是某个具体系统版本。
  OS3 上输入侧与传感器侧会各收到一次转动，删掉只会把"手感不对"升级成"滚两倍"。
  留着零成本，所以一行没删。
- `Compatibility.TESTED_MIN_SDK` 仍是 **37**（这一版之前就已经是 37，改的是它对外的措辞）；
  适配卡不再用"部分适配（未实测）/大概率能用"这种暗示"改一改就能用"的说法。

### 修改的文件
| 文件 | 改了什么 |
| --- | --- |
| `app/src/main/kotlin/xyz/nextalone/hyperzoomring/util/Compatibility.kt` | 类头 `Level.PARTIAL` 注释改为「**不在支持范围**，能不能用不保证」；`TESTED_MIN_SDK` 注释补一句"这就是终态，不再等 OS3 实测后抬回 36"。**数值未改。** |
| `app/src/main/res/values{,-en,-b+zh+Hant,-ru}/strings.xml` | `compat_level_partial` 四语改为「未支持（不保证可用）」/ `Not supported (no guarantees)` / `未支援（不保證可用）` / `Не поддерживается (без гарантий)`；各新增 1 键 `compat_partial_hint`（说明源码公开、需要的自己改）。四语各 **245** 键。 |
| `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/AdaptationCard.kt` | PARTIAL 时在「系统」一行下面多画一行灰色小字 `compat_partial_hint`。理由：只给一个灰色"未支持"标题，用户下一句一定是"那到底能不能用"。 |
| `app/src/test/kotlin/xyz/nextalone/hyperzoomring/util/CompatibilityTest.kt` | 用例 `系统低于实测范围 = 部分适配` 改名为 `系统低于实测范围 = 未支持（不是还差一步）`；`Android 16（HyperOS 3）没实测过 不许报成通过` 的注释改成终态口径。**断言值未改。** |
| `README.md` | 顶部接手提示里加"支持范围"两行；「已知限制」里那条"只在 nezha + OS4 上实测过"扩写成完整口径（含"防护会保留、删了会怎样"和指向 `docs/SELF-ADAPT.md`）。 |
| `docs/SELF-ADAPT.md` | **新增**。给想自己在 OS3 上跑的人：九条已证死的不用再证、五条只能真机回答的未知（A 输入侧收不收得到 MotionEvent / B `mt350x` 是否同名 / C `AXIS_SCROLL` 增量还是累计 / D Android 16 钩子方法名 / E 相机包名白名单）各带判据与兜底、开工前必读（版本串两种写法、别删防护）、构建机条件（JDK 21 / SDK 37 / `localmaven` 不在库里）、发布自己构建的三件必做（改包名 / 自签 / GPL 义务）。 |

### 验证
- `./gradlew --offline -q :app:testDebugUnitTest --rerun` → **39 个 XML / 248 条断言 / 0 失败 0 错误 0 跳过**（与上一批同数：本批只改文案与注释，没有新增断言）。
- `python tools/i18n_check.py` → `values=245 values-b+zh+Hant=245 values-en=245 values-ru=245 OK`。
- 没有改任何 hook / 仲裁 / 注入逻辑，`BUILD_TAG` 仍是 `cfg49`（**本批不产生新的构建戳**）。

### 没做的
- 商店页 `build/store/README.md` 的「支持的系统」段已同步改写，但**尚未 PUT 到 `Xposed-Modules-Repo/io.github.tvtjiesen.omniring`**
  （`build/` 在 `.gitignore`，那是本地草稿；发布商店页要走 contents API，等业主点头一起做）。
- `docs/HYPEROS3-SELFCHECK.md` 与 `docs/HYPEROS3-TESTER.md` 保留原文，只在 `HANDOVER.md` §6 标成"历史材料、不再是待办"。

## 第二十四批（2026-11-04）· 适配卡自相矛盾：「模块加载 = 未被加载」和「环数据 = 还没收到」同时出现（`cfg50`）

### 症状（真机 1.40.1 上复现）
设置页「本机与适配」卡同时显示两行，彼此打脸：
- `模块加载 = 未被加载`
- `环数据 = 开机后还没收到环数据`

而同一时刻 `settings get global hzr_entry` 是
`entry=modern102 … runtime=started=true ctx=ok cfg=ok recv=ok build=cfg49`，
`settings get global hzr_heartbeat` 有值（`base.apk|48755656|1793775151103;0;0;;;0;0;0/0`），
`hzr_loaded_apk` 与它逐字节相同。也就是说**模块明明在跑，只是还没人转过环**。

### 根因
`Compatibility.grade()` 把 `StatusJudge.Verdict.NO_SAMPLES` 并进了 `else -> State.FAIL`：

```kotlin
val loadedState = when (verdict) {
    null -> State.WARN
    StatusJudge.Verdict.OK -> State.PASS
    StatusJudge.Verdict.RELOAD_PENDING, StatusJudge.Verdict.DISABLED -> State.WARN
    else -> State.FAIL          // ← NO_SAMPLES 掉进这里，被判成"未被加载"
}
```

但 `NO_SAMPLES` 的前提恰恰是**心跳已经读到了**（`StatusJudge.judge()` 里
`if (heartbeat == null) return NOT_LOADED` 在前，`if (heartbeat.samples <= 0L) return NO_SAMPLES`
在后），所以能判出 NO_SAMPLES 就等于"模块已加载、只是没数据"。
被判成 FAIL 之后，适配卡显示「未被加载」，而 RING 那一条同时显示「还没收到」——
用户看到这两句会去重装模块、重启作用域，白折腾一轮（我自己也差点这么干）。

### 改法
1. `app/src/main/kotlin/xyz/nextalone/hyperzoomring/util/Compatibility.kt`：
   `NO_SAMPLES` 改判 `State.PASS`（并列出 `NOT_LOADED` / `ALL_FAILED` 保持 FAIL，不再用 `else` 兜底）。
   "没数据进来"继续由 RING 那一条单独说，两条各说各的，整体仍然是 `Level.SETUP`。
2. 顺手修掉同一处的第二个歧义：`DISABLED`（模块总开关关着）原来和 `RELOAD_PENDING` 共用一句
   「待重启作用域」，会让人反复重启而问题在开关上。新增字符串 `compat_loaded_disabled`
   （中 `已关闭` / en `Disabled` / zh-Hant `已關閉` / ru `Отключено`），
   `AdaptationCard.loadedTextOf(state, verdict)` 先看判定名再看状态色。
3. `DebugProbe.BUILD_TAG` `cfg49` → **`cfg50`**（`app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/DebugProbe.kt:33`）。

### 验证
- 新增单测 `有心跳没样本 模块加载必须是已加载，不许跟环数据那行打脸`（`CompatibilityTest.kt`）：
  断言 `LOADED` 项是 `PASS`、`RING` 项是 `WARN`、整体仍是 `SETUP`。
- `./gradlew --offline -q :app:testDebugUnitTest --rerun` → **XML=39 tests=249 failures=0 errors=0 skipped=0**（上批 248）。
- `python tools/i18n_check.py` → **`values=246 values-b+zh+Hant=246 values-en=246 values-ru=246 OK`**（上批 245）。
- `./gradlew --offline -q assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
  **md5 `f63c3108f6c7d061fcd4673d86ce1a96` / 48,756,124 B**。
- 装机并回读设备端 md5（**`Success` 不算证据**，见第十九批的坏包事故）：
  `su -c 'md5sum $(pm path io.github.tvtjiesen.omniring | sed s/package://)'` = `f63c3108…` 与本地一致；
  新路径 `/data/app/~~FXv3WCL7qp0qpFDadqtaYg==/…/base.apk`。
- `su -c 'kill -9 $(pidof system_server)'` 后 `hzr_entry` 出现 `build=cfg50`，`pidof lspd`=5090 存活。
- 界面复验**未完**：dump 时设备正好锁屏（`mWakefulness=Dozing`、`isKeyguardShowing=true`、
  `mCurrentFocus=NotificationShade`），截到的是通知栏，不是适配卡。等解锁后补一张。

## 第二十五批（2026-11-04）· 1.40.2：作用域自动勾选真机验证通过 + 版本号进位

### 这一批改了什么

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | `versionCode 52 → 53`、`versionName "1.40.1" → "1.40.2"` |
| `HANDOVER.md` | §12 快照改写：作用域验证结论、装机数据往返做法、签名入口搬到桌面、1.40.2 待发布状态 |

**代码一行没改**——这一批只是把 `cfg50` 的两处修复（适配卡 `NO_SAMPLES` 误判、`DISABLED` 文案）与实际
验证结果一起发出去，并补上此前唯一没验过的那条机制。

### 作用域自动勾选：终于验了（这是 `cfg47` 加的 `scope.list` 的验收）

此前一直没验成，原因是**覆盖安装不会被重新判定作用域**：`adb install -r` 保留 `modules_config.db`
里的原有勾选，`ScopeAdapter` 的"当前勾选为空才补推荐作用域"分支根本走不到。这一轮改成卸载重装：

1. 卸载前用 `su` 把 `/data/data/io.github.tvtjiesen.omniring` 整目录拷到 `/data/local/tmp/omniring_bak/data`，
   顺手也备份了 `/data/adb/lspd/config/modules_config.db{,-wal,-shm}`。
2. `adb uninstall` → `adb install -r app-debug.apk`（md5 `a39f72bb484f0d531e653407e610b31a`，`versionCode=53`）；
   装完立刻回读设备端 `md5sum $(pm path …)`，与本地一致才算数。
3. 打开 LSPosed 管理器：**「模块 → 万能变焦环」的作用域页里「系统框架 / system」已经自己出现在列表中
   并处于勾选状态**，右侧标注「推荐应用」。修复前这一页是空的，必须手动点。
4. 顺手把「启用模块」打开，DB 回读为 `modules_state=(pkg, 0, 1, 0)`、`scope=[(pkg, 'system', 0)]`。
5. 数据还原：重装后 uid 从 `u0_a342` 变成 `u0_a477`，把 `files/`、`shared_prefs/` 拷回并
   `chown -R` 到新 uid + `restorecon`，`shared_prefs/hyperzoomring_config.xml` 完好；
   `kill -9 $(pidof system_server)` 后 `hzr_entry` 里 `build=cfg50`、`recv=ok`，
   `hzr_cfg=pushed=1 keys=17 … PLUS=scroll reg=ok`。

**判据留档（下次别再从零摸索）**：验证 `scope.list` 只能用卸载重装；覆盖安装看到的是旧勾选。
另外这条机制**只对"从没勾过作用域"的新装生效**，老用户手动勾过的选择会被保留（这是 LSPosed
的设计，不是 bug）。

### 版本号与发布准备

- `versionCode 53` / `versionName 1.40.2`；`assembleRelease` 出的
  `app/build/outputs/apk/release/app-release.apk` 是 **4,746,316 B / md5 `49a2aaa3b31d1ca199c7aae524dab00c`**，
  仍是调试签名，**必须用 `tools/sign_key.bat` 重签**才能发。
- 桌面 `打正式签名包.bat` 原来把输出目录硬编码成 `OmniRing 1.40.1`，每次发版都要手改；
  已改成从 `app/build.gradle.kts` 读 `versionName`（`findstr` + `for /f` 解析，实测输出 `VERSION=[1.40.2]`），
  输出目录随版本走：`桌面\OmniRing 1.40.2\`。
- 发布说明稿：`build/store/RN142.md`（`build/` 不入库）。
- 商店 tag 应为 **`53-1.40.2`**，附件用 `-signed-legacy.apk`。

### 商店页 README 的第二次更新

`Xposed-Modules-Repo/io.github.tvtjiesen.omniring` 的 `README.md` 在 1.40.2 之前补了一条说明：
安装第 3 步现在写「模块已自带推荐作用域，正常情况下『系统框架』会自动出现在列表里并勾好；
如果你之前已经给它勾过别的应用，管理器会保留你原来的选择，这时请手动补上」。commit `6736246`，
回读 1081 B 一致。此前那次（支持范围口径）是 `a7ff0a74`。

### 验证

- `./gradlew --offline -q :app:testDebugUnitTest --rerun`：**39 XML / 249 断言 / 0 失败 0 错误 0 跳过**。
- `python tools/i18n_check.py`：`values=246 values-b+zh+Hant=246 values-en=246 values-ru=246 OK`。
- `assembleDebug` → `app-debug.apk` md5 `a39f72bb484f0d531e653407e610b31a` / 47,215,085 B；
  装机端 md5 回读一致；`versionCode=53 / versionName=1.40.2`。
- 真机：`pidof lspd`=5090 存活、`/data/adb/lspd/log/` 无新 crash。
## 第二十六批（2026-11-04）· 1.40.2 正式签名与发布（无代码改动）

本批只动发布链与文档，App 代码零改动，包内构建戳仍是 `cfg50`。

### 签名入口重做（桌面 `打正式签名包.bat` → `tools/sign_release.ps1`）

- bat 退回到五行纯 ASCII：不再用 `for /f` 解析版本号（那层单引号转义在 cmd 里必坏），
  版本号解析与桌面拷贝全部搬进 ps1。
- ps1 **必须带 UTF-8 BOM**：PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解，中文注释全乱后报
  一串"表达式或语句中包含意外的标记"。
- Gradle 调用改走 `Invoke-Gradle`：stdout+stderr 一起重定向进临时日志再判退出码。
  PS 5.1 的 `$ErrorActionPreference='Stop'` 会把原生命令的 stderr 当致命异常抛
  （`NativeCommandError`），而 Gradle 总往 stderr 写提示，构建其实成功了脚本却炸。
- 新增 `Test-KeystorePassword`：用 keytool `-list` 试读，**口令走 stdin**（不进命令行、不进环境变量、
  不落盘），错 3 次停下并弹窗。`KeytoolException: keystore password was incorrect` 看不出"打错一个字"，
  只会让人以为钥匙坏了 —— 这条预检把误导消掉。
- 签名信息改由 `Start-Process -RedirectStandardOutput` + 读文件获取：`(cmd /c ...) 2>&1 | Out-String`
  在本机 PS 5.1 恒返回空串，会让好包被判成"与正式指纹不一致"。
- 失败时完整 Gradle 日志留在 `build/gradle_error.log`（不要删日志，删了等于不给线索）。

### 1.40.2 正式签名产物（业主本人输口令，口令未过 agent 手）

- `build/OmniRing-1.40.2-signed.apk` 4,746,316 B / md5 `055142ec3502404113f4069afe4a7597`，
  445 entries，`META-INF/xposed/` 三条（`java_init.list` / `module.prop` / `scope.list`=`system\n`）。
- `build/OmniRing-1.40.2-signed-legacy.apk` 4,745,843 B / md5 `20ecd34178ba7395e6b29acdf9e4704f`，
  442 entries，`META-INF/xposed/` 0 条。
- 独立回读（不采信脚本自述）：两份 `apksigner verify --print-certs` 均
  `CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN` /
  SHA-256 `222fc290a5ade9398b0100e9683127118286f5e145dec681b57ae9891945bc1b` /
  SHA-1 `f0a379802f62c32ce749aa2829ff47f243bcef0d`；`aapt2 dump badging` 两份均
  `io.github.tvtjiesen.omniring` / versionCode 53 / versionName 1.40.2 / 「万能变焦环」/ compileSdk 37；
  dex 戳两份都是 `cfg50`（`classes.dex`）。

### 发布

- Release tag **`53-1.40.2`** / title `1.40.2` / 附件 `OmniRing-1.40.2-signed-legacy.apk`
  （4,745,843 B，`state=uploaded`），已是 Latest。
- 踩坑：`gh release create` 偶发 `HTTP 404` 打在 `uploads.github.com/.../assets`，
  此时 release 已建但附件没传上、随后整条被清掉（`gh api releases/<id>` 变 404）。
  **原样重跑一次即成功**；建完必须回读 `assets[].state == uploaded` 才算数。
- 备注：手机上装的仍是 17:28 那份 debug 签名包（md5 `a39f72bb…`）。换正式签名要先卸载，
  会带走 LSPosed 里的模块启用记录，需业主在场，故本轮未换。

### 工具

- 新增 `tools/push_via_api.py`：`github.com:443` 的 git 协议反复被墙（`Failed to connect ... after 21082 ms` /
  `send-pack: unexpected disconnect`），而 `api.github.com` 一直通。该脚本走 Git API 逐个提交搬运
  （blob → tree with `base_tree` → commit → 最后才 PATCH 分支引用），每棵树与本地
  `git rev-parse <commit>^{tree}` 比对，再用 `git hash-object -t commit -w --stdin` 复现同一 sha
  让本地与远程字节一致。支持 `--dry-run` / `--repo` / `--branch`。
- **时区教训**：复现提交对象时时区不能凭经验写 —— `3ab37a2` 要 `+0000`，
  `661b99f`/`c2f2e94`/`42e3f83`/`5657bb5` 要 `+0800`，脚本里两种都试。
