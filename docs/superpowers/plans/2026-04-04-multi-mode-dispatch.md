# 变焦环三模式分发 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将变焦环事件分发从全局固定映射扩展为三选一模式（全局/分应用/分场景），并新增手电筒亮度专属动作。

**Architecture:** ConfigManager 通过 `resolveActionId(gesture, foregroundPkg, activeScene)` 统一三种模式的分发逻辑。Hook 层 `InputInterceptorHook` 获取前台应用和场景状态后委托 ConfigManager 解析。UI 层通过 `ConfigScope` sealed class 复用 GestureConfigScreen。

**Tech Stack:** Kotlin, Jetpack Compose, Miuix UI, YukiHookAPI, SharedPreferences

**Spec:** `docs/superpowers/specs/2026-04-04-multi-mode-dispatch-design.md`

---

### File Structure

**新建文件：**
| 文件 | 职责 |
|------|------|
| `config/DispatchMode.kt` | 分发模式枚举 |
| `config/SceneType.kt` | 场景类型枚举 |
| `config/ConfigScope.kt` | 配置作用域 sealed class |
| `action/actions/FlashlightAction.kt` | 手电筒亮度 Up/Down Action |
| `hook/SceneDetector.kt` | 场景检测（媒体/手电筒/全屏） |
| `ui/screen/ModeConfigScreen.kt` | 模式选择页面 |
| `ui/screen/AppListScreen.kt` | 已配置应用列表页面 |
| `ui/screen/AppPickerDialog.kt` | 应用选择器对话框 |
| `ui/screen/AppGestureOverviewScreen.kt` | 单应用手势概览 |
| `ui/screen/SceneListScreen.kt` | 场景列表页面 |
| `ui/screen/SceneGestureOverviewScreen.kt` | 单场景手势概览 |

**修改文件：**
| 文件 | 变更 |
|------|------|
| `config/ConfigManager.kt` | 新增 dispatchMode, overrideCamera, 分应用/分场景读写, resolveActionId/Config, 缓存 |
| `action/Action.kt` | 新增 `sceneOnly: SceneType?` 属性 |
| `action/ActionRegistry.kt` | 注册 FlashlightBrightness 动作, 新增 `allForScope()` |
| `hook/InputInterceptorHook.kt` | getForegroundPackage, 相机覆盖逻辑, SceneDetector 集成 |
| `ui/MainActivity.kt` | Screen sealed class 导航 |
| `ui/screen/HomeScreen.kt` | 模式选择、相机覆盖、按模式显示手势区 |
| `ui/screen/GestureConfigScreen.kt` | 增加 ConfigScope 参数, 按 scope 过滤 Action |

---

### Task 1: 数据模型 — DispatchMode, SceneType, ConfigScope

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/config/DispatchMode.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/config/SceneType.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/config/ConfigScope.kt`

- [ ] **Step 1: 创建 DispatchMode 枚举**

```kotlin
package xyz.nextalone.hyperzoomring.config

enum class DispatchMode(val key: String, val displayName: String) {
    GLOBAL("global", "全局模式"),
    PER_APP("per_app", "分应用模式"),
    PER_SCENE("per_scene", "分场景模式"),
    ;

    companion object {
        fun fromKey(key: String): DispatchMode =
            entries.find { it.key == key } ?: GLOBAL
    }
}
```

- [ ] **Step 2: 创建 SceneType 枚举**

```kotlin
package xyz.nextalone.hyperzoomring.config

enum class SceneType(val key: String, val displayName: String) {
    MEDIA_PLAYING("media_playing", "媒体播放中"),
    FLASHLIGHT_ON("flashlight_on", "手电筒已开启"),
    FULLSCREEN("fullscreen", "全屏模式（实验性）"),
}
```

- [ ] **Step 3: 创建 ConfigScope sealed class**

```kotlin
package xyz.nextalone.hyperzoomring.config

