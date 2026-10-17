package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表冠模型的离线回放测试。这些用例取代"改一次参数就重启一次手机"的做法：
 * 真机样本可以直接喂进来验证，只有跑绿的版本才上机。
 */
class CrownModelTest {

    private fun feed(m: CrownModel, t0: Long, deltas: List<Float>, stepMs: Long = 60L): Float {
        var pos = 0f
        var t = t0
        for (d in deltas) {
            pos += d
            m.sample(t, pos)
            t += stepMs
        }
        return pos
    }

    @Test
    fun `monotonic forward turn never moves target backwards`() {
        val m = CrownModel()
        var pos = 0f
        var t = 1000L
        var prevTarget = 0f
        repeat(40) {
            pos += 55f
            m.sample(t, pos)
            t += 60
            assertTrue(
                "目标不应倒退: ${m.targetUnits} < $prevTarget",
                m.targetUnits >= prevTarget - 0.001f
            )
            prevTarget = m.targetUnits
        }
        assertTrue("正向转动应累积出目标", m.targetUnits > 1000f)
    }

    @Test
    fun `sensor re-zero jump is discarded instead of flying the target`() {
        val m = CrownModel()
        // 先正常正向转
        var pos = 0f
        var t = 1000L
        repeat(10) { pos += 50f; m.sample(t, pos); t += 60 }
        val before = m.targetUnits
        // 传感器回零：位置从 -500 附近一下弹回 0（单样本巨大跳变）
        m.sample(t, 0f)
        assertEquals("回零跳变必须被丢弃，目标不能动", before, m.targetUnits, 0.001f)
        assertFalse("该样本应标记为未采纳", m.lastAccepted)
        assertTrue("应记录被丢弃的跳变幅度", kotlin.math.abs(m.lastDiscardedJump) > 300f)
    }

    @Test
    fun `small backlash wobble is swallowed`() {
        val m = CrownModel(backlash = 40f, backlashSpeed = 0.03f, backlashCap = 110f)
        // 正向转到有一定峰值速度
        var pos = 0f
        var t = 1000L
        repeat(15) { pos += 60f; m.sample(t, pos); t += 60 }
        val peak = m.targetUnits
        // 回摆：总共只倒退 25 单位（小于阈值）
        repeat(3) { pos -= 8f; m.sample(t, pos); t += 60 }
        assertEquals("轻微回摆不应推动目标", peak, m.targetUnits, 0.001f)
    }

    @Test
    fun `genuine reversal is accepted after threshold`() {
        val m = CrownModel(backlash = 40f, backlashSpeed = 0.03f, backlashCap = 110f)
        var pos = 0f
        var t = 1000L
        repeat(10) { pos += 60f; m.sample(t, pos); t += 60 }
        val peak = m.targetUnits
        // 真反向：持续倒退，累计必然越过阈值
        repeat(10) { pos -= 30f; m.sample(t, pos); t += 60 }
        assertTrue("持续反向必须被承认，否则就是方向锁死 bug", m.targetUnits < peak)
    }

    @Test
    fun `threshold cap keeps reversal possible after a violent flick`() {
        // 模拟猛甩：峰值速度极高，若没有 cap，阈值会被撑到天量，之后永远无法换向
        val m = CrownModel(backlash = 40f, backlashSpeed = 0.03f, backlashCap = 110f)
        var pos = 0f
        var t = 1000L
        repeat(8) { pos += 350f; m.sample(t, pos); t += 60 }   // 极快正向
        assertTrue("峰值速度应被拉高", m.peakVel > 3000f)
        val peak = m.targetUnits
        repeat(8) { pos -= 60f; m.sample(t, pos); t += 60 }    // 明显反向
        assertTrue("猛甩之后仍要能换向（cap 生效）: ${m.targetUnits} vs $peak", m.targetUnits < peak)
    }

    @Test
    fun `peak velocity decays with elapsed time not sample count`() {
        val m = CrownModel()
        var pos = 0f
        var t = 1000L
        repeat(8) { pos += 300f; m.sample(t, pos); t += 60 }
        val high = m.peakVel
        // 停手 2 秒没有新样本，然后只有一个极小样本到来：
        // 此时 idleReset 已清过，峰值必须很低，否则说明衰减依赖样本数
        t += 2000
        pos += 50f
        m.sample(t, pos)
        assertTrue("停手后峰值应回落（当前 ${m.peakVel} vs 峰值 $high）", m.peakVel < high)
    }

    @Test
    fun `replayed real-world style session keeps sign sane`() {
        // 复现一次典型使用：正向 12 格、停 0.6s、反向 6 格、停 0.6s、再正向
        val m = CrownModel()
        var pos = 0f
        var t = 0L
        fun turn(n: Int, per: Float, gap: Long) {
            repeat(n) { pos += per; m.sample(t, pos); t += gap }
            t += 600
        }
        turn(12, 55f, 70)
        val afterFwd = m.targetUnits
        turn(6, -45f, 70)
        val afterBack = m.targetUnits
        turn(10, 50f, 70)
        assertTrue("正向应累积", afterFwd > 500f)
        assertTrue("反向应减少目标", afterBack < afterFwd)
        assertTrue("再次正向应回升", m.targetUnits > afterBack)
    }
}
