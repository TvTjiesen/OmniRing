package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

/** 17U 徕卡版实测分辨率 */
private const val SCREEN_H = 2608f

/**
 * 端到端离线仿真：真机录的 SMP 样本 -> CrownModel（目标）-> ScrollTracker（手指）。
 *
 * 他要的是"先在你电脑上模拟一遍再实装"，这个用例就是干这个的：
 *  1) 两个方向必须对称 —— 单向失效在注入端必须看不见；
 *  2) 落指 y 必须落在拖拽带内（旧版 0.10 那个点会压在 B站/短视频页的播放器画面上，
 *     播放器自己吃掉竖向手势 -> 真机上就是"往上滚没反应"）；
 *  3) 抬手前必须真拖过 touch slop（防误触点击）；
 *  4) 每一步都朝目标走，不许有开环尾巴。
 */
class ScrollReplaySymmetryTest {

    private data class Smp(val tms: Long, val pos: Float, val tgt: Float)

    private fun load(name: String): List<Smp> {
        val f = listOf(name, "../$name", "app/$name").map(::File).firstOrNull { it.exists() && it.length() > 500 }
            ?: return emptyList()
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+) tgt=(-?[\d.]+)""")
        val out = ArrayList<Smp>()
        var t0 = -1.0
        for (line in f.readLines()) {
            val m = re.find(line) ?: continue
            val sec = m.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            out += Smp(((sec - t0) * 1000.0).toLong(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
        }
        return out
    }

    private class Run(topFrac: Float, bottomFrac: Float, private val gain: Float) {
        val tracker = ScrollTracker(
            ease = 0.55f, maxStepPx = 170f, minDragPx = 28f,
            topPx = SCREEN_H * topFrac, bottomPx = SCREEN_H * bottomFrac,
        )
        var posTarget = 0f
        var negTarget = 0f
        var posApplied = 0f
        var negApplied = 0f
        var drags = 0
        var minDrag = Float.MAX_VALUE
        var downYs = ArrayList<Float>()
        var frames = 0
        var reverseSteps = 0

        fun feed(targetPx: Float, prevTargetPx: Float) {
            val d = targetPx - prevTargetPx
            if (d > 0) posTarget += d else negTarget += d
            tracker.targetPx = targetPx
            var guard = 0
            while (guard++ < 12) {
                val before = tracker.appliedPx
                val errBefore = tracker.errorPx
                val step = tracker.frame()
                frames++
                val dd = tracker.appliedPx - before
                if (dd != 0f && dd.sign != errBefore.sign && !tracker.lastFramePadded) reverseSteps++
                if (dd > 0) posApplied += dd else negApplied += dd
                for (op in step.ops) when (op) {
                    is ScrollTracker.Op.Down -> {
                        drags++
                        downYs += op.y
                    }
                    is ScrollTracker.Op.Move -> Unit
                    is ScrollTracker.Op.Up -> minDrag = minOf(minDrag, tracker.movedSinceDown)
                }
                if (!step.keepGoing) break
            }
        }
    }

    @Test
    fun `两个方向对称 且落点不在播放器上`() {
        val rows = load("diag.log")
        if (rows.size < 60) {
            println("diag.log 不可用，跳过（${rows.size} 条）")
            return
        }
        val gain = 0.95f
        val run = Run(topFrac = 0.30f, bottomFrac = 0.72f, gain = gain)
        var prev = 0f
        for (r in rows) {
            run.feed(r.tgt * gain, prev)
            prev = r.tgt * gain
        }

        println(
            "新带 0.30-0.72：目标 正=${run.posTarget.toInt()} 负=${run.negTarget.toInt()} / " +
                "实滚 正=${run.posApplied.toInt()} 负=${run.negApplied.toInt()} / " +
                "拖拽${run.drags}次 最小拖距=${"%.0f".format(run.minDrag)} 反向追=${run.reverseSteps}"
        )
        // 1) 两个方向都得真的在滚，且比例对称
        assertTrue("正向没滚：${run.posApplied}", run.posApplied > 500f)
        assertTrue("负向没滚：${run.negApplied}", run.negApplied < -500f)
        val posRatio = run.posApplied / run.posTarget
        val negRatio = run.negApplied / run.negTarget
        println("跟踪比：正=${"%.2f".format(posRatio)} 负=${"%.2f".format(negRatio)}")
        assertTrue("两个方向不对称：正=$posRatio 负=$negRatio", abs(posRatio - negRatio) < 0.15f)
        assertTrue("跟踪比过低（丢帧/被吞）：$posRatio", posRatio > 0.85f)

        // 2) 落点必须在带内，且上滚不能落在屏幕顶部（那里是播放器）
        val top = SCREEN_H * 0.30f
        val bottom = SCREEN_H * 0.72f
        for (y in run.downYs) assertTrue("落指点 $y 出带", y >= top - 1 && y <= bottom + 1)
        assertTrue("有落指点压在顶部 15% 区域内：${run.downYs.minOrNull()}", run.downYs.min()!! > SCREEN_H * 0.15f)

        // 3) 防误触
        assertTrue("最小拖距 ${run.minDrag} 不足 slop", run.minDrag >= 28f)
        // 4) 无开环反向追
        assertEquals("出现反向追", 0, run.reverseSteps)
    }

    @Test
    fun `旧落点带会压到播放器 这是新带存在的原因`() {
        val rows = load("diag.log")
        if (rows.size < 60) return
        val old = Run(topFrac = 0.10f, bottomFrac = 0.85f, gain = 0.95f)
        var prev = 0f
        for (r in rows) {
            old.feed(r.tgt * 0.95f, prev)
            prev = r.tgt * 0.95f
        }
        val lowest = old.downYs.minOrNull() ?: Float.MAX_VALUE
        println("旧带 0.10-0.85：上滚落点 y=$lowest（屏幕高 $SCREEN_H，即 ${(lowest / SCREEN_H * 100).toInt()}% 处）")
        // 旧版往上滚时手指落在屏幕最上沿，B站/短视频页那一块是播放器画面 -> 手势被吃掉
        assertTrue("旧带没复现出问题落点", lowest < SCREEN_H * 0.15f)
    }
}
