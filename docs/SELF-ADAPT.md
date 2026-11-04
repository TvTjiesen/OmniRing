# 自己适配 HyperOS 3（及更低）：从哪开始、卡在哪、怎么判断改对了

日期：2026-11-04 · 对应构建：`cfg49` / `versionCode 52` / `1.40.1`

## 0. 先看清楚这份文件是什么

**本项目的支持范围只有一条**：Xiaomi 17 Ultra（`nezha` / 25128PNA1C）+ HyperOS 4 / Android 17，实测过。
HyperOS 3（Android 16）及更低**不在支持范围、不保证可用**，也不再收这类系统的适配回执。

但源代码是公开的（GPL-3.0），所以"能不能在别的系统上跑"这个问题，答案不是"不能"，
而是"**没人替你保证，你要自己动**"。这份文件就是给愿意自己动的那个人的：
把我们已经证死的、证不死的、和"别乱删"的东西一次讲清楚，省掉你从零摸索的那几天。

如果你只是想装上试试、不打算改代码：直接装，装坏了卸载即可，但别指望我们处理 OS3 上的问题。

## 1. 开工前必读的三件事

### 1.1 先确认真实系统版本，别看商品名

适配卡的判定依据是 `Build.VERSION.SDK_INT` 与 `ro.mi.os.version.*`，不是机型名。改之前先读：

```bash
adb shell getprop ro.build.version.sdk          # 37 = Android 17 / OS4；36 = Android 16 / OS3
adb shell getprop ro.mi.os.version.name         # HyperOS 3.0 / HyperOS 4.0
adb shell getprop ro.build.version.incremental  # OS3.0.34.0.WPCCNXM 这类
```

版本串有两种真实写法，`DeviceInfo` 都要能读出来（`DeviceInfoTest` 已钉死）：
`OS3.0.34.0.WPCCNXM` → `HyperOS 3.0.34.0`；`3.0.301.3.WPAMIXM`（无 `OS` 前缀）→ 退回
`ro.mi.os.version.name` 得 `HyperOS 3.0`。**你自己适配时，如果适配卡显示版本为空或乱码，先修这里。**

### 1.2 想让它显示"已支持"，改的是这两个地方，不是删掉判定

```kotlin
// app/src/main/kotlin/xyz/nextalone/hyperzoomring/util/Compatibility.kt
const val TESTED_MIN_SDK = 37      // 你的实测 SDK 是多少就写多少
```

改了它，`CompatibilityTest` 里那条`Android 16（HyperOS 3）没实测过 不许报成通过`会跟着需要调整。
**原则：只有你真的在这台系统上跑通了 §3 的正面证据，才把数字降下来。**
把判定删掉、或者把 PARTIAL 直接当 PASS，只是把"我们不确定"变成"我们撒谎" —— 前者是诚实，后者会被人挂到论坛上。

### 1.3 别删这些防护

下面这些**不是**"OS3 适配代码"，是"两条入口并存的系统"的通用防护。删掉不会让它更能跑，
只会把"手感不对"升级成"滚两倍 / 音量跳两格 / 离散动作触发两遍"：

| 机制 | 在哪 | 为什么不能删 |
| --- | --- | --- |
| 双入口租约（默认 700ms） | `ring/RingSourceArbiter.kt` | OS3 上输入侧和传感器侧会各收到一次转动，仲裁器让"只有一个执行" |
| 租约到期自动接管 | 同上 | 传感器没了/改名了，输入侧 700ms 后自己干活，功能不丢 |
| 时钟倒挂防护（`age < 0` 判过期） | 同上 | 改系统时间或两个时钟混用时，按"负数小于租约"会让路到永久锁死 |
| 转场 600ms 闸门 | `hook/ForegroundGate.kt` | 刚换应用时 `getRunningTasks` 会给出上一个应用或读空，不挡就会往刚打开的相机里注手指 |
| 心跳第 8 段向后兼容 | `util/Heartbeat.kt` | 老版 7 段载荷还得能解析 |

