package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 端到端离线回放：真机 SMP 样本 -> CrownModel（目标）-> ScrollTracker（手指动作）。
 *
 * 他要的"来回转动会乱跳""回到原点""误触点击"都是这条链路上的问题，
 * 所以回归也按链路做：不用手机，在 PC 上跑一遍录制数据就能验。
 * 没有 capture.log 时自动跳过。
 */
class ScrollReplayTest {

    private data class Smp(val tms: Long, val pos: Float)

    private fun load(): List<Smp> {
        val candidates = listOf("capture.log", "../capture.log", "app/capture.log")
        val f = candidates.map(::File).firstOrNull { it.exists() && it.length() > 500 } ?: return emptyList()
        val out = ArrayList<Smp>()
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+)""")
        var t0 = -1.0
        for (line in f.readLines()) {
            val m = re.find(line) ?: continue
            val sec = m.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            out += Smp(((sec - t0) * 1000.0).toLong(), m.groupValues[2].toFloat())
        }
        return out
    }

    @Test
    fun `replayed turning never chases the wrong way`() {
        val rows = load()
        if (rows.size < 50) {
            println("capture.log 不可用，跳过滚动回放（${rows.size} 条）")
            return
        }
        val model = CrownModel()
        val tracker = ScrollTracker(topPx = 240f, bottomPx = 2040f)
        val gain = 0.60f
        val frameMs = 16L
        var i = 0
        var now = 0L
        var worstErrGrowth = 0f
        var reverseSteps = 0
        var drags = 0
        var padFrames = 0
        var minDrag = Float.MAX_VALUE
        var down = false
        var moved = 0f
        var lastY = 0f
        var prevApplied = 0f
        var maxSingleStep = 0f

        while (now <= rows.last().tms + 2000) {
            while (i < rows.size && rows[i].tms <= now) {
                model.sample(rows[i].tms, rows[i].pos)
                tracker.targetPx = model.targetUnits * gain
                i++
            }
            val errBefore = abs(tracker.errorPx)
            prevApplied = tracker.appliedPx
            val step = tracker.frame()
            for (op in step.ops) when (op) {
                is ScrollTracker.Op.Down -> {
                    assertFalse("连续两次 DOWN", down)
                    down = true; moved = 0f; lastY = op.y
                }
                is ScrollTracker.Op.Move -> if (down) {
                    moved += abs(op.y - lastY); lastY = op.y
                }
                is ScrollTracker.Op.Up -> {
                    drags++
                    minDrag = minOf(minDrag, moved)
                    down = false
                }
            }
            val d = tracker.appliedPx - prevApplied
            maxSingleStep = maxOf(maxSingleStep, abs(d))
            if (tracker.lastFramePadded) {
                // 防误触补位移是故意朝反方向推一下的，单独统计代价，不算"乱跳"
                padFrames++
            } else if (d != 0f) {
                // 「乱跳」的形式化定义：目标没变的情况下 tracker 自己把误差搞大，
                // 或者动的方向和误差反号（反向追）
                val grew = abs(tracker.errorPx) - errBefore
                worstErrGrowth = maxOf(worstErrGrowth, grew)
                if (d > 0 != (tracker.targetPx - prevApplied) > 0) reverseSteps++
            }
            now += frameMs
        }

        println(
            "滚动回放：$drags 次拖拽，最小拖拽位移=$minDrag，防误触补位 $padFrames 帧/" +
                "${tracker.paddedPx}px，最大误差自增长=$worstErrGrowth，" +
                "反向追次数=$reverseSteps，单帧最大位移=$maxSingleStep"
        )
        assertTrue("存在反向追（该往前却往后）：$reverseSteps", reverseSteps == 0)
        assertTrue("误差会自己变大 = 有开环惯性：$worstErrGrowth", worstErrGrowth < 1f)
        assertTrue("单帧位移 $maxSingleStep 超过上限", maxSingleStep <= 170.01f)
        if (drags > 0) assertTrue("有拖拽位移不足 touch slop（会误触点击）：$minDrag", minDrag >= 28f)
    }
}
