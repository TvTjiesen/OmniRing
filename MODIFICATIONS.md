# 改动说明（GPL-3.0 §5a 要求的显著声明）

本仓库是 **NextAlone/HyperZoomRing** 的二改版本。下列文件由本次二改**新增或修改**，
改动日期为 **2026-10-11 ~ 2026-10-12**，作者：**TvTjiesen**。原始版本见 `README.upstream.md`，
完整原始许可见 `LICENSE`（GPL-3.0，保持不变）。

二改版在界面上的名字是 **万能变焦环 / OmniRing**（含新启动图标）；包名沿用上游
`xyz.nextalone.hyperzoomring` 不变，这样已装机的用户和 LSPosed 作用域不用重来。

代码占比（`tools/code_share.py`，2026-10-12 复算，76 个 .kt / 9493 行）：
本 fork 新增 32%、移植 Miuix 液态玻璃 15%、上游文件被重写 13%、上游原样保留 38%。
上游的署名放在 App 内「设置 → 致谢」和本文件、`README.md`，不再占用界面首屏。

## 为什么要二改

HyperOS 3 把 `dumpsys input` 里的 `InputFilterEnabled` 强制置为 `false`，Android 因此
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

1. **HyperOS 3 掐死输入捕获**：`InputFilterEnabled=false` → 上游唯一的硬件事件入口
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
| 品牌与发布材料 | `brand/`、`.gitignore`、`.github/workflows/build.yml` | 海报由 `build/make_poster.py`（SVG 几何）+ 无头 Edge 生成；CI 产物改名 `OmniRing-*`；`shots/`、`localmaven/`、`brand/tmp/`、`backups/` 加入 ignore |

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
| 宣传图 v5 | `brand/`、`build/make_poster_v5.py`、`build/render_poster_v5.sh` | 画面交给 AI 关键视觉（横放机身 + 赛璐璐手绘手 + 镜组中心换成自家 C 弧 logo），文字全部我们自己排；出 16:9 主图 / 1:1 酷安首图 / 2:1 GitHub 社交卡 |

版本号：`versionCode 38` / `versionName 1.28.0-beta` / `DebugProbe.BUILD_TAG = cfg32`。