## 2. 我们能证死的，你不用再证

九条，逐条都对应到具体单测，可以直接复跑（`./gradlew :app:testDebugUnitTest`）：

1. 双入口撞车 —— 已修，传感器路为主、输入侧降级为"只吞不执行"（`RingSourceArbiterTest` 5 条）
2. 传感器挂了会不会全灭 —— 不会，租约到期输入侧接管（`RingSourceArbiterTest` 2 条）
3. 时钟倒挂 —— 已修（`时间戳倒挂时不许让路`）
4. A/B 退路 —— `hzr_ring_lease_ms 0` 就是关掉仲裁，不重装就能退回老行为
5. 相机误触发 —— 两处修（失败值只缓存 40ms + 600ms 转场窗），`ForegroundGateTest` 7 条
6. 心跳 7 段/8 段兼容 —— 缺省为 0（`HeartbeatParseTest` 4 条）
7. `TESTED_MIN_SDK` 虚报 —— 已改成 37（`CompatibilityTest`）
8. 版本串两种写法 —— 都能读（`DeviceInfoTest` 2 条）
9. 配置通道在 OS3 走不走得通 —— 机制与 ROM 大版本无关，`RECEIVER_EXPORTED` 要 API 33+，OS3 是 36，满足

细节与每条的原始推理在 [`HYPEROS3-SELFCHECK.md`](HYPEROS3-SELFCHECK.md) §1。

## 3. 证不死的五条 —— 你的活儿就在这张表里

这五条**只能由真机回答**。我们没 OS3 机器，所以它们是空的，需要你填。

| # | 未知 | 猜错了会怎样 | 怎么测（关键） |
| --- | --- | --- | --- |
| A | OS3 上输入侧到底收不收得到环的 `MotionEvent`（`InputFilterEnabled` 是不是 true） | 收不到只是少一条备援路，功能不丢 | **心跳第 8 段**：`0/0` = 收不到（与 OS4 相同，走传感器）；`0/N`（N>0）= 收得到且被仲裁器挡住——这就是要的那一行证据。诊断页/反馈包里 `hzr_hb` 最后一段 |
| B | OS3 上 `mt350x` 光学传感器是否同名存在 | 传感器路挂了 → 输入侧 700ms 后接管，功能在，但手感是没调校过的那条 | 诊断页「环数据」样本数涨不涨；`hzr_debug 1` 后看 `SMP` 行 |
| C | `AXIS_SCROLL` 在 OS3 上是增量还是累计位置 | 直接影响"应用内转环跟不跟手" | 传感器路做主正是为了不依赖这个未知量；若反馈"滚动量不对"，`hzr_debug 1` 取一条 logcat 即可定案 |
| D | 钩子签名在 Android 16 的 `InputManagerService` 里是否同名存在（`filterInputEvent(InputEvent,int)` / `injectInputEvent` / `interceptKeyBeforeQueueing`） | 挂不上 = 没有输入侧，退回只有传感器路，不会炸系统 | 匹配靠**反射按类名+方法名+参数简单名**（`HookPlan.matches`），挂不上只记 `onHookingFailure`；装机后看 logcat 的 `HookPlan.describe` 那一行 |
| E | 相机包名在 OS3 上是不是 `com.android.camera`（白名单只有 `com.android.camera` / `com.xiaomi.camera`） | 不在名单 → 相机里照样会触发我们的动作 | `hzr_debug 1` 后看 `FG 执行注入 … fg=<包名>`；`fg=null` 是读空（已被压住），`fg=<陌生包名>` 就是包名不对，加一条白名单即可 |

**报告格式（如果还想发回上游）**：一张首页截图（看「技术路径」行）+ 反馈包 txt
（诊断页 →「反馈包」→「存成文件」，落在 `下载/OmniRing/`）。有了这个，A/B/C/E 基本当场可判。

## 4. 改代码之前的机器条件

