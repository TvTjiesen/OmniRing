# HyperOS 3 适配自检（无 OS3 测试机的前提下，能证到哪一步）

日期：2026-10-30 · 对应构建：`cfg39` / `versionCode 45` / `1.35.0-beta`
执行：Qoder CN（OmniRing 这一轮的实施者）

## 0. 这份文件的用途

我们没有 HyperOS 3 的机器，业主去找酷友测。所以这一轮的原则是：
**能在 PC 上证死的，全部证死；证不死的，写清楚"证不死的原因"和"错了会怎样、兜底是什么"，绝不当成已支持。**

下面每一行的"离线证明"都指到具体单测名，可以直接 `./gradlew :app:testDebugUnitTest --tests "*RingSourceArbiterTest*"` 复跑。
本轮总数：**33 个测试类 / 202 条断言 / 0 失败**（基线 175 条，新增 27 条）。

## 1. 已证死的部分

| # | 风险 | 结论 | 离线证明 |
| --- | --- | --- | --- |
| 1 | **双入口撞车**：OS3 上输入侧（AOSP `filterInputEvent`）和传感器侧会各自执行一次动作 → 滚动双倍速、音量跳两格、离散动作触发两遍 | 已修。传感器路为主，输入侧降级为"只吞事件不执行" | `RingSourceArbiterTest`：`输入侧一个事件都没来过 传感器不许让路`、`输入侧刚处理过环事件 传感器这一拍让路`、`输入侧断供超过租约 传感器自动接管`、`正好到租约边界算过期`、`交错回放里一次转动只执行一遍 且输入侧断供后按租约接管` |
| 2 | **假设错了会不会全灭**：我们把权威给了传感器路，万一 OS3 上那个 `mt350x` 光学传感器不存在/挂了 | 不会全灭。租约 700ms 到期输入侧自动接管执行 | `RingSourceArbiterTest`：`传感器断供超过租约 输入侧自己执行`、`传感器一个样本都没来过 输入侧自己执行`（这两条直接钉住 EXECUTE 分支） |
| 3 | **时钟倒挂把功能锁死**：戳比现在还新（改系统时间 / 两个时钟混用）时按"负数小于租约"会永久让路 | 已修，`age < 0` 判过期 | `RingSourceArbiterTest`：`时间戳倒挂时不许让路` |
| 4 | **A/B 退路**：万一仲裁判反了，必须不重装就能退回老行为 | `hzr_ring_lease_ms 0` = 关掉仲裁 | `RingSourceArbiterTest`：`租约设成 0 就是关掉仲裁` |
| 5 | **相机里误触发滑动**（他这轮报的 bug）：转场过程中 `getRunningTasks` 给的是上一个应用、甚至读空 → `isCameraPackage` 判 false → 往刚打开的相机注手指 | 两处修：① 读失败不再当有效值缓存 200ms（只 40ms）；② 刚换应用的 600ms 窗口内，`injectsTouch` 类动作不执行 | `ForegroundGateTest` 7 条：`读到空值不算切换 不要把切换时刻反复刷新`、`切换后窗口内要注手指的动作先等一拍`、`窗口过去后照常执行`、`从没检测到切换时不许拦住动作`、`前台包名读失败只短暂缓存 读成功才缓存满窗口` |
| 6 | **心跳解析兼容性**：给心跳加了第 8 段（输入侧执行/只消费计数），设备上可能还留着老版 7 段载荷 | 7 段照旧解析，第 8 段缺省为 0 | `HeartbeatParseTest` 4 条（含 `空载荷和残缺载荷都判废而不是猜`） |
| 7 | **虚报支持范围**：适配卡上 `TESTED_MIN_SDK` 原来写 36，等于宣称跑过 Android 16，而我们唯一实测机是 OS4 / Android 17 | 改成 37；OS3 现在显示 WARN + 「部分适配」 | `CompatibilityTest`：`Android 16（HyperOS 3）没实测过 不许报成通过` |
| 8 | **HyperOS 3 版本串读不出来**（适配卡显示成乱码或空） | 两种真实写法都能出 3.x：`OS3.0.34.0.WPCCNXM` → `HyperOS 3.0.34.0`；`3.0.301.3.WPAMIXM`（无前缀）→ 退回 `ro.mi.os.version.name` 得 `HyperOS 3.0` | `DeviceInfoTest`：`HyperOS 3 带 OS 前缀的增量号解析成 3_0_34_0`、`HyperOS 3 增量号没有 OS 前缀时退回 name 也必须读出 3 系` |
| 9 | 配置通道在 OS3 走不走得通（签名广播 + 开机快照 + `Settings.Global`） | 机制与 ROM 大版本无关，且 `RECEIVER_EXPORTED` 要求 API 33+，OS3 = API 36，满足 | 代码侧事实核过（`ConfigSync.ensureRegistered`、`ConfigPayload.PERMISSION`），无需新增判据；真机上由心跳 `reg=ok` 正面确认 |

