package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「抽搐」的根因回归测试（真机 461 样本回放定位，见 build/replay_v3.txt）：
 *
 * 旧行为是**每追平一次目标就抬手**（ease=0.55 三帧就把误差吃干，而传感器 20~40ms
 * 才来一个新目标），回放里 34.5 秒抬手 96 次 = 每秒 2.8 次"抓-放"。每次抬手前只有
 * 2 帧（32ms）零速，而控件的 VelocityTracker 看的是最近约 100ms，所以 fling 压不掉：
 * 内容先被甩出去、下一根手指的 DOWN 又把它打断 —— 观感就是抖/抽。
 *
 * 因此要守住的性质：
 *  1. 还在转（目标持续到来）时手指绝不抬手，一次拖拽用满整条拖拽带；
 *  2. 每次 UP 之前必须有足够的零速帧，fling 才压得住；
 *  3. 但停留总时长必须远小于 500ms 长按阈值，否则列表会弹出长按菜单。
 */
class ScrollTrackerHoldTest {

    private class Rec(
        hold: Int,
        dwell: Int,
        edgeInstant: Boolean = true,
    ) {
        val tracker = ScrollTracker(
            ease = 0.55f, maxStepPx = 170f, minDragPx = 28f,
            topPx = 782f, bottomPx = 1878f,      // 真机 0.30/0.72 × 2608
            holdFrames = hold, dwellFrames = dwell, edgeInstant = edgeInstant,
        )
        var down = false
        var lastY = 0f
        var zeroMoves = 0
        val ups = ArrayList<UpStat>()
        var currentZeroRun = 0

        data class UpStat(val zeroBefore: Int, val moved: Float)

        fun apply(step: ScrollTracker.Step) {
            for (op in step.ops) {
                when (op) {
                    is ScrollTracker.Op.Down -> { down = true; lastY = op.y; currentZeroRun = 0 }
                    is ScrollTracker.Op.Move -> {
                        if (down) {
                            if (abs(op.y - lastY) < 0.5f) currentZeroRun++ else currentZeroRun = 0
                            lastY = op.y
                        }
                    }
                    is ScrollTracker.Op.Up -> {
                        assertTrue("UP 之前没有 DOWN", down)
                        ups += UpStat(currentZeroRun, tracker.movedSinceDown)
                        down = false; currentZeroRun = 0
                    }
                }
            }
        }

        /** 喂一个"新目标 + 若干空帧"，模拟传感器 32ms 一个样本、循环 16ms 一帧 */
        fun sample(newTarget: Float, gapFrames: Int = 2) {
            tracker.targetPx = newTarget
            for (i in 0 until gapFrames) apply(tracker.frame())
        }

        fun run(frames: Int) {
            for (i in 0 until frames) apply(tracker.frame())
        }
    }

    @Test
    fun `还在转的时候手指不抬手`() {
        val r = Rec(hold = 10, dwell = 8)
        // 12 个样本 × 120px = 1440px，短于拖拽带 1096px*? —— 用 8 个样本保证不撞边
        var target = 0f
        for (k in 1..8) {
            target += 120f
            r.sample(target)
        }
        assertTrue("转的过程中抬手了 ${r.ups.size} 次 —— 这就是抽搐", r.ups.isEmpty())
        assertTrue("手指应该还按在屏幕上继续拖", r.down)
        assertTrue("没拖够 touch slop: ${r.tracker.movedSinceDown}", r.tracker.movedSinceDown >= 28f)
    }

    @Test
    fun `停手之后才抬手，并且抬手前压了零速`() {
        val r = Rec(hold = 10, dwell = 8)
        var target = 0f
        for (k in 1..6) { target += 120f; r.sample(target) }
        assertTrue(r.ups.isEmpty())
        r.run(40)                                   // 不再来新目标
        assertTrue("停手后必须抬手，否则长按菜单会弹", r.ups.size == 1)
        assertTrue("抬手前零速帧只有 ${r.ups[0].zeroBefore}，压不住 fling",
            r.ups[0].zeroBefore >= 8)
        assertTrue("抬手位移不足 touch slop", r.ups[0].moved >= 28f)
    }

    @Test
    fun `停留时长必须远小于长按阈值`() {
        val r = Rec(hold = 10, dwell = 8)
        r.tracker.targetPx = 600f
        r.run(300)
        assertTrue(r.ups.isNotEmpty())
        // hold + dwell 帧是手指"原地不动"的上限；16ms/帧，必须 < 500ms 长按阈值的一半以上余量
        val maxIdleFrames = 10 + 8 + 2
        assertTrue("停留会撑到 ${maxIdleFrames * 16}ms，有长按风险", maxIdleFrames * 16 < 500)
    }

    @Test
    fun `撞边换指不许停在半路`() {
        // 撞边那一刻误差还大着，正是"还有货没送出去"。在这里压零速 = 把停顿压在关键路径上，
        // 他 2026-10-13 说的"卡手更频繁了"就是上一版这么改造成的（回放量出来 274 vs 84 帧）。
        val fast = Rec(hold = 10, dwell = 8, edgeInstant = true)
        val slow = Rec(hold = 10, dwell = 8, edgeInstant = false)
        fast.tracker.targetPx = 4000f                 // 远超一条拖拽带，必然反复撞边
        slow.tracker.targetPx = 4000f
        val nFast = framesUntilDone(fast, 600)
        val nSlow = framesUntilDone(slow, 600)
        assertTrue("换指路径没走通：applied=${fast.tracker.appliedPx}", fast.tracker.appliedPx > 3900f)
        assertTrue(
            "撞边压零速多花了 ${nSlow - nFast} 帧空等（dwell=${slow.tracker.dwellFrames}/次换指），" +
                "就地换指必须明显更快",
            nFast + 20 < nSlow,
        )
        assertTrue("应该多次换指，实际 ${fast.ups.size}", fast.ups.size >= 2)
        for ((idx, u) in fast.ups.withIndex()) {
            assertTrue("第 $idx 次换指位移 ${u.moved}px 有误触风险", u.moved >= 28f)
        }
    }

    /** 跑到"目标追平且手指收回"，返回帧数；这就是"送完这批货用了多久" */
    private fun framesUntilDone(r: Rec, maxFrames: Int): Int {
        var n = 0
        while (n < maxFrames) {
            r.run(1)
            n++
            if (!r.down && r.tracker.errorPx < 1f) break
        }
        return n
    }

    @Test
    fun `中途反方向不用重新落指`() {
        val r = Rec(hold = 10, dwell = 8)
        var target = 0f
        for (k in 1..6) { target += 120f; r.sample(target) }
        // 立刻反向：手指还按着，误差变号就应当朝反方向走，而不是抬手再抓
        for (k in 1..4) { target -= 120f; r.sample(target) }
        assertTrue("来回转时抬手了 ${r.ups.size} 次", r.ups.isEmpty())
        assertTrue(r.down)
        val y = r.lastY
        r.sample(target - 200f)
        assertTrue("反向后手指必须往回走", abs(r.lastY - y) > 1f)
    }
}
