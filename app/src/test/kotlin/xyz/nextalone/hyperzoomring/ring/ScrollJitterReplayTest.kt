package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 「抽搐 / 卡手」的真机样本回放回归（app/src/test/fixtures/smp_live.log：
 * 他亲口说"还抽"之后录的 461 个 SMP 样本 / 34.5 秒，hzr_debug=1）。
 *
 * 断言用的是**观感量**而不是抬手次数，因为抬手次数会骗人：
 *  - 旧版"追平即抬手"抬手很多，但那些停顿都发生在没有货要送的时候，看不出来；
 *  - 我 2026-10-13 第一版把停留也加在撞边换指上，抬手次数砍了一半，
 *    反而让他说"卡手更频繁了" —— 因为停顿被挪到了"还有货没送出"的关键路径上。
 *
 * 所以主指标是 stall_behind：**手指按着、这一帧没有真实位移、而误差还大于 60px 的帧数**。
 * 同一份样本、同一套回放跑三组参数（旧 / 我上一版 / 现方案），必须一次比一次少。
 */
class ScrollJitterReplayTest {

    private data class Smp(val tms: Long, val pos: Float)

    private fun load(name: String): List<Smp> {
        val f = File("src/test/fixtures/$name")
        assertTrue("缺少真机样本，回放断言会变成假绿灯：$f", f.exists() && f.length() > 1000)
        val re = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HyperZoomRing: SMP pos=(-?[\d.]+)""")
        var t0 = -1.0
        val out = ArrayList<Smp>()
        for (line in f.readLines()) {
            val m = re.find(line) ?: continue
            val sec = m.groupValues[1].toDouble()
            if (t0 < 0) t0 = sec
            out += Smp(((sec - t0) * 1000.0).toLong(), m.groupValues[2].toFloat())
        }
        return out
    }

    private class Replay(
        val tracker: ScrollTracker,
        val frameMs: Int = 16,
        val gain: Float = 1.20f,
    ) {
        init {
            tracker.topPx = 2608 * 0.30f
            tracker.bottomPx = 2608 * 0.72f
        }

        var releases = 0; private set
        var stallBehindFrames = 0; private set
        var illegalSeq = 0; private set
        var maxBacklogPx = 0f; private set
        /** 换指空窗：抬手那一刻还剩很多货没送，到下一帧重新落指之间过了多少毫秒 */
        var maxCrossingGapMs = 0L; private set
        var dragTotal = 0f; private set

        private val crown = CrownModel()
        private val odometer = ScrollModeModel()
        private var down = false
        private var lastY = 0f
        private var upAt = -1L
        private var pendingAtUp = 0f

        fun run(samples: List<Smp>) {
            require(samples.size > 2)
            var now = samples.first().tms
            for (i in 1 until samples.size) {
                val ts = samples[i].tms
                while (now < ts) {
                    now += frameMs
                    val step = tracker.frame()
                    val movedReal = apply(step, now)
                    val err = abs(tracker.errorPx)
                    maxBacklogPx = maxOf(maxBacklogPx, err)
                    if (down && movedReal < 0.5f && err > 60f) stallBehindFrames++
                    if (!step.keepGoing) {
                        now = ts          // 循环真的停了：等下一个样本重新唤醒
                        break
                    }
                }
                if (crown.sample(ts, samples[i].pos)) {
                    odometer.onAccepted(crown.lastDeltaUnits)
                    tracker.targetPx = odometer.targetPx(ScrollModeModel.SCROLL_CROWN, gain)
                }
            }
        }

        /** @return 这一帧手指真实走了多少像素（零速 Move / Down / Up 都算 0） */
        private fun apply(step: ScrollTracker.Step, now: Long): Float {
            var movedReal = 0f
            for (op in step.ops) {
                when (op) {
                    is ScrollTracker.Op.Down -> {
                        if (down) illegalSeq++
                        // 只有"还有货没送完"的那次抬手才叫换指空窗；
                        // 停手之后的空窗是正常休息，不该算进观感
                        if (upAt >= 0 && pendingAtUp > 60f) {
                            maxCrossingGapMs = maxOf(maxCrossingGapMs, now - upAt)
                        }
                        down = true; lastY = op.y
                    }
                    is ScrollTracker.Op.Move -> if (down) {
                        movedReal = maxOf(movedReal, abs(op.y - lastY))
                        dragTotal += abs(op.y - lastY)
                        lastY = op.y
                    }
                    is ScrollTracker.Op.Up -> {
                        if (!down) illegalSeq++
                        down = false; releases++; upAt = now
                        pendingAtUp = abs(tracker.errorPx)
                    }
                }
            }
            return movedReal
        }
    }

    private fun replay(hold: Int, dwell: Int, edgeInstant: Boolean) = Replay(
        ScrollTracker(holdFrames = hold, dwellFrames = dwell, edgeInstant = edgeInstant)
    )

    @Test
    fun `三组参数在观感量上一次比一次少`() {
        for (name in listOf("smp_live.log", "smp_wiggle.log")) {
            val s = load(name)
            val old = replay(1, 2, false).apply { run(s) }      // 他最初报抽搐的版本
            val shipped = replay(10, 8, false).apply { run(s) }  // 我 10-13 第一版（更卡）
            val fixed = replay(10, 8, true).apply { run(s) }     // 现方案：撞边不停手

            assertTrue("$name 事件序列非法 ${fixed.illegalSeq} 次", fixed.illegalSeq == 0)
            assertTrue(
                "$name 旧版卡手帧=${old.stallBehindFrames}，我上一版=${shipped.stallBehindFrames} —— " +
                    "这一条复现了他说的'这次频率更高了'：停顿确实被挪到了关键路径上",
                shipped.stallBehindFrames > old.stallBehindFrames,
            )
            assertTrue(
                "$name 现方案必须同时赢过两个参照：卡手帧 fixed=${fixed.stallBehindFrames} " +
                    "old=${old.stallBehindFrames} shipped=${shipped.stallBehindFrames}",
                fixed.stallBehindFrames < old.stallBehindFrames &&
                    fixed.stallBehindFrames * 2 < shipped.stallBehindFrames,
            )
        }
    }

    @Test
    fun `现方案仍然保住防误触与停手收尾`() {
        val s = load("smp_live.log")
        val r = replay(10, 8, true)
        r.run(s)
        assertTrue("一次都没抬手说明回放根本没走通", r.releases > 0)
        assertTrue(
            "换指空窗 ${r.maxCrossingGapMs}ms：还有货没送完时不许出现空窗（旧版这里要等 ~240ms 才重抓，" +
                "fling 被掐断就是那一下）",
            r.maxCrossingGapMs <= 48L,
        )
        assertTrue("最长堆积 ${r.maxBacklogPx}px，说明送不出去", r.maxBacklogPx < 1500f)
        assertTrue("累计位移为 0，回放没跑起来", r.dragTotal > 1000f)
    }

    @Test
    fun `现参数下一次拖拽能用满整条拖拽带`() {
        val s = load("smp_live.log")
        val old = replay(1, 2, false).apply { run(s) }
        val fixed = replay(10, 8, true).apply { run(s) }
        val perOld = old.dragTotal / old.releases.coerceAtLeast(1)
        val perNew = fixed.dragTotal / fixed.releases.coerceAtLeast(1)
        assertTrue(
            "单次拖拽长度 old=${perOld.toInt()}px new=${perNew.toInt()}px（拖拽带只有 1095px，" +
                "追平即抬手时每次只拖一小截，才是'抓-放'的根源）",
            perNew > perOld * 1.5f,
        )
    }
}
