package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sign

private fun newScrollTracker() = ScrollTracker(
    ease = 0.55f,
    maxStepPx = 170f,
    minDragPx = 28f,
    topPx = 240f,
    bottomPx = 2040f,
    // 这里刻意用 holdFrames=1：本文件测的是"追上目标"本身的性质，抬手策略由
    // ScrollTrackerHoldTest 单独守（默认值是 hold=10 / dwell=8）
    holdFrames = 1,
    dwellFrames = 2,
)

/**
 * 手感不变量的形式化测试：「不来回滚」「不乱跳」「不误触」都能写成可断言的性质，
 * 全部在 PC 上跑，不需要拿手机试错。
 */
class ScrollTrackerTest {

    private class Sim(val tracker: ScrollTracker = newScrollTracker()) {
        /** 每次抬手时的累计拖拽位移 */
        val drags = ArrayList<Float>()
        var appliedAtLastCheck = 0f

        private var down = false
        private var lastY = 0f

        fun apply(step: ScrollTracker.Step) {
            for (op in step.ops) {
                when (op) {
                    is ScrollTracker.Op.Down -> {
                        assertFalse("连续两次 DOWN 之间没有 UP，事件序列非法", down)
                        down = true
                        lastY = op.y
                    }
                    is ScrollTracker.Op.Move -> if (down) lastY = op.y
                    is ScrollTracker.Op.Up -> {
                        assertTrue("UP 之前没有 DOWN", down)
                        drags += tracker.movedSinceDown
                        down = false
                    }
                }
            }
        }

        /** 跑到静止；返回帧数 */
        fun settle(maxFrames: Int = 600): Int {
            var n = 0
            while (n < maxFrames) {
                val s = tracker.frame()
                apply(s)
                n++
                if (!s.keepGoing) break
            }
            return n
        }

        /** 手指是否还按在屏幕上 */
        val fingerStillDown: Boolean get() = down
    }

    @Test
    fun `位移永不越过目标`() {
        val sim = Sim()
        sim.tracker.targetPx = 900f
        val n = sim.settle()
        assertEquals(900f, sim.tracker.appliedPx, 0.6f)
        assertTrue("收敛太慢：$n 帧", n < 60)
        assertFalse("收尾后手指还按着", sim.fingerStillDown)
    }

    @Test
    fun `每一步都朝目标方向走`() {
        val sim = Sim()
        sim.tracker.targetPx = 1200f
        sim.settle()
        sim.tracker.targetPx = 200f          // 中途反向：最容易翻车的场景
        var frames = 0
        while (frames < 200) {
            val errBefore = sim.tracker.errorPx
            val before = sim.tracker.appliedPx
            val s = sim.tracker.frame()
            sim.apply(s)
            frames++
            val d = sim.tracker.appliedPx - before
            if (d != 0f && !sim.tracker.lastFramePadded) {
                assertEquals("动的时候误差必须同号", errBefore.sign.toDouble(), d.sign.toDouble(), 0.001)
                assertTrue("单步越过目标", abs(sim.tracker.errorPx) < abs(errBefore))
            }
            if (!s.keepGoing) break
        }
        assertTrue(abs(sim.tracker.errorPx) < 1f)
    }

    @Test
    fun `来回转动时误差只由目标变化引起`() {
        // 真机病根：池子/惯性带着画面继续朝旧方向走 -> 目标已经反了、画面还在往前。
        // 这里用锯齿目标（转过去再转回来）验证： tracker 自己绝不会把误差变大。
        val sim = Sim()
        var target = 0f
        for (k in 0..6) {
            val seq = (0..25).map { it * 40f } + (25 downTo 0).map { it * 40f }
            for (v in seq) {
                val errBefore = abs(sim.tracker.errorPx)
                val dTarget = abs(v - target)
                target = v.toFloat()
                sim.tracker.targetPx = target
                sim.apply(sim.tracker.frame())
                val errAfter = abs(sim.tracker.errorPx)
                if (sim.tracker.lastFramePadded) continue  // 防误触补位是故意反推一下
                assertTrue(
                    "误差自己变大了：before=$errBefore after=$errAfter dTarget=$dTarget",
                    errAfter <= errBefore + dTarget + 0.001f
                )
            }
        }
        sim.settle()
        assertEquals(target, sim.tracker.appliedPx, 1f)
        for (d in sim.drags) assertTrue("有抬手位移不足 touch slop：$d", d >= 28f)
    }

    @Test
    fun `微小抖动不落指`() {
        val sim = Sim()
        for (i in 1..20) {
            sim.tracker.targetPx = i * 1.5f
            sim.apply(sim.tracker.frame())
        }
        assertTrue("亚 touch slop 的量就被当拖动落指了", sim.drags.isEmpty())
        assertFalse(sim.fingerStillDown)

        // 攒够 slop 之后一次补上
        sim.tracker.targetPx = 60f
        sim.settle()
        assertTrue(sim.drags.isNotEmpty())
        for (d in sim.drags) assertTrue("抬手时只拖了 $d px", d >= 28f)
    }

    @Test
    fun `追到手指边界后换边继续追`() {
        val sim = Sim()
        sim.tracker.targetPx = 5000f       // 远超一次拖拽行程（1800px）
        sim.settle(900)
        assertEquals(5000f, sim.tracker.appliedPx, 1f)
        assertTrue("应该重抓多次，实际 ${sim.drags.size} 次", sim.drags.size >= 2)
        for (d in sim.drags) assertTrue("重抓时有误触风险: $d", d >= 28f)
        assertFalse(sim.fingerStillDown)
    }

    @Test
    fun `单帧位移不超过上限`() {
        val sim = Sim()
        sim.tracker.targetPx = 20000f
        var prevY: Float? = null
        var frames = 0
        while (frames < 300) {
            val s = sim.tracker.frame()
            sim.apply(s)
            frames++
            val down = s.ops.filterIsInstance<ScrollTracker.Op.Down>().firstOrNull()
            if (down != null) prevY = down.y
            val move = s.ops.filterIsInstance<ScrollTracker.Op.Move>().firstOrNull()
            if (move != null) {
                prevY?.let { assertTrue("单帧跳了 ${abs(move.y - it)}", abs(move.y - it) <= 170.01f) }
                prevY = move.y
            }
            if (!s.keepGoing) break
        }
    }

    @Test
    fun `换向时立刻收尾不拖尾巴`() {
        val sim = Sim()
        sim.tracker.targetPx = 1500f
        repeat(8) { sim.apply(sim.tracker.frame()) }   // 走一小段，手还没停
        val before = sim.tracker.appliedPx
        sim.tracker.targetPx = 100f                    // 突然反向
        // 反向后应该在几帧内开始往回走（不等惯性跑完）
        var reversed = -1
        for (i in 0 until 10) {
            val s = sim.tracker.frame()
            sim.apply(s)
            if (sim.tracker.appliedPx < before - 0.5f) { reversed = i; break }
        }
        assertTrue("反向后 10 帧还没跟上：$reversed", reversed >= 0)
    }
}