sealed class ConfigScope {
    data object Global : ConfigScope()
    data class App(val packageName: String) : ConfigScope()
    data class Scene(val scene: SceneType) : ConfigScope()
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```
jj commit -m "feat: add DispatchMode, SceneType, ConfigScope data models"
```

---

### Task 2: Action 接口扩展 + FlashlightAction

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/Action.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/actions/FlashlightAction.kt`
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/ActionRegistry.kt`

- [ ] **Step 1: Action 接口增加 sceneOnly 属性**

在 `Action.kt` 中添加默认实现：

```kotlin
package xyz.nextalone.hyperzoomring.action

import android.content.Context
import xyz.nextalone.hyperzoomring.config.SceneType

interface Action {
    val id: String
    val displayName: String
    val sceneOnly: SceneType? get() = null  // null = 所有场景可用
    fun execute(context: Context, intensity: Float)
}
```

- [ ] **Step 2: 创建 FlashlightAction**

```kotlin
package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.config.SceneType

object FlashlightBrightnessUpAction : Action {
    override val id = "flashlight_brightness_up"
    override val displayName = "手电筒亮度增大"
    override val sceneOnly = SceneType.FLASHLIGHT_ON

    override fun execute(context: Context, intensity: Float) {
        adjustFlashlight(context, intensity, up = true)
    }
}

object FlashlightBrightnessDownAction : Action {
    override val id = "flashlight_brightness_down"
    override val displayName = "手电筒亮度减小"
    override val sceneOnly = SceneType.FLASHLIGHT_ON

    override fun execute(context: Context, intensity: Float) {
        adjustFlashlight(context, intensity, up = false)
    }
}

private fun adjustFlashlight(context: Context, intensity: Float, up: Boolean) {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
    try {
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return

        val chars = cm.getCameraCharacteristics(cameraId)
        val maxLevel = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: return
        if (maxLevel <= 1) return  // 不支持亮度调节

        // 从缓存获取当前档位，或默认中间档
        val current = currentLevel.coerceIn(1, maxLevel)
        val step = (intensity * (maxLevel / 4f)).toInt().coerceAtLeast(1)
        val newLevel = if (up) {
            (current + step).coerceAtMost(maxLevel)
        } else {
            (current - step).coerceAtLeast(1)
        }

        cm.turnOnTorchWithStrengthLevel(cameraId, newLevel)
        currentLevel = newLevel
    } catch (e: Exception) {
        Log.w("HyperZoomRing", "Failed to adjust flashlight brightness", e)
    }
}

@Volatile
private var currentLevel: Int = 1
```

- [ ] **Step 3: ActionRegistry 注册 + 添加 allForScope 方法**

修改 `ActionRegistry.kt`:

```kotlin
package xyz.nextalone.hyperzoomring.action

import xyz.nextalone.hyperzoomring.action.actions.*
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType

object ActionRegistry {
    private val actions = mutableMapOf<String, Action>()

    init {
        register(VolumeUpAction)
        register(VolumeDownAction)
        register(BrightnessUpAction)
        register(BrightnessDownAction)
        register(LaunchAppAction)
        register(FlashlightBrightnessUpAction)
        register(FlashlightBrightnessDownAction)
    }

    private fun register(action: Action) { actions[action.id] = action }
    fun get(id: String): Action? = actions[id]
    fun all(): List<Action> = actions.values.toList()

    fun allForScope(scope: ConfigScope): List<Action> = actions.values.filter { action ->
        val sceneOnly = action.sceneOnly ?: return@filter true
        scope is ConfigScope.Scene && scope.scene == sceneOnly
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```
jj commit -m "feat: add sceneOnly to Action interface, FlashlightBrightness actions, allForScope"
```

---

### Task 3: ConfigManager 扩展

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/config/ConfigManager.kt`
- Test: `app/src/test/kotlin/xyz/nextalone/hyperzoomring/config/ConfigManagerTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/kotlin/xyz/nextalone/hyperzoomring/config/ConfigManagerTest.kt`:

```kotlin
package xyz.nextalone.hyperzoomring.config

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import xyz.nextalone.hyperzoomring.ring.GestureType

class ConfigManagerTest {

    private lateinit var store: FakePrefsStore
    private lateinit var config: ConfigManager

    @Before
    fun setup() {
        store = FakePrefsStore()
        config = ConfigManager.forTest(store)
    }

    @Test
    fun `global mode returns global action id`() {
        config.dispatchMode = DispatchMode.GLOBAL
        config.setActionId(GestureType.CW_FAST, "volume_up")
        assertEquals("volume_up", config.resolveActionId(GestureType.CW_FAST, "com.example", null))
    }

    @Test
    fun `per app mode returns app action when configured`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        config.setAppActionId("com.example", GestureType.CW_FAST, "brightness_up")
        config.setActionId(GestureType.CW_FAST, "volume_up")

        assertEquals("brightness_up", config.resolveActionId(GestureType.CW_FAST, "com.example", null))
    }

    @Test
    fun `per app mode falls back to default for unconfigured app`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.setActionId(GestureType.CW_FAST, "volume_up")

        assertEquals("volume_up", config.resolveActionId(GestureType.CW_FAST, "com.other", null))
    }

    @Test
    fun `per scene mode returns scene action when active`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setSceneActionId(SceneType.MEDIA_PLAYING, GestureType.CW_FAST, "volume_up")
        config.setActionId(GestureType.CW_FAST, "brightness_up")

        assertEquals("volume_up", config.resolveActionId(GestureType.CW_FAST, null, SceneType.MEDIA_PLAYING))
    }

    @Test
    fun `per scene mode falls back to default when no scene active`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setActionId(GestureType.CW_FAST, "brightness_up")

        assertEquals("brightness_up", config.resolveActionId(GestureType.CW_FAST, null, null))
    }

    @Test
    fun `remove configured package cleans up keys`() {
        config.addConfiguredPackage("com.example")
        config.setAppActionId("com.example", GestureType.CW_FAST, "volume_up")
        config.removeConfiguredPackage("com.example")

        assertFalse(config.getConfiguredPackages().contains("com.example"))
        assertNull(config.getAppActionId("com.example", GestureType.CW_FAST))
    }
}

/**
 * In-memory PrefsStore for testing
 */
class FakePrefsStore : ConfigManager.TestablePrefsStore {
    private val data = mutableMapOf<String, Any?>()
    override fun getString(key: String, default: String?): String? = data[key] as? String ?: default
    override fun getInt(key: String, default: Int): Int = data[key] as? Int ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = data[key] as? Boolean ?: default
    override fun putString(key: String, value: String) { data[key] = value }
    override fun putInt(key: String, value: Int) { data[key] = value }
    override fun putBoolean(key: String, value: Boolean) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ConfigManagerTest"`
Expected: FAIL — `forTest`, `TestablePrefsStore`, `resolveActionId` 等方法不存在

- [ ] **Step 3: 实现 ConfigManager 扩展**

修改 `config/ConfigManager.kt`，在 `ConfigManager` class 中添加：

```kotlin
class ConfigManager private constructor(private val store: PrefsStore) {

    // --- 现有方法保持不变 ---

    // --- 新增: 模式 ---
    var dispatchMode: DispatchMode
        get() = DispatchMode.fromKey(store.getString("dispatch_mode", DispatchMode.GLOBAL.key) ?: DispatchMode.GLOBAL.key)
        set(value) = store.putString("dispatch_mode", value.key)

    // --- 新增: 相机覆盖 ---
    var overrideCamera: Boolean
        get() = store.getBoolean("override_camera", false)
        set(value) = store.putBoolean("override_camera", value)

    // --- 新增: 分应用 ---
    @Volatile
    private var cachedPackages: Set<String>? = null

    fun getConfiguredPackages(): Set<String> {
        cachedPackages?.let { return it }
        val raw = store.getString("per_app_configured_packages", null) ?: return emptySet()
        val result = raw.split(",").filter { it.isNotBlank() }.toSet()
        cachedPackages = result
        return result
    }

    fun addConfiguredPackage(packageName: String) {
        val current = getConfiguredPackages().toMutableSet()
        current.add(packageName)
        store.putString("per_app_configured_packages", current.joinToString(","))
        cachedPackages = current
    }

    fun removeConfiguredPackage(packageName: String) {
        val current = getConfiguredPackages().toMutableSet()
        current.remove(packageName)
        if (current.isEmpty()) {
            store.remove("per_app_configured_packages")
        } else {
            store.putString("per_app_configured_packages", current.joinToString(","))
        }
        cachedPackages = current
        GestureType.entries.forEach { gesture ->
            store.remove("app_${packageName}_gesture_${gesture.name}_action_id")
            store.remove("app_${packageName}_gesture_${gesture.name}_action_config")
        }
    }

    fun getAppActionId(packageName: String, gesture: GestureType): String? =
        store.getString("app_${packageName}_gesture_${gesture.name}_action_id", null)

    fun setAppActionId(packageName: String, gesture: GestureType, actionId: String?) {
        val key = "app_${packageName}_gesture_${gesture.name}_action_id"
        if (actionId != null) store.putString(key, actionId) else store.remove(key)
    }

    fun getAppActionConfig(packageName: String, gesture: GestureType): String? =
        store.getString("app_${packageName}_gesture_${gesture.name}_action_config", null)

    fun setAppActionConfig(packageName: String, gesture: GestureType, config: String?) {
        val key = "app_${packageName}_gesture_${gesture.name}_action_config"
        if (config != null) store.putString(key, config) else store.remove(key)
    }

    // --- 新增: 分场景 ---
    fun getSceneActionId(scene: SceneType, gesture: GestureType): String? =
        store.getString("scene_${scene.key}_gesture_${gesture.name}_action_id", null)

    fun setSceneActionId(scene: SceneType, gesture: GestureType, actionId: String?) {
        val key = "scene_${scene.key}_gesture_${gesture.name}_action_id"
        if (actionId != null) store.putString(key, actionId) else store.remove(key)
    }

    fun getSceneActionConfig(scene: SceneType, gesture: GestureType): String? =
        store.getString("scene_${scene.key}_gesture_${gesture.name}_action_config", null)

    fun setSceneActionConfig(scene: SceneType, gesture: GestureType, config: String?) {
        val key = "scene_${scene.key}_gesture_${gesture.name}_action_config"
        if (config != null) store.putString(key, config) else store.remove(key)
    }

    // --- 新增: 统一解析 ---
    fun resolveActionId(gesture: GestureType, foregroundPkg: String?, activeScene: SceneType?): String? =
        when (dispatchMode) {
            DispatchMode.GLOBAL -> getActionId(gesture)
            DispatchMode.PER_APP -> {
                if (foregroundPkg != null && foregroundPkg in getConfiguredPackages()) {
                    getAppActionId(foregroundPkg, gesture) ?: getActionId(gesture)
                } else {
                    getActionId(gesture)
                }
            }
            DispatchMode.PER_SCENE -> {
                if (activeScene != null) {
                    getSceneActionId(activeScene, gesture) ?: getActionId(gesture)
                } else {
                    getActionId(gesture)
                }
            }
        }

    fun resolveActionConfig(gesture: GestureType, foregroundPkg: String?, activeScene: SceneType?): String? =
        when (dispatchMode) {
            DispatchMode.GLOBAL -> getActionConfig(gesture)
            DispatchMode.PER_APP -> {
                if (foregroundPkg != null && foregroundPkg in getConfiguredPackages()) {
                    getAppActionConfig(foregroundPkg, gesture) ?: getActionConfig(gesture)
                } else {
                    getActionConfig(gesture)
                }
            }
            DispatchMode.PER_SCENE -> {
                if (activeScene != null) {
                    getSceneActionConfig(activeScene, gesture) ?: getActionConfig(gesture)
                } else {
                    getActionConfig(gesture)
                }
            }
        }

    companion object {
        const val PREFS_NAME = "hyperzoomring_config"

        fun fromContext(context: Context): ConfigManager { /* 不变 */ }
        fun fromYukiPrefs(prefs: YukiHookPrefsBridge): ConfigManager { /* 不变 */ }

        // 测试用
        fun forTest(store: TestablePrefsStore): ConfigManager = ConfigManager(store)
    }

    // 将 PrefsStore 接口公开为 TestablePrefsStore 供测试
    interface TestablePrefsStore : PrefsStore
}
```

注意：需要将 `PrefsStore` 接口从 `private` 改为 `internal`（或作为 `TestablePrefsStore` 的超类型暴露）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ConfigManagerTest"`
Expected: 6 tests PASS

- [ ] **Step 5: 提交**

```
jj commit -m "feat: extend ConfigManager with dispatch mode, per-app, per-scene, resolveActionId"
```

---

### Task 4: SceneDetector

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/SceneDetector.kt`

- [ ] **Step 1: 创建 SceneDetector**

```kotlin
package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import xyz.nextalone.hyperzoomring.config.SceneType

object SceneDetector {
    private const val TAG = "HyperZoomRing"

