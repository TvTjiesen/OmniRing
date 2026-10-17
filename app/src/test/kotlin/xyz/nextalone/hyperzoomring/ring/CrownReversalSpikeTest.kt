package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 「往前拨却往反方向一蹿」的根因回归。
 *
 * 证据来自他自己那两段真机日志（app/src/test/fixtures/smp_live.log 与 smp_wiggle.log）：
 * 换向一共 204 次，其中 **123 次是"单个样本就撑够回差"**，而这类翻转里很多下一帧
 * 方向又弹回原样（来回弹）。单帧尖峰最大能把 ±300~400 单位（≈470px）一次性记进里程表，
 * 于是画面就往反方向蹿一大截 —— 和他描述的"很小幅度波动时有概率反向、概率很大"完全对上。
 *
 * 修法只改一条：换向必须由 [CrownModel.reverseMinSamples] 个连续反向样本共同成立。
 * 真实换向本来就跨多帧（数据里 78/81 次多样本翻转都只用 2 个样本），所以代价是慢一个样本。
 */
class CrownReversalSpikeTest {

    private data class Smp(val tms: Long, val pos: Float)

    private fun load(name: String): List<Smp> {
        val f = File("src/test/fixtures/$name")
        assertTrue("缺少真机样本 $name，回放断言会变成假绿灯", f.exists() && f.length() > 1000)
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+)""")
        var t0 = -1.0
        val out = ArrayList<Smp>()
        for (line in f.readLines()) {
            val m = re.find(line) ?: continue
            val sec = m.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            out += Smp(((sec - t0) * 1000.0).toLong(), m.groupValues[2].toFloat())
        }
        assertTrue("$name 解析出来是空的", out.size > 100)
        return out
    }

    private class Stat {
        var accepted = 0
        var flips = 0
        var pingPong = 0
        var biggestFlipUnits = 0f
        var lastFlipDir = 0
        var lastFlipAt = -1L
    }

    private fun run(samples: List<Smp>, minSamples: Int): Stat {
        val m = CrownModel()
        m.reverseMinSamples = minSamples
        val st = Stat()
        for (s in samples) {
            val dirBefore = m.dirSign
            val acc = m.sample(s.tms, s.pos)
            if (acc) {
                st.accepted++
                if (dirBefore != 0 && m.dirSign != 0 && m.dirSign != dirBefore) {
                    st.flips++
                    st.biggestFlipUnits = maxOf(st.biggestFlipUnits, abs(m.lastDeltaUnits))
                    if (st.lastFlipDir != 0 && m.dirSign == st.lastFlipDir &&
                        s.tms - st.lastFlipAt <= 150
                    ) {
                        st.pingPong++            // 150ms 内又翻回上一个方向 = 来回弹
                    }
                    st.lastFlipDir = dirBefore
                    st.lastFlipAt = s.tms
                }
            }
        }
        return st
    }

    private fun abs(v: Float) = if (v < 0f) -v else v

    @Test
    fun `旧判据在真机样本上会来回弹，新判据不弹`() {
        for (name in listOf("smp_live.log", "smp_wiggle.log")) {
            val s = load(name)
            val old = run(s, 1)
            val fixed = run(s, 2)
            assertTrue(
                "$name 单样本门必须有效：乒乓翻转 old=${old.pingPong} new=${fixed.pingPong}",
                fixed.pingPong * 4 <= old.pingPong,
            )
            assertTrue(
                "$name 不能把跟手砍没：被采纳样本 old=${old.accepted} new=${fixed.accepted}",
                fixed.accepted >= old.accepted * 0.75f,
            )
        }
    }

    @Test
    fun `孤立单帧尖峰既不翻方向也不记账`() {
        val m = CrownModel()
        // 16ms 一帧的稳定前进
        var pos = 0f
        repeat(12) {
            pos += 60f
            m.sample(1_000L + 16L * it, pos)
        }
        assertEquals(1, m.dirSign)
        val before = m.targetUnits

        // 一帧 -360 单位的孤立尖峰（真机实测范围），随后又恢复前进。
        // 要求的是"这一蹿绝不能被记成反向位移"：尖峰本身不翻方向锁，
        // 里程表也绝不低于尖峰之前的值（真反向运动要连续两帧以上才成立）。
        var lowest = m.targetUnits
        for (step in listOf(1_200L to pos - 360f, 1_216L to pos - 300f, 1_232L to pos - 240f)) {
            m.sample(step.first, step.second)
            lowest = minOf(lowest, m.targetUnits)
        }
        assertEquals("单帧尖峰不许把方向锁翻过去", 1, m.dirSign)
        assertTrue(
            "尖峰期间里程表倒退了 ${before - lowest} 单位（=$((before - lowest) * 1.2f}px 反向位移）",
            lowest >= before,
        )
    }

    @Test
    fun `真实换向只慢一个样本`() {
        val m = CrownModel()
        var pos = 0f
        repeat(20) {
            pos += 80f
            m.sample(1_000L + 20L * it, pos)
        }
        assertEquals(1, m.dirSign)
        // 真的往回转：连续多帧反向，每帧 90 单位
        var back = pos
        var firstFlipFrame = -1
        repeat(10) {
            val before = m.dirSign
            back -= 90f
            val acc = m.sample(1_400L + 20L * it, back)
            if (acc && before != -1 && firstFlipFrame < 0) firstFlipFrame = it
        }
        assertTrue("连续反向应当最终换向成功", m.dirSign == -1)
        assertTrue("换向太迟钝（第 $firstFlipFrame 帧才翻）", firstFlipFrame in 0..2)
    }

    @Test
    fun `加门之后总量不塌`() {
        // 门只能吞掉"孤立的假尖峰"，不能把正常来回拨的行程吞掉：
        // 他 2026-10-14 的反馈正是"返回那一下有时不动、有时幅度更小"
        val s = load("smp_wiggle.log")
        val fixed = run(s, 2)
        val old = run(s, 1)
        assertTrue("换向次数不该暴涨：old=${old.flips} new=${fixed.flips}", fixed.flips <= old.flips)
        assertTrue(
            "被采纳总量塌了：old=${old.accepted} new=${fixed.accepted}",
            fixed.accepted >= old.accepted * 0.8f,
        )
        assertTrue(
            "单笔换向记账只能折叠 reverseMinSamples 个样本，不该超过旧判据 3 倍：" +
                "old=${old.biggestFlipUnits} new=${fixed.biggestFlipUnits}",
            fixed.biggestFlipUnits <= old.biggestFlipUnits * 3f + 1f,
        )
    }
}
