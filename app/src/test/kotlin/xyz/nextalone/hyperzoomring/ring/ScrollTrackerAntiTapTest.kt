package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * 抬手不变量：只要发出了 UP，这一次 DOWN→UP 的**累计位移必须 ≥ touch slop**。
 *
 * 用户反馈"上下滑动有时会触发轻微点击，微博这种信息密集的平台特别明显"。
 * 这类问题的根因不在参数，而在"某些帧序列下漏了抬手前的位移补足"，
 * 所以不能靠一两个用例覆盖，得把 UP 之前走过的路径逐帧量出来，
 * 再用固定种子的随机序列扫一遍。
 *
 * 这里刻意只看**发出去的 op 序列**（外部世界看到的东西），
 * 不看 tracker 内部的 movedSinceDown —— 内部记账对了不代表发出去的手势对了。
 */
class ScrollTrackerAntiTapTest {

    private class Trace(val slopPx: Float) {
        var down = false
        var lastY = 0f
        var moved = 0f
        var violations = 0
        var ups = 0

        fun feed(op: ScrollTracker.Op) {
            when (op) {
                is ScrollTracker.Op.Down -> {
                    down = true; lastY = op.y; moved = 0f
                }
                is ScrollTracker.Op.Move -> {
                    if (down) { moved += abs(op.y - lastY); lastY = op.y }
                }
                is ScrollTracker.Op.Up -> {
                    if (!down) return
                    ups++
                    val dy = abs(op.y - lastY)
                    moved += dy
                    if (moved < slopPx) violations++
                    down = false
                }
            }
        }
    }

    private fun run(targets: List<Float>, slop: Float = 28f, maxFrames: Int = 4000): Trace {
        val t = ScrollTracker(minDragPx = slop)
        val tr = Trace(slop)
        var i = 0
        var frames = 0
        while (frames < maxFrames) {
            frames++
            // 每 3 帧推一次目标，模拟上游按样本节奏喂
            if (i < targets.size && frames % 3 == 0) {
                t.targetPx = targets[i]
                i++
            }
            val step = t.frame()
            step.ops.forEach(tr::feed)
            if (!step.keepGoing && i >= targets.size) break
        }
        return tr
    }

    @Test
    fun `每个抬手都走过足够位移`() {
        val cases = listOf(
            // 一上来就极小的目标：可能压根不该落指
            listOf(1f, 2f, 3f, 2f, 1f, 0f),
            // 落指瞬间目标反向（来回转最常见）
            listOf(400f, -380f, 390f, -390f, 0f),
            // 刚过门槛就归零
            listOf(36f, 0f, 36f, 0f),
            // 一路顶到手指边界
            listOf(3000f, 3000f, 3000f, 0f, 0f),
            // 边界处来回
            listOf(3000f, 2990f, 3000f, 2990f, 0f),
        )
        for ((idx, seq) in cases.withIndex()) {
            val tr = run(seq)
            assertTrue("用例#$idx 出现 ${tr.violations} 次零位移抬手（共 ${tr.ups} 次抬手）", tr.violations == 0)
        }
    }

    @Test
    fun `随机扰动下也不会零位移抬手`() {
        val rnd = Random(20261012)
        repeat(120) { seed ->
            val seq = ArrayList<Float>(40)
            var v = 0f
            repeat(40) {
                // 混合小步与大幅跳变，包含反向
                v += when {
                    it % 7 == 0 -> (rnd.nextInt(2000) - 1000).toFloat()
                    rnd.nextBoolean() -> rnd.nextInt(3) - 1f
                    else -> (rnd.nextInt(80) - 40).toFloat()
                }
                seq.add(v)
            }
            val tr = run(seq)
            assertTrue("seed=$seed 出现 ${tr.violations} 次零位移抬手", tr.violations == 0)
        }
    }

    @Test
    fun `小信号不会引起来回过冲`() {
        // "小幅转环时列表来回跳"的可测部分：±3 单位的抖动，
        // 净位移应当很小，且不能出现"往一个方向甩一大段再倒回来"
        val t = ScrollTracker(minDragPx = 28f)
        var maxSwing = 0f
        var prevApplied = 0f
        var dir = 0f
        var reversals = 0
        repeat(300) { frame ->
            t.targetPx = if (frame % 2 == 0) 6f else -6f
            val step = t.frame()
            step.ops.forEach { op ->
                if (op is ScrollTracker.Op.Move) {
                    val d = t.appliedPx - prevApplied
                    prevApplied = t.appliedPx
                    if (d != 0f) {
                        if (dir != 0f && d.sign != dir.sign) reversals++
                        dir = d.sign
                    }
                    maxSwing = maxOf(maxSwing, abs(t.appliedPx))
                }
            }
        }
        assertTrue("小幅抖动净位移过大：$maxSwing px", maxSwing < 90f)
    }
}
