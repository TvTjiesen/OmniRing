# 表冠统一逻辑 v2 —— 设计第一版

> **状态：设计稿，未开工。** 本文件只描述方案与影响面，不含任何已实施的代码改动。
> 依据：业主 2026-11-01 拍板的两条 ——「无方向动作直接不支持」「转速只影响幅度」，
> 以及「取消 顺时针/逆时针 × 快转/慢转 四槽位，动作随表冠转向定正负」。
> 请按 §8 的 6 个点回复修改意见；设计批了才动手（§7 的分步）。

## 0. 一句话结论

把「转向 × 转速」的**四槽位**塌成只看转向的**两槽位**（PLUS / MINUS）；
转速从"决定执行哪个动作"降级为"只决定幅度"。
**存储不迁移**：读时三级回落兼容旧 4 键，写只写新键，旧键留在盘上（回滚安全）。
底层 16 个动作 id 一个不动，心跳/广播/i18n 以外没有任何外部契约变化。

## 1. 现状与病灶（判据都是源码行，不是推测）

今天"这一把算哪个动作"由**两个各自独立**的分类器给出，判据都是「方向 × 瞬时速度」：

| 路 | 位置 | 速度判据 |
|---|---|---|
| 传感器路（HyperOS 4 上真正干活的那条） | `hook/SensorRingDriver.kt:128-137` | `fast = abs(model.velUnitsPerSec) > FAST_UNITS_PER_SEC` |
| 输入路（HyperOS 3 才有事件） | `ring/ZoomRingDetector.kt:11-31` | `fast = count > speedThreshold`（200ms 窗口内样本数） |

它解释了他报的两件事：

1. **「音量 / 滑动概率冲突」**：速度判据是**每样本瞬时**的，速度在阈值上下抖动 →
   相邻两个样本落到**不同槽位** → 两个不同动作交替执行。看起来就是"一转又滚又调音量"。
2. **「音量只能降不能升」**：四格里只要某格没配，`resolveActionId(...) ?: return`
   （`hook/SensorRingDriver.kt:178`、`hook/InputInterceptorHook.kt:347`）**当场丢掉这一把**；
   界面上那格写着"未设置"，但他看到的是"转了没反应 / 只能降"。

**关键事实：他要求的"转速只影响幅度"今天已经成立** ——

- 离散动作按 `discreteAccum += abs(model.velUnitsPerSec) / 60f` 累计，满
  `DISCRETE_STEP_UNITS` 且距上次 ≥60ms 才执行一次（`hook/SensorRingDriver.kt:271-276`）。
  转得越快 → 单位时间累计越多 → 执行越密，**这本身就是幅度**。
- 滚动/进度按「真实转过的单位数 × 增益」走（`scrollToTarget` + `TouchScrollInjector.pxPerUnit()`）。

所以这条需求**不需要新机制**：只要把速度从"槽位选择"里摘掉，两个 bug 同时消失。
这是本设计最大的简化点，也是不建议顺手重构幅度曲线的理由（一个变更只动一个变量）。

## 2. 目标模型

```kotlin
/** 表冠转向。PLUS = 顺时针（物理上机身刻度往下走），MINUS = 逆时针。 */
enum class CrownDir(val displayName: String) {
    PLUS("顺时针（＋）"),
    MINUS("逆时针（−）"),
}
```

- **槽位 = f(转向)**：`currentGesture()` 只剩方向分支，速度不再参与。
  同一个动作在快转/慢转下都是它，**一把只执行一个动作**。
- **无方向动作不支持**：`Action` 增加 `val directional: Boolean get() = true`，
  `LaunchAppAction` 覆盖为 `false`；动作选择列表过滤掉它。
- **转速 → 幅度**：保持今天的累计 + 60ms 节流数值**完全不动**，只把 `intensity` 的
  定义写进注释（它是给诊断/表盘用的 0..1 读数，不是离散动作的驱动量）。
- **16 个动作 id 不变**。`ActionRegistry` 仍注册 `launch_app`（注册表是"可用动作全集"），
  由 `directional` 决定能不能出现在两槽位里。
- 已存在的行为保持：`dirSign == 0` 按 PLUS 处理（`SensorRingDriver.kt:129` 的既有语义）；
  `ZoomRingDetector` 无方向信息时的 CW 兜底（`:27-29`）→ PLUS。

## 3. 存储与兼容（**不迁移，只读时兼容**）