    @Volatile
    var torchState: Boolean = false
        private set

    @Volatile
    var fullscreenState: Boolean = false

    fun init(context: Context) {
        initTorchCallback(context)
    }

    /**
     * 检测当前激活的场景。优先级: 媒体播放 > 手电筒 > 全屏
     */
    fun detectActiveScene(context: Context): SceneType? {
        if (isMediaPlaying(context)) return SceneType.MEDIA_PLAYING
        if (torchState) return SceneType.FLASHLIGHT_ON
        if (fullscreenState) return SceneType.FULLSCREEN
        return null
    }

    private fun isMediaPlaying(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.isMusicActive
    }

    private fun initTorchCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchState = enabled
                }
            }, null)
            Log.i(TAG, "Torch callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register torch callback", e)
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```
jj commit -m "feat: add SceneDetector for media/flashlight/fullscreen detection"
```

---

### Task 5: Hook 层改造

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/InputInterceptorHook.kt`

- [ ] **Step 1: isCameraForeground → getForegroundPackage**

将现有 `isCameraForeground` 方法替换为：

```kotlin
private fun getForegroundPackage(context: Context?): String? {
    val ctx = context ?: return null
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val tasks = am.getRunningTasks(1)
    return tasks.firstOrNull()?.topActivity?.packageName
}

