package xyz.nextalone.hyperzoomring.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内表冠缓动的纯数学验证（他的要求：算法抽纯、在 PC 上回放，不许拿真机试错）。
 *
 * 真机样本口径：模块侧环事件 200ms 一拍，一拍约 20~40 个传感器单位，
 * 按默认 1.2 px/单位 ≈ 24~48px。下面统一按 60Hz 帧间隔回放。
 */
class RingEaseTest {

    private val frame = 1000f / 60f

    /** 30px 的一拍，每 12 帧（=200ms）来一次，共 5 拍 */
    private fun burstEvents(burstPx: Float = 30f, every: Int = 12, times: Int = 5) =
        List(every * times) { if (it % every == 0) burstPx else 0f }

    @Test
    fun `一拍被摊到多帧而不是一个台阶`() {
        val moves = RingEase.replay(burstEvents(), frame, tauMs = 45f)
        val total = moves.sum()
        // 死区会把每拍最后不到 0.6px 让掉，所以守恒要留这点余量，但不许凭空多出位移
        assertTrue("总位移 $total 不守恒", total in 145f..150.01f)
        assertTrue("缓动不许反向：$moves", moves.all { it >= 0f })
        assertTrue("单帧最大位移 ${moves.max()}，30px 的一拍必须摊开", moves.max() < 12f)
        val busy = moves.count { it >= 1f }
        assertTrue("只有 $busy 帧真正在动，说明还是台阶", busy >= 30)
    }

    @Test
    fun `tau 为 0 时就是旧的直驱行为`() {
        // 反证：同一份样本走旧参数就是一个台阶 —— 证明上面那条真的走到了缓动分支
        val moves = RingEase.replay(burstEvents(), frame, tauMs = 0f)
        assertEquals(30f, moves.max(), 0.001f)
        assertEquals(5, moves.count { it > 1f })
    }

    @Test
    fun `反向转动时位移跟着变负`() {
        var target = 100f
        var applied = 0f
        repeat(3) {
            val (m, a) = RingEase.step(target, applied, frame, 45f)
            applied = a
        }
        target = applied - 40f
        val (m, _) = RingEase.step(target, applied, frame, 45f)
        assertTrue("往回转时位移必须是负的，实际 $m", m < 0f)
    }

    @Test
    fun `卡帧不许一次跳到目标`() {
        val (move, _) = RingEase.step(target = 200f, applied = 0f, dtMs = 5000f, tauMs = 45f)
        assertTrue("卡帧后仍要按缓动推进，实际一次吃了 $move", move < 200f * 0.9f)
    }

    @Test
    fun `死区内对齐目标不再挤半像素`() {
        val (move, applied) = RingEase.step(target = 100.4f, applied = 100f, dtMs = frame, tauMs = 45f)
        assertEquals(0f, move)
        assertEquals(100.4f, applied, 0.0001f)
        assertTrue(RingEase.converged(100.4f, applied))
    }

    @Test
    fun `零长帧不推进`() {
        val (move, applied) = RingEase.step(target = 50f, applied = 0f, dtMs = 0f, tauMs = 45f)
        assertEquals(0f, move)
        assertEquals(0f, applied)
    }

    @Test
    fun `停手后收敛并且不再产生位移`() {
        var applied = 0f
        val target = 40f
        repeat(60) {
            val (_, a) = RingEase.step(target, applied, frame, 45f)
            applied = a
        }
        assertTrue(RingEase.converged(target, applied))
        assertEquals(40f, applied, 0.7f)
        val (tail, _) = RingEase.step(target, applied, frame, 45f)
        assertEquals(0f, tail)
    }
}