沿用 `config/ConfigManager.kt:17-20` 已经验证过的模式（`migrateActionId`：
`scroll_up`/`scroll_down` 在读配置时就地归一成 `scroll`，不写回）。

### 3.1 键名

| 作用域 | 新规范键（只写这个） | 读取回落链 |
|---|---|---|
| 全局 | `gesture_PLUS_action_id` / `_action_config` | `…PLUS…` → `gesture_CW_SLOW_*` → `gesture_CW_FAST_*` |
| 全局 | `gesture_MINUS_action_id` / `_action_config` | `…MINUS…` → `gesture_CCW_SLOW_*` → `gesture_CCW_FAST_*` |
| 分应用 | `app_<pkg>_gesture_PLUS_action_id` / `_action_config` | 同上，前缀换成 `app_<pkg>_` |
| 分场景 | `scene_<key>_gesture_PLUS_action_id` / `_action_config` | 同上，前缀换成 `scene_<key>_` |

### 3.2 三个必须提前挡住的陷阱

1. **`DefaultMapping.shouldSeed` 必须走同一个回落链**（`config/DefaultMapping.kt:28-29`
   现在是 `GestureType.entries.all { existing(it) == null }`）。若只查新键，那么**只写旧键的老用户**
   在升级后会被判成"全空"→ 被重新种上 "scroll"，**把他的自定义映射冲掉**。
   判据必须是"PLUS 与 MINUS 按回落链都解析不出东西"。
2. **`removeConfiguredPackage` 要清 6 份键/包**（`config/ConfigManager.kt:158-161`）：
   新 2 键 + 旧 4 键，否则残留的旧键会在下次回落时"复活"一个已经删掉的包配置。
3. **配置推给钩子是整份 prefs**：`util/ConfigPush.kt:75` `ConfigPayload.encode(p.all)` ——
   旧键会一起推过去，所以**钩子侧的回落链也能读到旧键**，两侧行为一致，不需要额外通道。

### 3.3 旧配置里"快转和慢转不一样"怎么办

按回落链**取慢转那条**，并在界面那一行下面给一条一次性提示：
「旧版的快转映射不再区分方向，现在这一侧是慢转那条（原来的快转是：xxx）」。
→ 取舍见 §8 第 1 点（我推荐取慢转：慢转是基准手势，快转多半是顺手的重复配置）。

### 3.4 旧配置指向 `launch_app`

按 §3.1 的归一函数（与 `scroll_up/down` 同一条路）**回落成 `scroll`**，界面标注。
不给"转了什么也不发生"——那是最难查的一类失效。→ 取舍见 §8 第 2 点。

### 3.5 `speed_threshold` 的归宿

`speed_threshold`（`ConfigManager.kt:46-48`，首页滑条 `ui/screen/HomeScreen.kt:166-173`）
**保留**：它仍然喂 `ZoomRingDetector.currentIntensity`（幅度读数）。
只把文案从"速度阈值"改成"转速灵敏度（只影响幅度，不再切换动作）"。

## 4. 影响面（对外契约）

| 契约 | 影响 | 处置 |
|---|---|---|
| 心跳 `hzr_heartbeat` | 字段数 8、顺序、`;` 分隔**全不变**；第 4 段 `gesture` 取值词汇 4→2（`CW_SLOW`→`PLUS`） | 无需改格式；`Heartbeat.parse` 是字符串透传（`util/Heartbeat.kt:184-203`），老 App 照读 |
| 探针 `hzr_map`（cmd 128） | 行文 `CW_SLOW:scroll\|CW_FAST:scroll\|…` → `PLUS:scroll\|MINUS:scroll` | `hook/DebugProbe.kt:75` 跟着枚举走，自动缩短 |
| 探针 `hzr_cfg`（cmd 512） | 末尾 `CW_SLOW=$cw` → `PLUS=$cw` | `hook/DebugProbe.kt:140-150` |
| 环事件广播 extras | `EXTRA_GESTURE` 词汇同上 | `hook/InputInterceptorHook.kt:333`；`ui/MainActivity.kt:326-330` 的兜底分支改成认 `CW_*`/`CCW_*` 前缀（+12/−12），**兼容旧 hook 版本** |
| 诊断页显示 | 只显示字符串（`ui/screen/DiagnosticScreen.kt:135,183`） | 无需改，词汇自动变 |
| 四语 strings | 删 4 键 ×4 语言、增 2 键 ×4 语言 → **234 → 226** | `tools/i18n_check.py` 只查四语互相对齐、无总数断言 ✅ |
| `tools/deploy_verify.sh` | **不用改**（已核对 203 行：它只 `settings get` 读 `hzr_map`/`hzr_cfg`/`hzr_probe`，不做字符串比对） | — |
| 心跳第 8 段输入侧计数、广播 action/权限名（`BuildConfig.APPLICATION_ID` 派生）、`RingSourceArbiter` 700ms 租约、`RingEase`/`CrownModel` 手感数字 | **全都不动** | — |

