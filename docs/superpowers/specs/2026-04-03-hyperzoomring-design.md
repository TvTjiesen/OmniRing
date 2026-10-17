# HyperZoomRing Design Spec

## Overview

基于 YukiHookAPI 的 LSPosed 模块，拦截小米 17 Ultra 大师变焦环的输入事件，将旋转手势映射为用户自定义的系统动作（音量、亮度、启动应用等）。

## 目标

- 在非相机场景下赋予变焦环额外功能
- 支持 2 种手势：快转、慢转（硬件不区分方向，后续可扩展）
- 每种手势可绑定一个 Action
- 提供 Miuix 风格的 Compose 设置界面

## 非目标

- 不修改相机内的变焦环行为
- 不适配小米 17 Ultra 以外的机型（初始版本）
- 不做自定义手势录制

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Kotlin 2.0+ |
| Hook 框架 | YukiHookAPI |
| UI | Jetpack Compose + Miuix UI (`top.yukonga.miuix.kmp:miuix-ui`) |
| minSdk | 35 (Android 16) |
| targetSdk | 35 |
| 构建 | Gradle KTS |
| 包名 | `xyz.nextalone.hyperzoomring` |

## 架构

### Hook 层（运行在 system 进程）

```
变焦环硬件
  → Linux /dev/input/eventX
    → Android InputManagerService
      → [Hook 拦截点] InputInterceptorHook
        → ZoomRingDetector（方向 + 速度 → GestureType）
          → ActionDispatcher（查配置 → 执行 Action）
```

Hook 目标进程：`android`（system_server）

**Hook 点**：`com.android.server.input.InputManagerService` 中的输入事件分发方法。通过设备名 `maxic, focus_ring_bridge`（vendor=`0x9120`, product=`0x9126`）识别变焦环设备。

### 硬件事件特征（实机验证）

| 项 | 值 |
|---|---|
| Input Device | `/dev/input/event7` |
| Device Name | `maxic, focus_ring_bridge` |
| Vendor ID | `0x9120` |
| Product ID | `0x9126` |
| Event Type | `EV_REL` (`0x0002`) |
| Event Code | `REL_WHEEL` (`0x0008`) |
| 震动反馈 | `event6` 发送 `EV_FF`（独立设备） |

**双模式行为（实机验证）**：

| 场景 | REL_WHEEL 值 | 顺/逆区分 |
|---|---|---|
| 非相机（默认） | `0x06` (6) | 不区分 |
| 相机内 | `0xffffffff` (-1) | 不区分 |

顺时针和逆时针旋转在两种模式下均产生相同值。相机 app 会切换变焦环工作模式（值从 6 变为 -1），但方向信息不在标准 input 事件中。方向数据推测来自独立通道（sysfs、HIDL 服务或光学传感器专有接口）。

初始版本不区分方向，后续逆向方向解析逻辑后可扩展为 4 手势。

### 手势识别

```kotlin
enum class GestureType {
    ROTATE_SLOW,   // 慢转
    ROTATE_FAST    // 快转
}
```

**速度判定**：计算单位时间窗口（如 200ms）内的事件次数。每个事件固定值为 6，因此速度 = 事件频率。超过阈值为快转，否则为慢转。阈值可在设置中调整。

### Action 系统

```kotlin
interface Action {
    val id: String
    val displayName: String
    fun execute(context: Context, intensity: Float)
}
```

`intensity`（0.0~1.0）基于旋转速度，允许 Action 做连续调节。

内置 Action：

| Action | 说明 |
|---|---|
| `VolumeAction` | 调节媒体音量，intensity 映射步长 |
| `BrightnessAction` | 调节屏幕亮度 |
| `LaunchAppAction` | 启动指定应用（仅触发一次） |

### 配置管理

- App 侧：`SharedPreferences`（MODE_WORLD_READABLE）
- Hook 侧：`XSharedPreferences` 读取
- Key 格式：`gesture_{GESTURE_TYPE}_action_id`、`gesture_{GESTURE_TYPE}_action_config`
- 速度阈值：`speed_threshold`（Int，单位为 200ms 内的事件数，默认值 5）

