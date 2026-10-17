package xyz.nextalone.hyperzoomring.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nextalone.hyperzoomring.ring.GestureType

/**
 * 空配置的保护：全新安装（或 App 数据被清）时，四个手势槽位都是空的，
 * 而钩子拿到空映射就是"转环什么都不做"。
 *
 * 今晚真机踩过：App 数据目录没了 → 映射全空 → 只要 App 一推送，
 * system_server 里原本还能用的快照就被换成空表 → 环当场失灵。
 * 所以"全空"必须种一份和文档一致的默认映射，而且只种一次。
 */
class DefaultMappingTest {

    @Test
    fun `四个槽位全空时才种默认`() {
        assertTrue(DefaultMapping.shouldSeed { null })
        // 只要有一个槽位是他自己配过的，就不许再替他决定
        assertFalse(DefaultMapping.shouldSeed { if (it == GestureType.CW_SLOW) "volume_up" else null })
    }

    @Test
    fun `默认映射四个槽位都给滚动`() {
        val written = mutableMapOf<GestureType, String>()
        DefaultMapping.seed { g, id -> written[g] = id }
        assertEquals(4, written.size)
        // 方向本来就跟实际转向走：顺时针往下、逆时针往上，不需要定方向动作
        GestureType.entries.forEach { g ->
            assertEquals("槽位 $g", "scroll", written[g])
        }
    }

    @Test
    fun `默认映射里的动作 id 都真实存在`() {
        // 种一个注册表里没有的 id，钩子那边只会静默 return，等于环没反应
        val written = mutableSetOf<String>()
        DefaultMapping.seed { _, id -> written += id }
        assertTrue("默认映射不能是空的", written.isNotEmpty())
        written.forEach { id ->
            assertTrue("动作 $id 不存在", xyz.nextalone.hyperzoomring.action.ActionRegistry.all().any { it.id == id })
        }
    }
}
