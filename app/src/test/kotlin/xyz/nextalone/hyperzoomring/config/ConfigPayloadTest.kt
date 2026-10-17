package xyz.nextalone.hyperzoomring.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置推送通道的编码规约（#57 的真根因修法）。
 *
 * 实测结论：system_server 看不见 App 的私有 prefs 文件（exists=false / canRead=false），
 * 而 YukiHook 那份快照是冻结的（改文件、杀框架、整机重启都读不到新值），
 * XSharedPreferences 这个类在模块 classloader 里根本不存在。
 * 所以配置只能由 App 主动推过来 —— 广播 extras 装不下 Map，只能先编码成一个字符串。
 */
class ConfigPayloadTest {

    @Test
    fun `五种类型往返不变`() {
        val src = mapOf(
            "language" to "en",
            "gesture_CW_SLOW_action_id" to "scroll_down",
            "theme_mode" to 2,
            "installed_at" to 1_791_809_197_963L,
            "gain" to 1.25f,
            "module_enabled" to true,
            "override_camera" to false,
        )
        val back = ConfigPayload.decode(ConfigPayload.encode(src))
        assertEquals("en", back["language"])
        assertEquals("scroll_down", back["gesture_CW_SLOW_action_id"])
        assertEquals("2", back["theme_mode"])
        assertEquals("1791809197963", back["installed_at"])
        assertEquals("1.25", back["gain"])
        assertEquals("true", back["module_enabled"])
        assertEquals("false", back["override_camera"])
    }

    @Test
    fun `空配置编码成空串，解码回空表`() {
        assertEquals("", ConfigPayload.encode(emptyMap()))
        assertTrue(ConfigPayload.decode("").isEmpty())
    }

    @Test
    fun `值里的换行和制表被清掉，不会破坏行结构`() {
        // 一旦放行 \n，decode 就会把一条值拆成两行，整份配置错位
        val text = ConfigPayload.encode(mapOf("a" to "x\ny\tz"))
        assertEquals(1, text.lines().count { it.isNotBlank() })
        val v = ConfigPayload.decode(text)["a"] ?: ""
        assertTrue("换行必须被替换掉", !v.contains('\n') && !v.contains('\t'))
        assertEquals("x y z", v)
    }

    @Test
    fun `坏行跳过而不是抛异常`() {
        // 线格式是「类型 \t 键 \t 值」三段；少一段、类型不认识、键为空，都只能跳过这一行
        val broken = "s\tlanguage\ten\n只有两段\n\t\t\nz\tk\tv\ns\t\tv"
        val m = ConfigPayload.decode(broken)
        assertEquals("en", m["language"])
        assertEquals(1, m.size)
    }

    @Test
    fun `键本身不会被值里的分隔符混淆`() {
        val src = mapOf("app_com_x_gesture_CW_SLOW_action_id" to "scroll_up")
        assertEquals("scroll_up", ConfigPayload.decode(ConfigPayload.encode(src))["app_com_x_gesture_CW_SLOW_action_id"])
    }
}

/** 推送进来的配置在钩子侧怎么用：只读、类型转换、缺键语义。 */
class MemoryStoreTest {

    private fun store() = MemoryStore(
        mapOf(
            "gesture_CW_SLOW_action_id" to "scroll_down",
            "theme_mode" to "2",
            "module_enabled" to "true",
            "broken_int" to "abc",
        )
    )

    @Test
    fun `按类型读出推送来的配置`() {
        val s = store()
        assertEquals("scroll_down", s.getString("gesture_CW_SLOW_action_id", null))
        assertEquals(2, s.getInt("theme_mode", 0))
        assertEquals(true, s.getBoolean("module_enabled", false))
    }

    @Test
    fun `缺键用调用方默认值——取消映射也要立刻生效`() {
        val s = store()
        assertEquals(null, s.getString("gesture_CCW_FAST_action_id", null))
    }

    @Test
    fun `坏值回落到默认不抛异常`() {
        val s = store()
        assertEquals(7, s.getInt("broken_int", 7))
        assertEquals(false, s.getBoolean("broken_int", false))
    }

    @Test
    fun `钩子侧写入被丢弃`() {
        val s = store()
        s.putString("gesture_CW_SLOW_action_id", "volume_up")
        s.remove("theme_mode")
        assertEquals("scroll_down", s.getString("gesture_CW_SLOW_action_id", null))
        assertEquals(2, s.getInt("theme_mode", 0))
    }
}
