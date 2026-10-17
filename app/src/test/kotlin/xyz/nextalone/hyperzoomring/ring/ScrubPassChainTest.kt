package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「快进到一个地方就不动了」的回归（2026-10-14 真机日志：横屏连续转环，
 * 两次会话都停在 x=2204 = 起点 1304 + maxOffsetPx 900，之后怎么转都不走）。
 *
 * 一把拖到头不是终点：模型必须报 [ScrubHoldModel.passBoundary]，
 * 由动作层"抬手落定 + 从另一端重新起把"，这样连续转环可以无限快进下去，
 * 而每一步仍然是真实拖拽（不瞬移，那条老禁忌还在）。
 */
class ScrubPassChainTest {

    private fun m() = ScrubHoldModel(pxPerUnit = 1.1f, maxOffsetPx = 900f, idleMs = 700L, minPressPx = 40f)

    @Test
    fun `顶边还在同向推要报接力边界`() {
        val s = m()
        var t = 0L
        var boundarySeen = 0
        repeat(40) {
            s.onDelta(t, 120f)               // 每帧 132px，很快就顶到 900
            if (s.passBoundary != 0) boundarySeen++
            t += 20L
        }
        assertTrue("顶边 40 帧一次都没报边界 = 接力根本没机会触发", boundarySeen >= 30)
        assertEquals("往右推到头应当报 +1", 1, s.passBoundary)
    }

    @Test
    fun `反向推不算边界`() {
        val s = m()
        var t = 0L
        repeat(20) { s.onDelta(t, 120f); t += 20L }
        assertEquals(1, s.passBoundary)
        repeat(6) {
            s.onDelta(t, -120f)
            assertTrue("往回走不该报边界", s.passBoundary <= 0)
            t += 20L
        }
    }

    @Test
    fun `接力之后能一直快进下去`() {
        val s = m()
        var t = 0L
        var passes = 0
        var advanced = 0f
        var last = 0f
        repeat(600) {
            val off = s.onDelta(t, 100f)     // 每帧 110px，共 600 帧 = 66000px 物理行程
            if (off != null) {
                advanced += abs(off - last)
                last = off
                if (s.passBoundary != 0) {
                    passes++
                    s.beginPass()            // 动作层：抬手落定 + 从另一端重新落指
                    last = 0f                // 新的一把从它自己的锚点算起（锚点之间不是拖拽）
                }
            }
            t += 20L
        }
        assertTrue("只切了 $passes 把（每把约 10 帧，应当切 55 把以上）", passes >= 55)
        // 不接力的话这 600 帧只能走出 900px（他遇到的就是卡在那里）。接力之后每一把
        // 都是完整的一拖，累计应当达到物理行程的九成以上（贴边那一帧的余量走 carry）
        val physical = 600 * 110f
        assertTrue(
            "累计推进 ${"%.0f".format(advanced)}px / 物理行程 ${"%.0f".format(physical)}px = " +
                "${"%.0f".format(advanced / physical * 100)}%，接力不该把行程丢掉这么多",
            advanced >= physical * 0.9f,
        )
    }

    @Test
    fun `beginPass 不瞬移也不丢会话`() {
        val s = m()
        var t = 0L
        repeat(20) { s.onDelta(t, 120f); t += 20L }
        assertTrue(s.active)
        assertEquals(1, s.passBoundary)
        s.beginPass()
        assertEquals(0, s.passBoundary)
        // 新一把只从"顶边那帧没装下的余量"起步，最多一帧的位移，不是从 900 瞬移回 0
        assertTrue("新一把起点偏了 ${"%.0f".format(s.offsetPx)}px", abs(s.offsetPx) <= 132f)
        assertTrue("起新一把之后会话还在（否则下一帧要重新攒落指量，快进会一顿一顿）", s.active)
        val carry = s.offsetPx
        val next = s.onDelta(t, 100f) ?: 0f
        assertTrue("新一把第一步跳了 ${"%.0f".format(abs(next - carry))}px", abs(next - carry) <= 110.1f)
    }

    @Test
    fun `停手仍然照原样抬手`() {
        val s = m()
        var t = 0L
        repeat(10) { s.onDelta(t, 100f); t += 20L }
        assertTrue(s.active)
        s.onDelta(t + 5_000L, 0f)             // 停手 5 秒后才来一帧
        assertFalse(s.active)
    }

    @Test
    fun `贴边判定不会把正常推进误报成边界`() {
        val s = m()
        var t = 0L
        // 慢慢推，每次 50px，还没到 900 之前一帧都不该报
        var firstBoundaryAt = -1
        repeat(25) {
            s.onDelta(t, 45f)                  // 49.5px/帧
            if (s.passBoundary != 0 && firstBoundaryAt < 0) firstBoundaryAt = s.offsetPx.toInt()
            t += 20L
        }
        assertTrue("偏移才 ${s.offsetPx} 就报边界，太敏感", firstBoundaryAt == -1 || firstBoundaryAt >= 890)
    }
}