private fun isCameraPackage(packageName: String?): Boolean =
    packageName == "com.android.camera" || packageName == "com.xiaomi.camera"
```

- [ ] **Step 2: 初始化 SceneDetector**

在 `hook()` 方法中，sensor listener 启动后添加：

```kotlin
try {
    SceneDetector.init(ctx)
    Log.i(TAG, "SceneDetector initialized")
} catch (e: Exception) {
    Log.w(TAG, "SceneDetector init failed", e)
}
```

- [ ] **Step 3: 重写 handleZoomRingEvent 分发逻辑**

替换 `handleZoomRingEvent` 中从相机检查到动作执行的部分（约 L156-L207）：

```kotlin
private fun handleZoomRingEvent(event: InputEvent, context: Context?, canConsume: Boolean): Boolean {
    if (event !is MotionEvent) return false
    val device = event.device ?: return false
    if (!isZoomRingDevice(device)) return false

    val eventTime = event.eventTime
    if (eventTime == lastProcessedEventTime) return canConsume
    lastProcessedEventTime = eventTime
    lastZoomRingEventMs = System.currentTimeMillis()

    val foregroundPkg = getForegroundPackage(context)

    // 相机覆盖: overrideCamera=false 时相机永远透传
    if (isCameraPackage(foregroundPkg) && !config.overrideCamera) return false

    val direction = getDirection()
    val scrollValue = event.getAxisValue(MotionEvent.AXIS_SCROLL).toInt()
    val zoomEvent = ZoomRingEvent(
        timestampMs = eventTime,
        value = scrollValue,
        direction = direction,
    )

    val gesture = detector.onEvent(zoomEvent)
    val intensity = detector.currentIntensity

    // 诊断广播（不变）
    try {
        val ctx = context ?: return canConsume
        val intent = Intent(ACTION_ZOOM_RING_EVENT).apply {
            setPackage("xyz.nextalone.hyperzoomring")
            putExtra(EXTRA_TIMESTAMP, zoomEvent.timestampMs)
            putExtra(EXTRA_VALUE, scrollValue)
            putExtra(EXTRA_DIRECTION, direction)
            putExtra(EXTRA_GESTURE, gesture.name)
            putExtra(EXTRA_INTENSITY, intensity)
        }
        ctx.sendBroadcast(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to send diagnostic broadcast", e)
    }

    if (!config.isEnabled) return canConsume

    // 场景检测
    val activeScene = context?.let { SceneDetector.detectActiveScene(it) }

    // 统一解析
    val actionId = config.resolveActionId(gesture, foregroundPkg, activeScene) ?: return canConsume
    val action = ActionRegistry.get(actionId) ?: return canConsume

    if (action is LaunchAppAction) {
        LaunchAppAction.targetPackage = config.resolveActionConfig(gesture, foregroundPkg, activeScene)
    }

    val ctx = context ?: return canConsume
    try {
        action.execute(ctx, intensity)
    } catch (e: Exception) {
        Log.e(TAG, "Action execution failed: ${action.id}", e)
    }

    return canConsume
}
```

- [ ] **Step 4: 删除旧的 isCameraForeground 方法**

确认没有其他调用点后删除。去重检查中也用 `getForegroundPackage` 替代：

```kotlin
// 原: if (eventTime == lastProcessedEventTime) return canConsume && !isCameraForeground(context)
// 改为: if (eventTime == lastProcessedEventTime) return canConsume
```

去重时不需要判断相机——直接返回 canConsume 即可，因为相机判断已在后面处理。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```
jj commit -m "feat: hook layer multi-mode dispatch with SceneDetector and camera override"
```

---

### Task 6: 全屏检测 Hook（实验性）

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/InputInterceptorHook.kt`

- [ ] **Step 1: 在 hook() 中添加全屏检测 Hook**

在 `interceptKeyBeforeQueueing` hook 之后添加：

```kotlin
// Fullscreen detection (experimental)
try {
    "com.android.server.wm.DisplayPolicy".toClass().hook {
        injectMember {
            allMethods("beginPostLayoutPolicyLw")
            afterHook {
                // 检查顶部 window 的 systemUiVisibility 是否包含 immersive flags
            }
        }
    }.result {
        onHookingFailure {
            Log.w(TAG, "DisplayPolicy hook failed (fullscreen detection unavailable)", it)
        }
    }
    Log.i(TAG, "Fullscreen detection hook installed")
} catch (e: Exception) {
    Log.w(TAG, "DisplayPolicy not found, fullscreen detection disabled", e)
}
```

备选方案：如果 `DisplayPolicy` 不可用，尝试 `PhoneWindowManager`:

```kotlin
try {
    "com.android.server.policy.PhoneWindowManager".toClass().hook {
        injectMember {
            allMethods("applyPostLayoutPolicyLw")
            afterHook {
                try {
                    val topIsFullscreen = args.firstOrNull()?.let { win ->
                        val attrs = win.javaClass.getMethod("getAttrs").invoke(win)
                        val flags = attrs.javaClass.getField("flags").getInt(attrs)
                        (flags and 0x00000400) != 0 // FLAG_FULLSCREEN
                    } ?: false
                    SceneDetector.fullscreenState = topIsFullscreen
                } catch (_: Exception) {}
            }
        }
    }.result {
        onHookingFailure { Log.w(TAG, "PhoneWindowManager hook failed", it) }
    }
} catch (_: Exception) {}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```
jj commit -m "feat: experimental fullscreen detection via DisplayPolicy/PhoneWindowManager hook"
```

---

### Task 7: UI — Screen 导航模型 + MainActivity 重写

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/MainActivity.kt`

- [ ] **Step 1: 定义 Screen sealed class 和重写 MainContent**

```kotlin
package xyz.nextalone.hyperzoomring.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ui.screen.*

sealed class Screen {
    data object Home : Screen()
    data object Diagnostic : Screen()
    data object ModeConfig : Screen()
    data class GestureConfig(val gesture: GestureType, val scope: ConfigScope) : Screen()
    data object AppList : Screen()
    data class AppGestureOverview(val packageName: String) : Screen()
    data object SceneList : Screen()
    data class SceneGestureOverview(val scene: SceneType) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MiuixTheme { MainContent() } }
    }
}

@Composable
private fun MainContent() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var screenStack by remember { mutableStateOf<List<Screen>>(emptyList()) }

    val currentScreen = screenStack.lastOrNull()

    fun push(screen: Screen) { screenStack = screenStack + screen }
    fun pop() { screenStack = screenStack.dropLast(1) }

    if (currentScreen != null) {
        BackHandler { pop() }

        val title = when (currentScreen) {
            is Screen.ModeConfig -> "模式选择"
            is Screen.GestureConfig -> "${currentScreen.gesture.displayName} 配置"
            is Screen.AppList -> "应用配置"
            is Screen.AppGestureOverview -> "应用手势配置"
            is Screen.SceneList -> "场景配置"
            is Screen.SceneGestureOverview -> "场景手势配置"
            else -> ""
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = { pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                )
            },
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (currentScreen) {
                is Screen.ModeConfig -> ModeConfigScreen(modifier = mod)
                is Screen.GestureConfig -> GestureConfigScreen(
                    gestureType = currentScreen.gesture,
                    scope = currentScreen.scope,
                    modifier = mod,
                )
                is Screen.AppList -> AppListScreen(
                    onAppClick = { push(Screen.AppGestureOverview(it)) },
                    modifier = mod,
                )
                is Screen.AppGestureOverview -> AppGestureOverviewScreen(
                    packageName = currentScreen.packageName,
                    onGestureClick = { push(Screen.GestureConfig(it, ConfigScope.App(currentScreen.packageName))) },
                    modifier = mod,
                )
                is Screen.SceneList -> SceneListScreen(
                    onSceneClick = { push(Screen.SceneGestureOverview(it)) },
                    modifier = mod,
                )
                is Screen.SceneGestureOverview -> SceneGestureOverviewScreen(
                    scene = currentScreen.scene,
                    onGestureClick = { push(Screen.GestureConfig(it, ConfigScope.Scene(currentScreen.scene))) },
                    modifier = mod,
                )
                else -> {}
            }
        }
    } else {
        Scaffold(
            topBar = { TopAppBar(title = "HyperZoomRing") },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = "主页", icon = Icons.Default.Home)
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = "诊断", icon = Icons.Default.Info)
                }
            },
        ) { padding ->
            when (selectedTab) {
                0 -> HomeScreen(
                    modifier = Modifier.padding(padding),
                    onNavigate = { push(it) },
                )
                1 -> DiagnosticScreen(modifier = Modifier.padding(padding))
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证（会因缺少新 Screen 而报错，确认错误仅是缺少新 Composable）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — 引用了尚未创建的 ModeConfigScreen, AppListScreen 等

- [ ] **Step 3: 提交（暂存，后续 task 补全新 Screen 后整体编译）**

```
jj commit -m "feat: Screen sealed class navigation model in MainActivity"
```

---

### Task 8: UI — HomeScreen 改造

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/HomeScreen.kt`

- [ ] **Step 1: 重写 HomeScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.DispatchMode
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ui.Screen

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Screen) -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var isEnabled by remember { mutableStateOf(config.isEnabled) }
    var dispatchMode by remember { mutableStateOf(config.dispatchMode) }
    var overrideCamera by remember { mutableStateOf(config.overrideCamera) }
    var speedThreshold by remember { mutableFloatStateOf(config.speedThreshold.toFloat()) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle("模块状态")
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperSwitch(
                title = "启用 HyperZoomRing",
                summary = if (isEnabled) "变焦环手势已启用" else "变焦环手势已禁用",
                checked = isEnabled,
                onCheckedChange = { isEnabled = it; config.isEnabled = it },
            )
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("模式选择")
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperArrow(
                title = "分发模式",
                summary = dispatchMode.displayName,
                onClick = { onNavigate(Screen.ModeConfig) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("相机设置")
        Card(modifier = Modifier.fillMaxWidth()) {
            SuperSwitch(
                title = "覆盖相机应用",
                summary = if (overrideCamera) "变焦环在相机内执行自定义动作" else "相机内保持原始变焦功能",
                checked = overrideCamera,
                onCheckedChange = { overrideCamera = it; config.overrideCamera = it },
            )
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("手势配置")
        Card(modifier = Modifier.fillMaxWidth()) {
            // 默认/全局手势映射（所有模式都显示）
            if (dispatchMode != DispatchMode.GLOBAL) {
                Text(
                    text = "默认配置",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            GestureType.entries.forEach { gesture ->
                val actionId = config.getActionId(gesture)
                val actionName = actionId?.let { ActionRegistry.get(it)?.displayName } ?: "未设置"
                SuperArrow(
                    title = gesture.displayName,
                    summary = "动作: $actionName",
                    onClick = { onNavigate(Screen.GestureConfig(gesture, ConfigScope.Global)) },
                )
            }
        }

        // 模式特有入口
        if (dispatchMode == DispatchMode.PER_APP) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperArrow(
                    title = "管理应用配置",
                    summary = "${config.getConfiguredPackages().size} 个应用已配置",
                    onClick = { onNavigate(Screen.AppList) },
                )
            }
        }

        if (dispatchMode == DispatchMode.PER_SCENE) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperArrow(
                    title = "管理场景配置",
                    summary = "媒体播放 · 手电筒 · 全屏",
                    onClick = { onNavigate(Screen.SceneList) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("速度阈值")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "200ms 内事件数 > ${speedThreshold.toInt()} 判定为快转",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = (speedThreshold - 2f) / 13f,
                    onValueChange = { progress ->
                        val v = 2f + progress * 13f
                        speedThreshold = v
                        config.speedThreshold = v.toInt()
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```
jj commit -m "feat: HomeScreen with mode selector, camera override, mode-specific sections"
```

---

### Task 9: UI — GestureConfigScreen 改造

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/GestureConfigScreen.kt`

- [ ] **Step 1: 增加 ConfigScope 参数**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.extra.SuperRadioButton
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun GestureConfigScreen(
    gestureType: GestureType,
    scope: ConfigScope = ConfigScope.Global,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }

    var selectedActionId by remember {
        mutableStateOf(
            when (scope) {
                is ConfigScope.Global -> config.getActionId(gestureType)
                is ConfigScope.App -> config.getAppActionId(scope.packageName, gestureType)
                is ConfigScope.Scene -> config.getSceneActionId(scope.scene, gestureType)
            }
        )
    }

    val actions = remember { ActionRegistry.allForScope(scope) }

    fun saveAction(actionId: String?) {
        when (scope) {
            is ConfigScope.Global -> config.setActionId(gestureType, actionId)
            is ConfigScope.App -> config.setAppActionId(scope.packageName, gestureType, actionId)
            is ConfigScope.Scene -> config.setSceneActionId(scope.scene, gestureType, actionId)
        }
        selectedActionId = actionId
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle("${gestureType.displayName} — 选择动作")

        Card(modifier = Modifier.fillMaxWidth()) {
            SuperRadioButton(
                title = "无动作",
                selected = selectedActionId == null,
                onClick = { saveAction(null) },
            )

            actions.forEach { action ->
                SuperRadioButton(
                    title = action.displayName,
                    selected = selectedActionId == action.id,
                    onClick = { saveAction(action.id) },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```
jj commit -m "feat: GestureConfigScreen supports ConfigScope for multi-mode dispatch"
```

---

### Task 10: UI — ModeConfigScreen

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/ModeConfigScreen.kt`

- [ ] **Step 1: 创建 ModeConfigScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.extra.SuperRadioButton
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.DispatchMode

@Composable
fun ModeConfigScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var selected by remember { mutableStateOf(config.dispatchMode) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle("选择变焦环分发模式")

        Card(modifier = Modifier.fillMaxWidth()) {
            DispatchMode.entries.forEach { mode ->
                SuperRadioButton(
                    title = mode.displayName,
                    summary = when (mode) {
                        DispatchMode.GLOBAL -> "所有应用使用相同手势配置"
                        DispatchMode.PER_APP -> "不同应用使用不同手势配置"
                        DispatchMode.PER_SCENE -> "根据系统状态自动切换手势配置"
                    },
                    selected = selected == mode,
                    onClick = { selected = mode; config.dispatchMode = mode },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```
jj commit -m "feat: add ModeConfigScreen for dispatch mode selection"
```

---

### Task 11: UI — AppListScreen + AppPickerDialog

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/AppListScreen.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/AppPickerDialog.kt`

- [ ] **Step 1: 创建 AppListScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun AppListScreen(
    onAppClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val pm = context.packageManager
    var configuredPackages by remember { mutableStateOf(config.getConfiguredPackages()) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AppPickerDialog(
            excludePackages = configuredPackages,
            onSelect = { pkg ->
                config.addConfiguredPackage(pkg)
                configuredPackages = config.getConfiguredPackages()
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle("应用配置")

        Card(modifier = Modifier.fillMaxWidth()) {
            SuperArrow(
                title = "添加应用",
                summary = "从已安装应用中选择",
                onClick = { showPicker = true },
            )
        }

        if (configuredPackages.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "尚未配置任何应用，请点击上方添加",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            SmallTitle("已配置应用")

            Card(modifier = Modifier.fillMaxWidth()) {
                configuredPackages.forEach { pkg ->
                    val appLabel = try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) { pkg }

                    val configuredCount = GestureType.entries.count { gesture ->
                        config.getAppActionId(pkg, gesture) != null
                    }

                    SuperArrow(
                        title = appLabel,
                        summary = "$pkg · 已配置 $configuredCount 个手势",
                        onClick = { onAppClick(pkg) },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 创建 AppPickerDialog**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class AppInfo(val packageName: String, val label: String)

@Composable
fun AppPickerDialog(
    excludePackages: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val apps = remember {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg in excludePackages) return@mapNotNull null
                AppInfo(pkg, resolveInfo.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val filtered = remember(searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
        ) {
            Column(Modifier.padding(16.dp)) {
                SmallTitle("选择应用")
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索应用",
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app.packageName) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(app.label)
                            Text(
                                app.packageName,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: 提交**

```
jj commit -m "feat: add AppListScreen and AppPickerDialog for per-app configuration"
```

---

### Task 12: UI — AppGestureOverviewScreen

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/AppGestureOverviewScreen.kt`

- [ ] **Step 1: 创建 AppGestureOverviewScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun AppGestureOverviewScreen(
    packageName: String,
    onGestureClick: (GestureType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val pm = context.packageManager
    val appLabel = remember {
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { packageName }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle(appLabel)
        Text(
            packageName,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        Spacer(Modifier.height(12.dp))
        SmallTitle("手势配置")

        Card(modifier = Modifier.fillMaxWidth()) {
            GestureType.entries.forEach { gesture ->
                val actionId = config.getAppActionId(packageName, gesture)
                val actionName = actionId?.let { ActionRegistry.get(it)?.displayName } ?: "使用默认"
                SuperArrow(
                    title = gesture.displayName,
                    summary = "动作: $actionName",
                    onClick = { onGestureClick(gesture) },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```
jj commit -m "feat: add AppGestureOverviewScreen for per-app gesture mapping"
```

---

### Task 13: UI — SceneListScreen + SceneGestureOverviewScreen

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/SceneListScreen.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/SceneGestureOverviewScreen.kt`

- [ ] **Step 1: 创建 SceneListScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.extra.SuperArrow
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun SceneListScreen(
    onSceneClick: (SceneType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle("场景配置")

        Card(modifier = Modifier.fillMaxWidth()) {
            SceneType.entries.forEach { scene ->
                val configuredCount = GestureType.entries.count { gesture ->
                    config.getSceneActionId(scene, gesture) != null
                }
                SuperArrow(
                    title = scene.displayName,
                    summary = "已配置 $configuredCount 个手势",
                    onClick = { onSceneClick(scene) },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 创建 SceneGestureOverviewScreen**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun SceneGestureOverviewScreen(
    scene: SceneType,
    onGestureClick: (GestureType) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SmallTitle(scene.displayName)

        if (scene == SceneType.FULLSCREEN) {
            Text(
                "此场景为实验性功能，部分设备可能不支持",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("手势配置")

        Card(modifier = Modifier.fillMaxWidth()) {
            GestureType.entries.forEach { gesture ->
                val actionId = config.getSceneActionId(scene, gesture)
                val actionName = actionId?.let { ActionRegistry.get(it)?.displayName } ?: "使用默认"
                SuperArrow(
                    title = gesture.displayName,
                    summary = "动作: $actionName",
                    onClick = { onGestureClick(gesture) },
                )
            }
        }
    }
}
```

- [ ] **Step 3: 提交**

```
jj commit -m "feat: add SceneListScreen and SceneGestureOverviewScreen"
```

---

### Task 14: 编译验证 + 全量测试

- [ ] **Step 1: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: ALL TESTS PASS

- [ ] **Step 3: 修复编译/测试问题（如有）**

解决所有编译错误和测试失败。

- [ ] **Step 4: 提交**

```
jj commit -m "fix: resolve compilation and test issues for multi-mode dispatch"
```

---

## 验证清单

1. **编译**: `./gradlew :app:assembleDebug` 通过
2. **单元测试**: `./gradlew :app:testDebugUnitTest` 通过，特别是 `ConfigManagerTest`
3. **功能验证**（安装后）:
   - 全局模式行为不变（向后兼容）
   - 模式切换 UI 正常
   - 分应用：添加应用 → 配置手势 → 在该应用前台验证
   - 分场景：播放音乐 → 媒体场景手势生效
   - 分场景：开手电筒 → 手电筒亮度调节生效
   - 相机覆盖：关闭时相机透传，开启时执行自定义动作
   - 默认回退：未配置时使用默认映射
