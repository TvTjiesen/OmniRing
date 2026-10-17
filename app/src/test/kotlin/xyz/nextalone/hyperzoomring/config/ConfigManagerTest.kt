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
    fun `per app mode falls back when app action is null`() {
        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        config.setActionId(GestureType.CW_FAST, "volume_up")
        // Don't set app-specific action - should fall back

        assertEquals("volume_up", config.resolveActionId(GestureType.CW_FAST, "com.example", null))
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
    fun `per scene mode falls back when scene action is null`() {
        config.dispatchMode = DispatchMode.PER_SCENE
        config.setActionId(GestureType.CW_FAST, "brightness_up")
        // Scene active but no scene-specific action

        assertEquals("brightness_up", config.resolveActionId(GestureType.CW_FAST, null, SceneType.FLASHLIGHT_ON))
    }

    @Test
    fun `remove configured package cleans up keys`() {
        config.addConfiguredPackage("com.example")
        config.setAppActionId("com.example", GestureType.CW_FAST, "volume_up")
        config.setAppActionConfig("com.example", GestureType.CW_FAST, "some_config")
        config.removeConfiguredPackage("com.example")

        assertFalse(config.getConfiguredPackages().contains("com.example"))
        assertNull(config.getAppActionId("com.example", GestureType.CW_FAST))
        assertNull(config.getAppActionConfig("com.example", GestureType.CW_FAST))
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
     * 删掉的 scroll_up / scroll_down 在读配置时归一成 scroll，三种作用域都要管：
     * 不归一的话注册表查不到动作，SensorRingDriver 是 `?: return`，
     * 他和老用户升级完就会撞上"表冠没反应"，而且界面上那一格还显示"未设置"。
     */
    @Test
    fun `旧的定方向 id 读出来是滚动`() {
        config.setActionId(GestureType.CW_SLOW, "scroll_down")
        config.setAppActionId("com.example", GestureType.CW_SLOW, "scroll_up")
        config.setSceneActionId(SceneType.MEDIA_PLAYING, GestureType.CW_SLOW, "scroll_down")

        config.dispatchMode = DispatchMode.GLOBAL
        assertEquals("scroll", config.getActionId(GestureType.CW_SLOW))
        assertEquals("scroll", config.resolveActionId(GestureType.CW_SLOW, null, null))

        config.dispatchMode = DispatchMode.PER_APP
        config.addConfiguredPackage("com.example")
        assertEquals("scroll", config.resolveActionId(GestureType.CW_SLOW, "com.example", null))

        config.dispatchMode = DispatchMode.PER_SCENE
        assertEquals(
            "scroll",
            config.resolveActionId(GestureType.CW_SLOW, null, SceneType.MEDIA_PLAYING)
        )
    }

    @Test
    fun `归一只动那两个已删除的 id`() {
        assertEquals("volume_up", migrateActionId("volume_up"))
        assertEquals("scroll", migrateActionId("scroll"))
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
