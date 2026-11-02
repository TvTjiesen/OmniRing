package xyz.nextalone.hyperzoomring.config

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import xyz.nextalone.hyperzoomring.ring.CrownDir

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
        config.setActionId(CrownDir.PLUS, "volume_up")
        assertEquals("volume_up", config.resolveActionId(CrownDir.PLUS, "com.example", null))
    }

    @Test
    fun `per app mode returns app action when configured`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        config.setAppActionId("com.example", CrownDir.PLUS, "brightness_up")
        config.setActionId(CrownDir.PLUS, "volume_up")

        assertEquals("brightness_up", config.resolveActionId(CrownDir.PLUS, "com.example", null))
    }

    @Test
    fun `per app mode falls back to default for unconfigured app`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.setActionId(CrownDir.PLUS, "volume_up")

        assertEquals("volume_up", config.resolveActionId(CrownDir.PLUS, "com.other", null))
    }

    @Test
    fun `per app mode falls back when app action is null`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        config.setActionId(CrownDir.PLUS, "volume_up")
        // Don't set app-specific action - should fall back

        assertEquals("volume_up", config.resolveActionId(CrownDir.PLUS, "com.example", null))
    }

    @Test
    fun `per scene mode returns scene action when active`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setSceneActionId(SceneType.MEDIA_PLAYING, CrownDir.PLUS, "volume_up")
        config.setActionId(CrownDir.PLUS, "brightness_up")

        assertEquals("volume_up", config.resolveActionId(CrownDir.PLUS, null, SceneType.MEDIA_PLAYING))
    }

    @Test
    fun `per scene mode falls back to default when no scene active`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setActionId(CrownDir.PLUS, "brightness_up")

        assertEquals("brightness_up", config.resolveActionId(CrownDir.PLUS, null, null))
    }

    @Test
    fun `per scene mode falls back when scene action is null`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setActionId(CrownDir.PLUS, "brightness_up")
        // Scene active but no scene-specific action

        assertEquals("brightness_up", config.resolveActionId(CrownDir.PLUS, null, SceneType.FLASHLIGHT_ON))
    }

    @Test
    fun `remove configured package cleans up keys`() {
        config.addConfiguredPackage("com.example")
        config.setAppActionId("com.example", CrownDir.PLUS, "volume_up")
        config.setAppActionConfig("com.example", CrownDir.PLUS, "some_config")
        // 老存档的旧槽位键也要跟着清：包既然从"分应用"里移除了，回落链就不该还能翻出它的旧映射
        store.putString("app_com.example_gesture_CW_SLOW_action_id", "volume_down")
        store.putString("app_com.example_gesture_CCW_FAST_action_config", "legacy_cfg")
        config.removeConfiguredPackage("com.example")

        assertFalse(config.getConfiguredPackages().contains("com.example"))
        assertNull(config.getAppActionId("com.example", CrownDir.PLUS))
        assertNull(config.getAppActionConfig("com.example", CrownDir.PLUS))
        assertNull(config.getAppActionId("com.example", CrownDir.MINUS))
        assertNull(store.getString("app_com.example_gesture_CW_SLOW_action_id", null))
        assertNull(store.getString("app_com.example_gesture_CCW_FAST_action_config", null))
    }

    @Test
    fun `dispatch mode defaults to GLOBAL`() {
        assertEquals(DispatchMode.GLOBAL, config.dispatchMode)
    }

    @Test
    fun `override camera defaults to false`() {
        assertFalse(config.overrideCamera)
    }

    /**
     * 四槽位 → 两槽位的读时回落链（业主 2026-11-02 批准：旧的"快转/慢转分开配"取**慢转**那条）。
     *
     * 写只写新键、旧键只读不删：老钩子/老 App 还在的机器上，把旧键擦掉会让它们读了空。
     * 落回顺序 PLUS = `CW_SLOW` → `CW_FAST`，MINUS = `CCW_SLOW` → `CCW_FAST`。
     */
    @Test
    fun `旧 CW_SLOW 键在新键缺失时被读到`() {
        store.putString("gesture_CW_SLOW_action_id", "volume_up")
        assertEquals("volume_up", config.getActionId(CrownDir.PLUS))
    }

    @Test
    fun `只剩旧 CW_FAST 键也要读到`() {
        store.putString("gesture_CW_FAST_action_id", "brightness_up")
        assertEquals("brightness_up", config.getActionId(CrownDir.PLUS))
    }

    @Test
    fun `两个旧键都在时先取慢转`() {
        store.putString("gesture_CW_FAST_action_id", "brightness_up")
        store.putString("gesture_CW_SLOW_action_id", "volume_up")
        assertEquals("volume_up", config.getActionId(CrownDir.PLUS))
    }

    @Test
    fun `逆时针两条旧键的回落顺序`() {
        store.putString("gesture_CCW_FAST_action_id", "media_next")
        assertEquals("media_next", config.getActionId(CrownDir.MINUS))
        store.putString("gesture_CCW_SLOW_action_id", "media_previous")
        assertEquals("media_previous", config.getActionId(CrownDir.MINUS))
    }

    @Test
    fun `新键压过旧键，且旧键不被删`() {
        store.putString("gesture_CW_SLOW_action_id", "volume_up")
        config.setActionId(CrownDir.PLUS, "brightness_up")

        assertEquals("brightness_up", config.getActionId(CrownDir.PLUS))
        assertEquals("volume_up", store.getString("gesture_CW_SLOW_action_id", null))
    }

    @Test
    fun `action_config 走同一条回落链`() {
        store.putString("gesture_CW_SLOW_action_config", "com.example/.Main")
        assertEquals("com.example/.Main", config.getActionConfig(CrownDir.PLUS))

        config.setActionConfig(CrownDir.PLUS, "com.other/.Main")
        assertEquals("com.other/.Main", config.getActionConfig(CrownDir.PLUS))
    }

    @Test
    fun `分应用与分场景的旧键也走回落链`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        store.putString("app_com.example_gesture_CW_SLOW_action_id", "volume_down")
        assertEquals("volume_down", config.resolveActionId(CrownDir.PLUS, "com.example", null))

        config.dispatchMode = DispatchMode.PER_SCENE
        val scene = SceneType.MEDIA_PLAYING
        store.putString("scene_${scene.key}_gesture_CCW_SLOW_action_id", "media_previous")
        assertEquals("media_previous", config.resolveActionId(CrownDir.MINUS, null, scene))
    }

    /**
     * 老存档探测（P1 用它决定要不要弹一次性的"快转/慢转已合并"提示）。
     * 只读，不写回任何映射。
     */
    @Test
    fun `老存档探测：旧键在就为真，只有新键为假`() {
        assertFalse(config.hasLegacySlots())

        config.setActionId(CrownDir.PLUS, "scroll")
        config.legacyNoticeShown = true
        assertFalse("新键不算老存档", config.hasLegacySlots())
        assertTrue(config.legacyNoticeShown)

        store.putString("gesture_CW_FAST_action_id", "scroll")
        assertTrue(config.hasLegacySlots())

        // 分应用里的旧键同样算
        store.remove("gesture_CW_FAST_action_id")
        config.addConfiguredPackage("com.example")
        store.putString("app_com.example_gesture_CCW_SLOW_action_config", "x")
        assertTrue(config.hasLegacySlots())
    }

    /**
     * 旧四键全在、新键全空的存档**不能**被重新种上默认映射：否则老用户自己配的
     * 音量/亮度会被默认的"滚动"冲掉（`shouldSeed` 必须透过回落链看）。
     */
    @Test
    fun `旧四键还在时不该重新种默认映射`() {
        store.putString("gesture_CW_SLOW_action_id", "volume_up")
        store.putString("gesture_CW_FAST_action_id", "brightness_up")
        store.putString("gesture_CCW_SLOW_action_id", "media_previous")
        store.putString("gesture_CCW_FAST_action_id", "media_next")

        assertFalse(DefaultMapping.shouldSeed { config.getActionId(it) })
    }

    /**
     * 删掉的 scroll_up / scroll_down 在读配置时归一成 scroll，三种作用域都要管：
     * 不归一的话注册表查不到动作，SensorRingDriver 是 `?: return`，
     * 他和老用户升级完就会撞上"表冠没反应"，而且界面上那一格还显示"未设置"。
     * `launch_app` 自 2026-11-02 起同样归一 —— 启动应用是无方向动作，不再支持映射到表冠。
     */
    @Test
    fun `旧的定方向 id 读出来是滚动`() {
        config.setActionId(CrownDir.PLUS, "scroll_down")
        config.setAppActionId("com.example", CrownDir.PLUS, "scroll_up")
        config.setSceneActionId(SceneType.MEDIA_PLAYING, CrownDir.PLUS, "scroll_down")

        config.dispatchMode = DispatchMode.GLOBAL
        assertEquals("scroll", config.getActionId(CrownDir.PLUS))
        assertEquals("scroll", config.resolveActionId(CrownDir.PLUS, null, null))

        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        assertEquals("scroll", config.resolveActionId(CrownDir.PLUS, "com.example", null))

        config.dispatchMode = DispatchMode.PER_SCENE
        assertEquals(
            "scroll",
            config.resolveActionId(CrownDir.PLUS, null, SceneType.MEDIA_PLAYING)
        )
    }

    @Test
    fun `launch_app 在读配置时归一成滚动`() {
        store.putString("gesture_CW_SLOW_action_id", "launch_app")
        assertEquals("scroll", config.getActionId(CrownDir.PLUS))
    }

    @Test
    fun `归一只动那三个已删除的 id`() {
        assertEquals("volume_up", migrateActionId("volume_up"))
        assertEquals("scroll", migrateActionId("scroll"))
        assertEquals("scroll", migrateActionId("scroll_up"))
        assertEquals("scroll", migrateActionId("scroll_down"))
        assertEquals("scroll", migrateActionId("launch_app"))
        assertNull(migrateActionId(null))
    }
}

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