- JDK 21 + Android SDK **platform 37 + build-tools 37**（`compileSdk = 37`；换到 36 要先改 `app/build.gradle.kts`）
- `localmaven/` **不在版本库里**（`.gitignore` 第 49 行）。本机到 `dl.google.com` 是超时，
  而 `assembleRelease` 需要 `org.jetbrains.kotlin:compose-group-mapping`，所以原作者机上是本地镜像：
  `localmaven/org/jetbrains/annotations/13.0`、`localmaven/org/jetbrains/kotlin/compose-group-mapping/2.3.20`、
  `localmaven/org/jetbrains/kotlin/kotlin-stdlib/2.1.21`。你网络正常就不需要它，反之照这个结构自己放。
- 构建：`./gradlew :app:assembleDebug`（约 47 MB，慢，但是真机调试用的）
  / `:app:assembleRelease`（R8 后约 4.7 MB，**别拿 debug 包量性能**）
  / `:app:testDebugUnitTest`（JVM 单测，买之前先跑一遍，基线 39 个 XML / 248 条断言 / 0 失败）

## 5. 发布你自己的构建：三件必须做的事

1. **改包名**。`app/build.gradle.kts:31` 的 `applicationId = "io.github.tvtjiesen.omniring"`，
   改成你自己的（`namespace` 不用动，它是 Kotlin 包名）。
   不改的话你和我这两个包会各自抢一次环，而且**互不覆盖**，用户装了两个就两个都在跑。
2. **签名**。不给 `KEYSTORE_FILE` 时 `assembleRelease` 会回落到 `~/.android/debug.keystore`
   （`app/build.gradle.kts:51-65`），和 debug 包同一张证书，能 `install -r` 覆盖——
   适合自用，不适合公开分发。公开就要自己的 keystore，注意换签名那一次必须卸载重装。
   **本项目正式签名的口令不在仓库里，也不会给你**（`E:\dev\keys\omniring-release.jks` 只属于业主）。
3. **GPL 义务**。保留 `LICENSE`、`MODIFICATIONS.md`、README 的署名与改动声明，
   再分发也用 GPL-3.0 公开源码。你改了什么就在 `MODIFICATIONS.md` 里加一条，
   别把上游和本 fork 的功劳抹掉。

另外：如果你想用 GitHub Actions 自动出包，`.github/workflows/build.yml` 在推 `v*` tag 时
会建 release，需要你自己配四个 secret（keystore 相关），别把密钥写进仓库。

## 6. 已知的"第一个门不是系统版本，是 LSPosed 版本"

实测（Android 15 虚拟机 + LSPosed v1.11.0）：老 LSPosed 只认 legacy 入口，模块能加载但两条钩子都不生效，
心跳里 `entry=legacy legacyHooked=…`。所以**系统版本对了也可能不工作**，先确认：

```bash
adb shell su -c 'pidof lspd'                       # 空 = 框架没在跑，什么都别谈
adb shell settings get global hzr_entry            # 看 entry=modern102 还是 entry=legacy
```

- `entry=modern102` = APK 里有 `META-INF/xposed/`，走新版 libxposed
- `entry=legacy` = 走老的 `assets/xposed_init`，能加载但 hook 可能不生效

商店下载的 `-signed-legacy.apk` **故意不带** `META-INF/xposed/`（给老 LSPosed 用）；
从源码自己构建出来的双入口包两个入口都在，优先走 modern。

## 7. 真有进展了怎么办

不发回上游也行（我们明确不保 OS3），但如果你修好了，欢迎在仓库开 issue 说明改了什么、
哪几条 §3 的未知被验证成了什么值。**有正面证据 + 我们重新拍板**，`TESTED_MIN_SDK` 是可以降的
—— 代码里的注释写的就是这个条件，别以为它是永久锁死的。

反过来，§1.3 那张表里的防护请务必保留，它们保护的是"两条入口并存"，与系统版本无关。