## 项目结构

```
app/src/main/java/xyz/nextalone/hyperzoomring/
├── HookEntry.kt                    # YukiHookAPI 入口
├── hook/
│   └── InputInterceptorHook.kt     # Hook InputManagerService
├── ring/
│   ├── ZoomRingEvent.kt            # 原始事件数据类
│   ├── ZoomRingDetector.kt         # 手势检测器
│   └── GestureType.kt              # 手势枚举
├── action/
│   ├── Action.kt                   # 动作接口
│   ├── ActionRegistry.kt           # 动作注册表
│   └── actions/
│       ├── VolumeAction.kt
│       ├── BrightnessAction.kt
│       └── LaunchAppAction.kt
├── config/
│   └── ConfigManager.kt            # 配置读写
└── ui/
    ├── MainActivity.kt             # Compose Activity
    └── screens/
        ├── HomeScreen.kt           # 模块状态 + 手势列表
        └── GestureConfigScreen.kt  # 手势动作配置
```

## UI 设计

### HomeScreen

- 顶部：模块激活状态指示（YukiHookAPI 检测）
- 中间：2 张手势卡片（快转/慢转），显示当前绑定动作，点击进入配置
- 底部：速度阈值滑块

### GestureConfigScreen

- 标题：当前手势类型
- 动作选择列表（RadioButton 风格）
- LaunchAppAction 时：弹出已安装应用选择器

### 主题

使用 `MiuixTheme`，跟随系统深色/浅色模式。

## 阶段一：事件监听与数据验证

在实现动作分发之前，先做一个诊断模式，采集变焦环的真实事件数据，验证 Hook 方案和事件特征。

### 诊断功能

1. **事件监听 Hook**：Hook InputManagerService，拦截 `focus_ring_bridge` 设备的所有事件，记录完整的事件流
2. **数据记录**：每个事件记录 timestamp、event type、code、value，写入日志
3. **UI 展示**：设置界面增加"诊断"页面，实时显示：
   - 最近 N 条事件的原始数据
   - 事件频率（events/sec）
   - 检测到的速度等级（快/慢）
   - 设备信息（name、vendor、product 确认）
4. **数据导出**：支持将采集的事件日志导出为文件，方便分析

### 验证清单

- [ ] Hook 能成功拦截到变焦环事件
- [ ] 设备识别逻辑正确（通过 device name 匹配）
- [ ] 事件值与 getevent 观察一致
- [ ] 速度检测算法的阈值合理
- [ ] 相机内/外的模式切换能被检测到
- [ ] 事件拦截后不影响系统稳定性

### 实现优先级

诊断模式是 **第一个交付物**。验证通过后再实现手势识别和动作分发。

## 后续逆向方向

方向信息不在标准 input 事件中，需要找到专有通道。调查路径：
- 反编译小米相机 APK，搜索 `focus_ring_bridge` 或 `REL_WHEEL` 相关处理逻辑
- 检查 `/sys/` 或 `/proc/` 下是否有 maxic 相关的 sysfs 节点提供方向数据
- 检查是否有专用的 HIDL/AIDL 服务（如 `vendor.xiaomi.hardware.zoomring`）
- Hook `InputReader` / `InputDispatcher` native 层，观察事件是否被增强
- 搜索 `maxic` 驱动源码（如果有 kernel 源码公开）

一旦找到方向解析方式，手势模型可扩展为 4 种（顺/逆 × 快/慢）。

## 风险

| 风险 | 缓解 |
|---|---|
| 变焦环事件不经过标准 InputManagerService | 备选方案：Hook 小米自定义的输入处理类 |
| 系统进程 Hook 导致 bootloop | 模块内加 try-catch，失败时放行所有事件 |
| XSharedPreferences 读取失败 | 降级为默认配置，不做静默忽略 |
| MIUI/HyperOS 版本更新改变 Hook 点 | 模块内做版本检测和降级提示 |
