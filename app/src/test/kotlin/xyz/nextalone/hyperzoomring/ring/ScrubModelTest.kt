package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度环不变量：目标只由"转了多少"决定，和播放器自己走到哪无关。
 * 这组用例对应他反馈的"快进和后退会突然回到原点、抽搐、不知道跳到哪"。
 */
class ScrubModelTest {

    private fun model() = ScrubModel(msPerUnit = 12f, minStepMs = 200, throttleMs = 110, idleMs = 450)

    @Test
    fun `目标是绝对位置 不随播放进度漂移`() {
        val m = model()
        // 会话开始拍基准 10 秒
        var t = 0L
        val first = m.onDelta(t, 30f, 10_000L, 60_000L)
        assertEquals(10_360L, first)   // 10000 + 30*12
        // 播放器自己在往前走：第二次调用时当前位置已经变成 12 秒，目标仍应基于 basePos
        t += 120
        val second = m.onDelta(t, 30f, 12_000L, 60_000L)
        assertEquals(10_720L, second)   // 10000 + 60*12，跟 12000 无关
    }

    @Test
    fun `来回转动回到原点就该真的回到原点`() {
        val m = model()
        var t = 0L
        repeat(5) { t += 120; m.onDelta(t, 25f, 30_000L, 60_000L) }
        val far = m.targetPosMs()
        assertTrue(far > 30_000L)
        repeat(5) { t += 120; m.onDelta(t, -25f, 30_000L, 60_000L) }
        // 来回同量：偏移必须回到 0，不能残留
        assertEquals(0f, m.offsetMs(), 0.001f)
        assertEquals(30_000L, m.targetPosMs())
    }

    @Test
    fun `静置之后重新起转会重拍基准`() {
        val m = model()
        m.onDelta(0L, 100f, 5_000L, 60_000L)     // 基准 5 秒，目标 +1.2 秒
        assertEquals(6_200L, m.targetPosMs())
        // 隔了很久才再转：必须把新位置当基准，否则会把上次的余量叠上去
        val cmd = m.onDelta(5_000L, 20f, 40_000L, 60_000L)
        assertEquals(40_240L, cmd)
        assertEquals(240f, m.offsetMs(), 0.001f)
    }

    @Test
    fun `不会越过片头片尾`() {
        val m = model()
        var t = 0L
        var lastCmd = -1L
        repeat(20) {
            t += 120
            m.onDelta(t, -40f, 1_000L, 60_000L)?.let { lastCmd = it }
        }
        assertTrue("发出了负位置：$lastCmd", lastCmd >= 0)
        assertEquals(0L, m.targetPosMs())
        val m2 = model()
        t = 0L
        repeat(60) { t += 120; m2.onDelta(t, 40f, 55_000L, 60_000L) }
        assertEquals(60_000L, m2.targetPosMs())
    }

    @Test
    fun `发 seek 有节流 但目标不会丢`() {
        val m = model()
        var emits = 0
        // 每 20ms 一个小增量，模拟真实采样节奏
        for (i in 1..50) {
            if (m.onDelta(i * 20L, 5f, 20_000L, 100_000L) != null) emits++
        }
        // 1 秒内不该发超过 ~10 次
        assertTrue("发了 $emits 次，太密", emits <= 11)
        // 但累计偏移必须完整生效
        assertEquals(50 * 5 * 12f, m.offsetMs(), 0.001f)
    }

    @Test
    fun `拿不到播放器就直接失败让上层回落到滚动`() {
        val m = model()
        assertNull(m.onDelta(0L, 50f, null, 0L))
        assertFalse(m.active)
    }

    @Test
    fun `同向转动时发出的位置单调`() {
        val m = model()
        var prev = Long.MIN_VALUE
        for (i in 1..40) {
            m.onDelta(i * 130L, 20f, 10_000L, 100_000L)?.let {
                assertTrue("发出的目标位置倒退了：$prev -> $it", it >= prev)
                prev = it
            }
        }
    }
}