## 2. 证不死的（必须由 OS3 真机回答）+ 猜错的代价 + 兜底

| # | 未知 | 猜错的代价 | 兜底 / 怎么测 |
| --- | --- | --- | --- |
| A | **OS3 上输入侧到底收不收得到环的 MotionEvent**（`InputFilterEnabled` 是不是 true） | 我们押的是"收得到"（上游模块当年就是靠它工作的）。收不到的话只是少一条备援路，不影响功能 | 心跳第 8 段：`0/0` = 收不到（和 OS4 一样）；`0/N`（N>0）= 收得到且被仲裁器挡住 —— **这就是我们要的那一行证据** |
| B | **OS3 上 `mt350x` 光学传感器是否同名/存在** | 传感器路挂了而输入侧活着 → 输入侧会在 700ms 后接管，功能不丢，但手感是我们没调校过的那条 | 诊断页看环样本数是否在涨；心跳 `seq` 字段 |
| C | **`AXIS_SCROLL` 在 OS3 上是增量还是累计位置** | 直接影响"应用内转环跟不跟手"。我们让传感器路做主，恰恰是为了不依赖这个未知量 | 若真机反馈"OS3 上列表滚动量不对"，取一条 `hzr_debug 1` 的 logcat 就能定案 |
| D | **钩子签名在 Android 16 的 `InputManagerService` 里是否同名存在**（`filterInputEvent(InputEvent,int)` / `injectInputEvent` / `interceptKeyBeforeQueueing`） | 挂不上就是"没有输入侧"，退回今天 OS4 的形态（只有传感器路），不会炸系统 | 匹配是**反射按类名+方法名+参数简单名**（见 `HookPlan.matches`），挂不上只记 `onHookingFailure`，不吞任何事件；装机后看 logcat 里 `HookPlan.describe` 那一行 |
| E | **相机包名在 OS3 上是不是 `com.android.camera`**（白名单只有 `com.android.camera` / `com.xiaomi.camera` 两个） | 不在名单 → 相机里照样会触发我们的动作 | 本轮加了定向日志（见下），一次复现就能分清是"包名不对"还是"读到空" |

## 3. 定向日志（E 项就靠它）

打开：`adb shell settings put global hzr_debug 1`

两条新日志（1 秒节流，**故意不复用**那条已有的 2 秒节流 —— 两条挨太近会互相吞）：

- `FG 转场窗口内跳过 <动作> fg=<包名> cam=<true/false> 切换后 <N>ms` —— 闸门生效了
- `FG 执行注入 <动作> fg=<包名> cam=<true/false> 切换后 <N>ms` —— 真的执行了

判读口径：
- 相机里误触发时，第二条若打出 `fg=null` → 是**读空**（已被 40ms 失败缓存和转场窗口压住，应该越来越少）；
- 若打出 `fg=<某个我们没在白名单里的包名>` → 是**包名不对**，把那一行发回来，加一条白名单即可；
- 若 `fg=com.android.camera` 却仍然执行 → 说明是 `overrideCamera` 被开着，先看设置页那个开关。

