package xyz.nextalone.hyperzoomring.ui.liquid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 底栏形变的纯函数验收。
 *
 * 真机上这块只能靠"拖拽中途抓一帧截图"看，很容易看漏两类致命情况：
 * 松手回不回零（回不了零底栏就永久歪着）、以及左右是否对称。
 * 所以把算式抽到 [SmearMath] 在这里钉死。
 */
class SmearMathTest {

    private val tab = 175f

    private fun smear(dragPx: Float, lead: Float = 0f, tabs: Int = 5) =
        SmearMath.smear(dragPx, tab, lead, tabs)

    @Test
    fun `静止时完全无形变`() {
        assertEquals(0f, smear(0f), 1e-6f)
        assertEquals(1f, SmearMath.scaleX(smear(0f)), 1e-6f)
        assertEquals(1f, SmearMath.scaleY(smear(0f)), 1e-6f)
    }

    @Test
    fun `松手回到零后形变必须归零`() {
        // 拖到最远 → 橡皮筋把 offsetAnimation 拉回 0 → 必须精确回到无形变
        val pulled = smear(600f)
        assertTrue(pulled > 0f)
        assertEquals(0f, smear(0f, lead = 0f), 1e-6f)
    }

    @Test
    fun `左右对称`() {
        for (d in listOf(40f, 175f, 600f)) {
            assertEquals(smear(d), -smear(-d), 1e-6f)
            assertEquals(SmearMath.scaleX(smear(d)), SmearMath.scaleX(smear(-d)), 1e-6f)
        }
    }

    @Test
    fun `支点落在背对手指的一侧`() {
        assertEquals(0f, SmearMath.pivotX(smear(200f)), 1e-6f)   // 往右拽 → 左边固定，右边缘伸出去
        assertEquals(1f, SmearMath.pivotX(smear(-200f)), 1e-6f)
        assertEquals(0f, SmearMath.pivotX(0f), 1e-6f)            // 0 时两边等价，取哪边都不影响画面
    }

    @Test
    fun `横向拉长纵向压扁`() {
        val s = smear(300f)
        assertTrue("应拉长：${SmearMath.scaleX(s)}", SmearMath.scaleX(s) > 1f)
        assertTrue("应压扁：${SmearMath.scaleY(s)}", SmearMath.scaleY(s) < 1f)
        // 幅度别太夸张，否则文字会明显被拉糊
        // 幅度钉死：最大只能拉长 3%、压扁 1.5%，肉眼是"灵动"而不是"被拉长"
        assertTrue("拉长上限超了：${SmearMath.scaleX(s)}", SmearMath.scaleX(s) <= 1f + SmearMath.MAX_SCALE_X + 1e-4f)
        assertTrue("压扁上限超了：${SmearMath.scaleY(s)}", SmearMath.scaleY(s) >= 1f - SmearMath.MAX_SCALE_Y - 1e-4f)
    }

    @Test
    fun `形变封顶不会溢出`() {
        // 单看拖拽项最多 1.0
        assertEquals(1f, smear(999_999f), 1e-4f)
        assertEquals(-1f, smear(-999_999f), 1e-4f)
        // 拖到底再叠领先量，也只能顶到 LIMIT，不能更多
        assertEquals(SmearMath.LIMIT, smear(999_999f, lead = 9f), 1e-4f)
        assertEquals(-SmearMath.LIMIT, smear(-999_999f, lead = -9f), 1e-4f)
    }

    @Test
    fun `点切页时滑块领先会让整条栏朝新tab倾一下`() {
        // 目标在第 1 格、当前还在第 0 格：lead = +1
        val lean = smear(0f, lead = 1f)
        assertTrue("应该往右倾：$lean", lean > 0f)
        assertTrue("但幅度要克制", abs(lean) < 0.5f)
        assertTrue(SmearMath.scaleX(lean) > 1f)
    }

    @Test
    fun `还没量出宽度时不能出 NaN`() {
        val s = SmearMath.smear(120f, tabWidthPx = 0f, leadUnits = 1f, tabsCount = 5)
        assertEquals(0f, s, 1e-6f)
        // 只有一个 tab 时没有"领先"可言，也不能除零
        assertEquals(0f, SmearMath.smear(0f, tab, 1f, 1), 1e-6f)
        assertTrue(SmearMath.smear(0f, tab, 1f, 0).isFinite())
    }

    @Test
    fun `拖满一格只到三成形变`() {
        // 手感标定：拖一整格（=tabWidth）只该到约 38% 形变，轻轻一碰就拉满是上一版被否的原因
        assertEquals(1f / SmearMath.DRAG_FULL_PX_FACTOR, smear(tab), 1e-4f)
        assertTrue(smear(tab) < 0.45f)
    }

    @Test
    fun `拖满再回零，全程连续且最终无形变`() {
        // 一把 10 帧、每帧 +20px 的拖拽，再按每帧衰减 15% 松手回到 0。
        // 注意别写成 `while (dec > 0f) dec *= 0.72f`：Float 在次正规区会乘回自身，
        // 那是个死循环（第一版就把测试 JVM 的堆吃爆了）。
        val frames = ArrayList<Float>()
        var raw = 0f
        repeat(10) {
            raw += 20f
            frames.add(smear(raw, lead = 0f))
        }
        var dec = raw
        repeat(30) {
            dec *= 0.85f
            frames.add(smear(dec, lead = 0f))
        }
        frames.add(smear(0f, lead = 0f))

        // 1) 拖拽段单调变大
        for (i in 1 until 10) assertTrue("第 $i 帧应比上一帧更形变", frames[i] > frames[i - 1])
        // 2) 全程不越界
        assertTrue(frames.all { abs(it) <= SmearMath.LIMIT + 1e-4f })
        // 3) 相邻帧不能跳（跳了就是肉眼可见的"弹一下"）
        for (i in 1 until frames.size) {
            val dScale = abs(SmearMath.scaleX(frames[i]) - SmearMath.scaleX(frames[i - 1]))
            assertTrue("第 $i 帧缩放突变 $dScale", dScale < 0.01f)
        }
        // 4) 最终严格回到无形变：回不了零底栏就永久歪着
        assertEquals(0f, frames.last(), 1e-6f)
        assertEquals(1f, SmearMath.scaleX(frames.last()), 1e-6f)
        assertEquals(1f, SmearMath.scaleY(frames.last()), 1e-6f)
    }
}
