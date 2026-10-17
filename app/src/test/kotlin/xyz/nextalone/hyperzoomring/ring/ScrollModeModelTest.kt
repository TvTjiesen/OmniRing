package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚动里程表的行为规约。
 *
 * 历史：#57 时这里是三个互相可区分的滚动语义（表冠跟随 / 向上定方向 / 向下定方向）。
 * 用户后来反馈"上下滚动和表冠滚动冲突、清单太冗杂，修不好就删掉吧"，定方向那两个已删除，
 * 只剩一条口径：**方向跟实际转向，距离跟累计转幅**。旧存档里的 scroll_up/scroll_down
 * 由 ConfigManager.migrateActionId 归一到 scroll，所以这里也要证明它们落到同一口径。
 */
class ScrollModeModelTest {

    private fun ScrollModeModel.turn(vararg deltas: Float) = deltas.forEach { onAccepted(it) }

    @Test
    fun `方向跟随实际转向`() {
        val m = ScrollModeModel()
        m.turn(100f)
        assertEquals(100f, m.targetPx("scroll", 1f), 0.001f)
        m.turn(-150f)
        assertEquals(-50f, m.targetPx("scroll", 1f), 0.001f)
    }

    @Test
    fun `来回转可以倒退回去`() {
        val m = ScrollModeModel()
        m.turn(100f, -160f)
        assertEquals(-60f, m.targetPx("scroll", 1f), 0.01f)
    }

    @Test
    fun `距离与累计转幅成正比`() {
        val m = ScrollModeModel()
        m.turn(50f, 50f)
        assertEquals(100f, m.targetPx("scroll", 1f), 0.001f)
    }

    @Test
    fun `增益按每单位像素换算`() {
        val m = ScrollModeModel()
        m.turn(100f)
        assertEquals(120f, m.targetPx("scroll", 1.2f), 0.01f)
        assertEquals(-120f, m.targetPx("scroll", -1.2f), 0.01f)
    }

    @Test
    fun `旧映射残留的定方向 id 与滚动同口径`() {
        val m = ScrollModeModel()
        m.turn(80f, -30f)
        val crown = m.targetPx(ScrollModeModel.SCROLL_CROWN, 1f)
        assertEquals(crown, m.targetPx("scroll_up", 1f), 0.001f)
        assertEquals(crown, m.targetPx("scroll_down", 1f), 0.001f)
    }
}