## 4. 新增的热调键（都不用重装就能 A/B）

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `hzr_ring_lease_ms` | 700 | 双入口互斥的租约；`0` = 关掉仲裁（退回两条路都执行） |
| `hzr_fg_stable_ms` | 600 | 换应用后的观察窗，窗内不注手指；`0` = 关掉这条闸门 |
| `hzr_debug` | 0 | 打上面的定向日志与逐样本 `SMP` 行 |

## 5. 本机（OS4）回归红线

这一轮所有改动对本机的唯一要求是：**行为一个字都不能变**。理由是这台机器上没有"输入侧"这条路的样本，仲裁器与转场窗口的判定必须全是惰性或恰好有利：

- 仲裁器：输入侧永远不盖戳 → 传感器路永远不让路（测试名 `输入侧一个事件都没来过 传感器不许让路` 钉的就是这条）。
- 转场窗口：只在"刚换应用 600ms 内"拦一次注手指，之后照旧。副作用是**他刚打开某个 App 立刻转环**时可能第一拍没反应 —— 这一条是有意换来的，业主判观感。

装机复验步骤见 `HYPEROS3-TESTER.md` 的第 1、2 节，本机也按同一套判据走一遍。

## 6. 真机新增发现（2026-10-31，A15 虚拟机实测）：**第一个门不是 HyperOS 版本，是 LSPosed 版本**

用本机那台 `bench_a15`（AOSP Android 15 / x86_64 / Magisk + **LSPosed v1.11.0 (7209)**）装 cfg40，结果：

```
E/LSPosedContext  Failed to load class class xyz.nextalone.hyperzoomring.CrownModule
java.lang.NoSuchMethodException: CrownModule.<init>
    [interface io.github.libxposed.api.XposedInterface,
     interface io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam]
settings get global hzr_heartbeat → null
```

**模块彻底没跑**，而且它一次都没有尝试 `assets/xposed_init` 那条 legacy 入口。对照实验（把
`META-INF/xposed/java_init.list` 与 `module.prop` 从包里拿掉，只留 legacy）之后：

```
settings get global hzr_heartbeat → base.apk|47230047|…;0;0;;;0;0;0/0     ← 活着
W HyperZoomRing: mt350x sensor not found yet, retrying …（15 次后 Gave up）  ← 传感器不在也干净退场
```

结论三条，都直接影响"OS3 能不能跑"：

1. **只要酷友的 LSPosed 低于认 102 的那一版，双声明并存 = 模块静默不加载**（不是"部分功能没有"，是全都没有）。
   所以对外发布之前必须先定这条：要么只发 legacy 声明（哪版框架都能加载，代价是 LSPosed 2.2.1 里那条
   "使用已废弃 API"的警告会重新出现），要么在帖子和 README 里把"LSPosed 需 ≥2.2.x"写成硬性前提。
2. `module.prop` 里加 `minApiVersion=102` **在 v1.11.0 上不生效**（实测无效，它照样去实例化 `CrownModule` 然后炸），
   所以不能靠这个元数据把老框架引到 legacy 上。声明保留（对着 spec 写是对的），但不能当保险。
3. **`lastAcceptedAtMs=0` 时输入侧自己接管**这条兜底，现在有了真机证据（不再只是单测）：这台 A15 上
   没有 `mt350x` 传感器，传感器路 15 次重试后干净放弃、不崩不炸，输入侧的判据会走 `EXECUTE`。

顺带把相机那条 bug 定案了（原来的第三条候选已被排除）：真机日志是
`执行注入 scroll fg=com.android.camera cam=true 切换后 8015ms`，包名、前台、转场窗口全都判对，
它照样执行 —— 因为旧放行条件是"相机在前台**且未开 override_camera**"，而他这台是 `override_camera=true`。
cfg40 起改成硬规则：**相机在前台时，一切 `injectsTouch` 动作一票否决**（两条入口都拦，判据同一个
`ForegroundGate.allowsInjectedAction`），开关现在的语义只剩"相机里允不允许非注入类动作"。
