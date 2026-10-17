package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 换向"力度不统一"的回归。
 *
 * 上一版给换向加了"至少两个反向样本"的门（CrownReversalSpikeTest），
 * 反方向一蹿确实没了，但他马上反馈：原本该往回走的那一下，有时原地不动、有时幅度更小。
 * 根因是回差的记账方式：破阈之前那些反向样本只是 return false，位移**整条丢掉**，
 * 翻方向那一刻又只记当前这一帧 —— 于是他物理转了多少，画面只走了其中一截，
 * 而且走多少取决于样本恰好怎么切，观感就是"有时不动、有时轻"。
 *
 * 正确语义：回差只用于**判定**方向（吃掉传感器抖动），位移本身不许丢，
 * 破阈那一刻把攒着的反向位移一次性记上（机械回差是"空程"，不是"消失"）。
 */
class CrownReverseCreditTest {

    @Test
    fun `持续换向必须把转过的量全部记上`() {
        val m = CrownModel()
        var pos = 0f
        // 先稳定前进 20 帧，把方向锁和峰值速度建立起来
        repeat(20) {
            pos += 80f
            m.sample(1_000L + 20L * it, pos)
        }
        assertTrue(m.dirSign == 1)

        // 往回转：每帧 70 单位，共 10 帧 = 700 单位
        var credited = 0f
        repeat(10) {
            pos -= 70f
            if (m.sample(1_400L + 20L * it, pos)) credited += m.lastDeltaUnits
        }
        // 允许吃掉一个回差样本（判定门），但绝不允许把整段反向行程吞掉
        val physical = 700f
        assertTrue(
            "反向只记了 ${abs(credited)} 单位，物理转了 $physical 单位 —— 力度就是这样不统一的",
            abs(credited) >= physical * 0.8f,
        )
        assertTrue("方向必须翻过来", m.dirSign == -1)
    }

    @Test
    fun `换向记账与旧判据在同量级`() {
        // 单样本门（旧）与多样本门（新）在"持续反向"这一段上记的总量必须接近，
        // 否则加了门就等于偷偷改了增益
        fun run(minSamples: Int, credit: Boolean): Float {
            val m = CrownModel()
            m.reverseMinSamples = minSamples
            m.reverseCredit = credit
            var pos = 0f
            repeat(20) { pos += 80f; m.sample(1_000L + 20L * it, pos) }
            var credited = 0f
            repeat(10) {
                pos -= 70f
                if (m.sample(1_400L + 20L * it, pos)) credited += m.lastDeltaUnits
            }
            return abs(credited)
        }
        val old = run(1, false)     // 旧：单样本就能翻 + 不补记
        val new = run(2, true)      // 现：两样本才翻 + 破阈时补记
        assertTrue("旧判据反向记了 $old，新判据只记了 $new —— 差距过大", new >= old * 0.85f)
    }

    @Test
    fun `孤立尖峰仍然不许被补记`() {
        // 补记不能把上一版修掉的尖峰又请回来
        val m = CrownModel()
        var pos = 0f
        repeat(20) { pos += 80f; m.sample(1_000L + 20L * it, pos) }
        val before = m.targetUnits
        m.sample(1_400L, pos - 380f)          // 孤立尖峰（下一帧就回到原方向）
        m.sample(1_420L, pos - 300f)          // 前进：+80
        m.sample(1_440L, pos - 220f)
        m.sample(1_460L, pos - 140f)
        assertTrue("尖峰被记进来了：Δ=${m.targetUnits - before}", m.targetUnits >= before)
        assertTrue("方向锁不能被孤立尖峰翻掉", m.dirSign == 1)
    }

    /**
     * 逐段"单向行程"看跟手：这一段里传感器实际走了多少、里程表记了多少。
     * 只统计**段内发生过换向**的那些段 —— 他抱怨的是"返回那一下"，
     * 全部段落的中位数会被单纯前进段淹成 1.0，等于没测。
     */
    private fun medianFollowRatio(samples: List<Pair<Long, Float>>, minSamples: Int, credit: Boolean): Float {
        val m = CrownModel()
        m.reverseMinSamples = minSamples
        m.reverseCredit = credit
        val ratios = ArrayList<Float>()
        var runSigned = 0f
        var runCredited = 0f
        var runLen = 0
        var runFlips = 0
        var prevSign = 0
        fun closeRun() {
            if (runLen >= 4 && runFlips >= 1 && abs(runSigned) > 60f) {
                ratios += (abs(runCredited) / abs(runSigned)).coerceAtMost(2f)
            }
            runSigned = 0f; runCredited = 0f; runLen = 0; runFlips = 0
        }
        for (i in 1 until samples.size) {
            val dt = samples[i].first - samples[i - 1].first
            val d = samples[i].second - samples[i - 1].second
            val sg = if (d > 0f) 1 else if (d < 0f) -1 else 0
            if (dt > 350 || (sg != 0 && prevSign != 0 && sg != prevSign)) closeRun()
            if (sg != 0) prevSign = sg
            runSigned += d
            runLen++
            val dirBefore = m.dirSign
            if (m.sample(samples[i].first, samples[i].second)) {
                runCredited += m.lastDeltaUnits
                if (dirBefore != 0 && m.dirSign != 0 && dirBefore != m.dirSign) runFlips++
            }
        }
        closeRun()
        assertTrue("样本里没有可用的换向段（段数=${ratios.size}）", ratios.size >= 5)
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    @Test
    fun `真机样本上每一段单向行程都得跟手`() {
        val f = File("src/test/fixtures/smp_wiggle.log")
        assertTrue("缺少真机样本", f.exists() && f.length() > 1000)
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+)""")
        var t0 = -1.0
        val s = ArrayList<Pair<Long, Float>>()
        for (line in f.readLines()) {
            val mm = re.find(line) ?: continue
            val sec = mm.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            s += ((sec - t0) * 1000.0).toLong() to mm.groupValues[2].toFloat()
        }
        val withCredit = medianFollowRatio(s, 2, true)
        val without = medianFollowRatio(s, 2, false)
        assertTrue(
            "按段跟手比例（中位）：补记=$withCredit 不补记=$without —— 补记必须明显把行程找回来",
            withCredit > without + 0.03f,
        )
        assertTrue("补记后按段跟手比例仍不达标：$withCredit", withCredit >= 0.85f)
    }
}
