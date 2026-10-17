package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 真机样本离线回放：把手机上 hzr_debug=1 录到的 SMP 行喂回模型，
 * 断言不再出现"停手后起转瞬间飞移"（他反馈的"回到原点"）。
 * 没有 capture.log 时自动跳过，不影响 CI。
 */
class CrownReplayTest {

    private data class Smp(val tms: Long, val pos: Float)

    private fun load(): List<Smp> {
        val candidates = listOf(
            "capture.log",
            "../capture.log",
            "app/capture.log",
        )
        val f = candidates.map(::File).firstOrNull { it.exists() && it.length() > 500 } ?: return emptyList()
        val out = ArrayList<Smp>()
        // 行首是 logcat -v epoch 的秒.毫秒时间戳，必须用真实间隔，
        // 否则"停手后重锚定"这条修复根本测不到（它靠 dt>idleResetMs 触发）
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+)""")
        var t0 = -1.0
        for (line in f.readLines()) {
            val m = re.find(line) ?: continue
            val sec = m.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            val tms = ((sec - t0) * 1000.0).toLong()
            out += Smp(tms, m.groupValues[2].toFloat())
        }
        return out
    }

    @Test
    fun `replayed session has no flying target jumps`() {
        val rows = load()
        if (rows.size < 50) {
            println("capture.log 不可用，跳过回放（${rows.size} 条）")
            return
        }
        val m = CrownModel()
        var prev = 0f
        var worst = 0f
        var worstAt = -1
        var resumeJumps = 0
        var worstOverClaim = 0f
        var windowAbs = 0f          // 自上次记账以来传感器走过的总路程（不含被丢弃的回零跳变）
        var lastPos = rows.first().pos
        rows.forEachIndexed { i, r ->
            val dt = if (i == 0) 0L else r.tms - rows[i - 1].tms
            val raw = r.pos - lastPos
            if (abs(raw) <= m.maxStepUnits) windowAbs += abs(raw)
            lastPos = r.pos
            val accepted = m.sample(r.tms, r.pos)
            val d = abs(m.targetUnits - prev)
            if (d > worst) { worst = d; worstAt = i }
            // 换向"补记"会把 2~3 个样本的反向行程折成一笔记账，所以单帧上限不再等于
            // maxStepUnits。真正的不变量是**不许凭空发明位移**：这一笔记的量必须不超过
            // 自上次记账以来传感器实际走过的路程（外加死区余量）。
            if (accepted) {
                val over = d - (windowAbs + m.deadband * 2f)
                if (over > worstOverClaim) worstOverClaim = over
                windowAbs = 0f
            }
            // 他反馈的"回到原点"就发生在这里：停手后传感器参考点归零，
            // 起转的第一帧若把该偏移当真实转动，目标会瞬间飞掉几百像素
            if (dt > 350 && d > 1f) resumeJumps++
            prev = m.targetUnits
        }
        assertEquals("停手后起转的帧不得推动目标（回到原点回归用例）", 0, resumeJumps)
        assertTrue(
            "存在凭空发明的位移：某一帧比传感器实际走的还多 $worstOverClaim 单位",
            worstOverClaim <= 0f,
        )
        val span = rows.last().tms - rows.first().tms
        val gaps = (1 until rows.size).count { rows[it].tms - rows[it - 1].tms > 350 }
        println("回放 ${rows.size} 条 / 跨度 ${span}ms / 含 ${gaps} 个停手间隙；最大单样本目标变化 = $worst（第 $worstAt 条）")
        assertTrue("回放里没有停手间隙，说明时间戳没解析成功，这个用例失去意义", gaps > 0)
        // 单帧最大记账量：补记会把最多 reverseMinSamples+1 个样本折成一笔，
        // 所以这里放宽到 3 倍 maxStepUnits；再大就说明不是折叠而是飞移
        assertTrue("存在飞移：$worst", worst <= m.maxStepUnits * 3f)
    }

    @Test
    fun `resume after pause does not inherit reference offset`() {
        val m = CrownModel(idleResetMs = 350)
        // 正向转到 pos=900
        var t = 0L
        repeat(10) { m.sample(t, it * 100f); t += 50 }
        val before = m.targetUnits
        // 停手 1 秒，期间传感器把参考点归零
        t += 1000
        m.sample(t, 0f)
        assertTrue("起转第一帧不得把参考点偏移算成转动: $before -> ${m.targetUnits}",
            abs(m.targetUnits - before) < 1f)
        // 之后正常转动应继续累积
        repeat(5) { t += 50; m.sample(t, it * 60f) }
        assertTrue(m.targetUnits > before)
    }
}
