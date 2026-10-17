package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * "按住不放"进度拖拽的不变量。
 */
class ScrubHoldModelTest {

    private fun m() = ScrubHoldModel(pxPerUnit = 1.1f, maxOffsetPx = 900f, idleMs = 700L, minPressPx = 40f)

    @Test
    fun `攒不够落指量就不落指`() {
        val s = m()
        assertNull(s.onDelta(0L, 10f))     // 11px
        assertNull(s.onDelta(50L, 10f))    // 22px
        assertFalse(s.active)
        val off = s.onDelta(100L, 20f)     // 44px 才够
        assertTrue(s.active)
        assertTrue(s.needsPress)
        assertEquals(44f, off!!, 0.01f)
    }

    @Test
    fun `落指后只推进偏移 反向是往回走不是瞬移`() {
        val s = m()
        s.onDelta(0L, 100f)                // 110px，这一帧才要落指
        assertTrue(s.needsPress)
        var prev = s.offsetPx
        for (i in 1..10) {
            s.onDelta(i * 40L, -20f)
            assertFalse("第二帧还要求落指", s.needsPress)
            val now = s.offsetPx
            assertTrue("一步跳了 ${abs(now - prev)}", abs(now - prev) <= 22.1f)
            assertTrue("反向却往前了", now < prev)
            prev = now
        }
        assertEquals(110f - 10 * 22f, s.offsetPx, 0.01f)
    }

    @Test
    fun `偏移钳在上下限内`() {
        val s = m()
        s.onDelta(0L, 5000f)
        assertTrue("拖了 ${s.offsetPx}", abs(s.offsetPx) <= 900.001f)
        // 贴边后继续同向推：不会溢出，也不会反向
        val edge = s.offsetPx
        s.onDelta(100L, 500f)
        assertEquals(edge, s.offsetPx, 0.001f)
        // 反向要能立刻往回走
        s.onDelta(200L, -100f)
        assertTrue(s.offsetPx < edge)
    }

    @Test
    fun `会话时长对外可见 抬手后下次重新起会话`() {
        val s = m()
        s.onDelta(0L, 100f)
        assertEquals(700L, s.releaseAfterMs)   // 注入端按这个时长决定抬手
        s.release()
        assertFalse(s.active)
        assertEquals(0f, s.offsetPx, 0.001f)
        // 下一次转动是全新会话：从 0 重新攒，不会继承上次偏移
        val off = s.onDelta(3000L, 60f)
        assertTrue(s.needsPress)
        assertEquals(66f, off!!, 0.01f)
    }

    @Test
    fun `持续微转不会把会话清零`() {
        val s = m()
        s.onDelta(0L, 100f)
        var t = 500L
        repeat(6) {
            s.onDelta(t, 5f)
            // 一直在转 → 会话保持，且偏移单调累加（5*1.1=5.5px/次）
            assertTrue(s.active)
            assertTrue("偏移没累上去：${s.offsetPx}", s.offsetPx > 110f)
            t += 500L
        }
    }

    @Test
    fun `首次落指也要钳位 否则反向要先花光余量=单向失效`() {
        val s = m()
        s.onDelta(0L, 5000f)   // 猛甩一次：pending=5500px
        assertTrue("落指偏移没钳位：${s.offsetPx}", abs(s.offsetPx) <= 900.001f)
        // 反向必须立刻能动，而不是先抵消 5500 的假偏移
        val before = s.offsetPx
        s.onDelta(60L, -200f)
        assertTrue("反向没立刻推进：before=$before now=${s.offsetPx}", s.offsetPx < before - 200f)
    }

    @Test
    fun `来回小幅转动不会乱跳`() {
        val s = m()
        var t = 0L
        var max = 0f
        repeat(40) {
            val d = if (it % 2 == 0) 60f else -60f
            s.onDelta(t, d)
            t += 40L
            max = maxOf(max, abs(s.offsetPx))
        }
        // 锯齿输入下偏移必须仍然有界，且不会累积成飞天
        assertTrue("偏移跑到 $max", max <= 900.001f)
        assertTrue("最终没收敛到 0 附近：${s.offsetPx}", abs(s.offsetPx) < 60f)
    }
}