## 5. 改动清单（逐文件，工程可直接照做）

| # | 文件 | 位置 | 改法 |
|---|---|---|---|
| 1 | `ring/GestureType.kt` | 全文 4 值 | → `ring/CrownDir.kt`：2 值（`git mv`，语义改名，`displayName` 保留） |
| 2 | `hook/SensorRingDriver.kt` | `:128-137` | `currentGesture(): CrownDir` 去 `fast`；`:174` `Heartbeat.onGesture(gesture.name)`；`:299/317/333` 三处形参类型；`:270-273` `LaunchAppAction` 特例加 `directional` 闸门（见 #6） |
| 3 | `hook/InputInterceptorHook.kt` | `:322-333` | `detector.onEvent` 返回类型随之；`EXTRA_GESTURE` 词汇随之；`:350-352` 同 #6 |
| 4 | `ring/ZoomRingDetector.kt` | `:11-31` | 返回 `CrownDir`；删 `fast` 分支，`currentIntensity` 公式不动 |
| 5 | `config/ConfigManager.kt` | `:24-44 / :158-195 / :198-234` | 键名换 `PLUS`/`MINUS`；新增 `legacyActionKeys(dir)` 回落读；`removeConfiguredPackage` 清 6 键；`resolveActionId/Config` 签名换类型 |
| 6 | `action/Action.kt` + `action/actions/LaunchAppAction.kt` | `Action.kt:6-19` | 接口加 `val directional: Boolean get() = true`；`LaunchAppAction` 覆盖 `false`；两个分发点（#2/#3）不再给它塞 `targetPackage` |
| 7 | `config/DefaultMapping.kt` | `:21-31` | 两条默认（PLUS/MINUS → `scroll`）；`shouldSeed` 用回落链（§3.2 陷阱 1） |
| 8 | `ui/Labels.kt` | `:19-24` | 2 个 label；`ui/CrownStage.kt:89-95` 兜底映射改前缀（`CW_*`→+12 / `CCW_*`→−12） |
| 9 | `ui/screen/{GestureListScreen,AppGestureOverviewScreen,SceneGestureOverviewScreen,AppListScreen,SceneListScreen}.kt` | `:41 / :43 / :45 / :71 / :33` | 全部 `CrownDir.entries`（自动从 4 行变 2 行，不用手写） |
| 10 | `ui/screen/GestureConfigScreen.kt` | `:57-60` | 动作列表过滤 `directional`（`launch_app` 那行消失；`AppPickerDialog` 随之不可达，**本轮不删**） |
| 11 | `ui/screen/HomeScreen.kt` | `:166-173` | 滑条文案改成"只影响幅度" |
| 12 | `hook/DebugProbe.kt` | `:75 / :140-150` | 自动（枚举跟着变），只改 `:141` 的字面量常量名 |
| 13 | `res/values*/strings.xml` ×4 | `:159-162` | 删 `gesture_cw_slow/cw_fast/ccw_slow/ccw_fast`，增 `gesture_plus`/`gesture_minus` |
| 14 | 测试（见 §6） | — | 改写 + 新增 |

不改：`MainActivity.kt:99`（`GestureConfig` 路由的数据类型随 #1 自动换）、`ActionRouter`（只认 actionId，
与槽位无关）、`RingSourceArbiter`、`ScrollAction`/`TouchScrollInjector`/`CrownModel`、
`MobileConfig`/广播 action/权限名。

## 6. 测试计划

**改写**（现有 219 条断言里涉及槽位的）：
`ConfigManagerTest`（`CW_FAST`→`PLUS` 等）、`DefaultMappingTest`、`ZoomRingDetectorTest`（4 槽断言 → 方向断言）、
`ConfigPayloadTest`（键名样例）、`HeartbeatParseTest` / `StatusJudgeTest`（词汇 `CW_SLOW`→`PLUS`）。

