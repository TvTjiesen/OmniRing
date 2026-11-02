package xyz.nextalone.hyperzoomring.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.config.ConfigScope

/**
 * 无方向动作不再出现在表冠的选择器里（业主 2026-11-02 拍板：没有方向的动作直接不支持）。
 *
 * 注意 `all()` 仍然返回全部 16 个 id —— 动作 id 是对外契约（老存档、心跳、老 App 都在用），
 * 只在"选择器"这一层过滤就够了。
 */
class ActionRegistryDirectionalTest {

    @Test
    fun `启动应用是无方向动作`() {
        assertFalse("launch_app 没有方向语义，不该能映射到表冠", LaunchAppAction.directional)
    }

    @Test
    fun `全局范围的选择器不含无方向动作`() {
        val ids = ActionRegistry.allForScope(ConfigScope.Global).map { it.id }
        assertFalse("launch_app 不该出现在表冠的可选动作里", ids.contains(LaunchAppAction.id))
        assertTrue(ids.contains("scroll"))
        assertTrue(ids.contains("volume_up"))
    }

    @Test
    fun `动作 id 全表不许缩水 —— 它是对外契约`() {
        assertEquals(16, ActionRegistry.all().size)
        assertTrue(
            "老存档和心跳里还写着 launch_app，注册表不能把它摘掉",
            ActionRegistry.all().any { it.id == LaunchAppAction.id }
        )
    }
}