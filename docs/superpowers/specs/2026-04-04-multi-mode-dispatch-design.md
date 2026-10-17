# 变焦环三模式分发 — 需求设计文档

**日期**: 2026-04-04
**状态**: 已评审

## 背景

HyperZoomRing 当前只支持全局配置：4 种手势（CW_SLOW/CW_FAST/CCW_SLOW/CCW_FAST）固定映射到动作（音量、亮度、启动应用）。用户需要更灵活的分发方式，根据前台应用或系统状态使用不同的手势映射。

## 需求

### 三种互斥模式

用户在 UI 中选择一种生效模式（`DispatchMode`）：

1. **全局模式**（现有行为）— 所有应用统一使用同一套 4 手势映射
2. **分应用模式** — 每个应用可独立配置 4 手势映射，未配置的应用回退到「默认映射」
3. **分场景模式** — 根据系统状态自动切换手势映射，无场景命中时回退到「默认映射」

三种模式互斥，同时只有一种生效。

### 相机覆盖

新增全局开关 `overrideCamera`：
- **关闭**（默认）：相机前台时 ring 事件透传，不执行自定义动作（现有行为）
- **开启**：相机前台时也执行自定义动作

此开关与三种模式独立，是硬开关——`overrideCamera=false` 时相机永远透传，无论当前模式或是否给相机配了专属手势。

### 分应用模式细节

- 从已安装应用列表中选择要配置的应用（`PackageManager` 获取有 launcher intent 的应用）
- 每个已配置应用独立设置 4 手势 → 动作映射
- 未在列表中的应用使用「默认映射」（等同于全局模式的配置，复用现有 `gesture_*` 键）
- 支持添加/删除已配置应用

### 分场景模式细节

一期支持 3 个场景：

| 场景 | 检测方式 | 备注 |
|------|---------|------|
| 媒体播放中 | `AudioManager.isMusicActive` | 后台有音乐/视频/播客播放 |
| 手电筒已开启 | `CameraManager.TorchCallback` 缓存状态 | 系统手电筒（控制中心磁贴） |
| 全屏模式 | Hook WindowManagerService 追踪沉浸式状态 | **实验性**，Hook 失败则不可用 |

**场景优先级**（多场景同时满足时）：媒体播放 > 手电筒 > 全屏

每个场景独立配置 4 手势 → 动作映射。无场景命中时使用「默认映射」。

### 手电筒亮度动作（场景专属）

新增 `FlashlightBrightnessUp` / `FlashlightBrightnessDown` 动作：
- 通过 `CameraManager.turnOnTorchWithStrengthLevel()` (Android 13+) 调节手电筒亮度档位
- **仅在手电筒场景的配置页面中可选**，其他场景/模式的配置页面不显示此动作
- `intensity` 参数决定步进大小

### 配置存储

SharedPreferences 键格式（`hyperzoomring_config`）：

| 键 | 说明 |
|---|---|
| `dispatch_mode` | `"global"` / `"per_app"` / `"per_scene"` |
| `override_camera` | Boolean |
| `gesture_{NAME}_action_id` | 全局/默认映射（现有键，零迁移） |
| `gesture_{NAME}_action_config` | 全局/默认动作参数 |
| `app_{pkg}_gesture_{NAME}_action_id` | 分应用映射 |
| `app_{pkg}_gesture_{NAME}_action_config` | 分应用动作参数 |
| `per_app_configured_packages` | 逗号分隔的已配置包名 |
| `scene_{sceneKey}_gesture_{NAME}_action_id` | 分场景映射 |
| `scene_{sceneKey}_gesture_{NAME}_action_config` | 分场景动作参数 |

### UI 结构

HomeScreen 布局（自上而下）：
```
[模块状态] 启用/禁用开关
[模式选择] → ModeConfigScreen（3 选 1 单选）
[相机设置] 覆盖相机应用开关
[手势配置] 按当前模式显示：
  全局: 4 手势配置入口
  分应用: 默认配置 4 手势 + "管理应用" → AppListScreen
  分场景: 默认配置 4 手势 + "管理场景" → SceneListScreen
[速度阈值] Slider
```

新增页面：
- **ModeConfigScreen** — 模式选择
- **AppListScreen** — 已配置应用列表 + 添加应用
- **AppPickerDialog** — 应用选择器（搜索 + LazyColumn）
- **AppGestureOverviewScreen** — 单个应用的 4 手势配置入口
- **SceneListScreen** — 3 场景列表
- **SceneGestureOverviewScreen** — 单个场景的 4 手势配置入口

**GestureConfigScreen** 复用，增加 `ConfigScope` 参数区分读写目标。

### 约束与边界

1. 分场景下默认映射未配置时，ring 无动作——UI 应提示"默认配置未设置"
2. `overrideCamera=false` 是硬开关，优先于分应用中对相机应用的配置
3. 全屏检测标记为「实验性」，Hook 失败时该场景不可用
4. 分应用模式下 `getConfiguredPackages()` 需缓存，避免每次 ring 事件解析字符串
5. Action 的场景可用性通过接口属性（`sceneOnly: SceneType?`）控制，不硬编码类型检查