**新增（真断言，PC 上跑得动）**：

1. **回落链**：只写 `gesture_CW_SLOW_action_id=volume_up` → `getActionId(PLUS) == "volume_up"`；
   只写 `gesture_CW_FAST_action_id=volume_down` → 也解析得出（兜底段）；
   两个旧键都在且不同 → 取 `CW_SLOW`；新键存在 → 新键压过旧键（三作用域各测一遍）。
2. **不分裂（本设计的主证）**：同一方向、速度在阈值上下抖动的**一串样本**，
   新 `currentGesture()` 恒返回同一个 `CrownDir`；配套断言"这一串样本只产出**一个** actionId"
   —— 这是"音量/滑动冲突"被结构性修掉的可执行证据。
3. **无方向动作**：`LaunchAppAction.directional == false`；`allForScope(...)` 里不含它；
   旧配置指向 `launch_app` → 归一成 `scroll`。
4. **不需要迁移**：旧 4 键全在、新键全空的存档，`shouldSeed` **为 false**（防覆盖，§3.2 陷阱 1）。
5. **注册表不缩水**：`ActionRegistry.all()` 仍是 **16** 个 id（防误删）。
6. **方向翻转清累计**（若 §8 第 6 点采纳）：`dirSign` 变化时 `discreteAccum` 归零，
   配一个"回手不多走一格"的断言。

跑法：`export PATH="/e/dev/msys64/usr/bin:$PATH"` 后
`./gradlew --offline :app:testDebugUnitTest --rerun`，读
`app/build/test-results/testDebugUnitTest/*.xml` 的 `tests/failures/errors`。
（缺 cygpath 会让 gradle"假成功"，见 HANDOVER §10 末。）

## 7. 分步实施（**设计未批不动手**）

| 步 | 内容 | 出口判据 | 回滚 |
|---|---|---|---|
| P0 | 只改模型层：#1-#7 + 测试 | 单测全绿（约 226 条，0 失败）；**不装机** | `git revert` 单提交 |
| P1 | UI 与文案：#8-#13 | 单测全绿 + 四语 226/226 | 同上 |
| P2 | `cfg46` 装机验收 | `tools/deploy_verify.sh`（探针 `build=cfg46` + 心跳带新签名 + `hzr_map` 两槽） | 旧键仍在盘上，装回 cfg45 即恢复旧行为 |

每步一个**本地提交**（不 push），`MODIFICATIONS.md` 追加一轮。
回滚安全性来自 §3：旧 4 键我们**只读不删**，退回旧版 App 读到的仍是旧键原值。

## 8. 待业主拍板的 6 个点（请按编号回）

1. **旧"快转/慢转配得不一样"时取哪条？** 我推荐**取慢转**（慢转是基准手势，快转多半是顺手重复），
   界面给一次性提示。备选：取快转。
2. **旧配置指向 `launch_app`（启动应用）时怎么办？** 我推荐**回落成"滚动"**（和 `scroll_up/down` 的先例一致，
   避免"转了没反应"），界面标注。备选：当成"未设置"（该侧不动作）。
3. **两个槽位的出厂默认值**：我推荐**维持都 = `scroll`**（默认行为零变化，改由你手动点）。
   备选：PLUS=音量+ / MINUS=音量−（一上手就是"转环调音量"）。
4. **界面文案**：「顺时针（＋）」「逆时针（−）」（我推荐，和 §2 命名一致），
   或「顺时针」「逆时针」不带符号，或「顺时针 / 加」「逆时针 / 减」。
5. **幅度**：本步**完全不改**"转速→幅度"的数值（我推荐：一个变更只动一个变量，
   你现在的累计值手感是 cfg44/45 上验证过的）。备选：顺手把 `intensity` 统一成一条新曲线
   （多一个变量，手感要重新定案）。
6. **方向翻转时是否清空离散累计？** 我推荐**清**：现在 `discreteAccum` 是全局一份
   （`SensorRingDriver.kt:271`），顺时针攒到一半再回手，那半格会算到**逆时针那个动作**头上，
   表现是"回手多走一格"。清零 + 一条断言即可。

> 与本设计**无关**的一条旧待办仍在挂着：他机上 `hzr_scrub_y=0.27` vs 代码默认/README `0.45`，
> 按 HANDOVER §5 必须他本人拍，我不自作主张。