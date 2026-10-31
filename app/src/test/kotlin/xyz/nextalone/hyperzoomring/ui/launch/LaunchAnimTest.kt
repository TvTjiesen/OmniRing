package xyz.nextalone.hyperzoomring.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动进入动画的时间规格（纯函数，PC 上可算可钉）。
 *
 * 为什么把节奏抽成纯函数：这套动画要"照小米17 Ultra 徕卡版相机那一下的手感"重做，
 * 但**主体得是我们自己的东西**（表冠 + 刻度 + 环被拨动），而且必须落在系统开屏退场动画
 * 1000ms 的硬上限里。节奏是数值设计，不是代码顺手写出来的 magic number，
 * 钉成单测之后每次改动都能立刻看出有没有把某一段挤爆。
 *
 * 参照实测（从相机包里的 `assets/guide_optical_zoom_anim.json` 拆出来的，只取节奏不搬素材）：
 * 60fps × 100 帧 = 1667ms，元素按 3 帧（50ms）错峰淡入、50→70→82 帧做"高亮滑到目标"。
 * 我们的压缩版保留同一套错峰与"先亮再滑"的顺序，总时长压到 900ms。
 */
class LaunchAnimTest {

    @Test
    fun `总时长不超过系统开屏退场的 1000ms 上限`() {
        assertTrue("实际 ${LaunchAnim.totalMs()}ms", LaunchAnim.totalMs() <= 1000L)
    }

    @Test
    fun `进场阶段每一项都是单调不减`() {
        // 来回抖动是我们反复栽过的坑（滑块形变那轮），进场量必须先增后平，不许回头
        for (i in 1..60) {
            val t = i * 15L
            val p = (t - 15L).coerceAtLeast(0L)
            assertTrue("crownAlpha 在 t=$t 回退了", LaunchAnim.crownAlpha(t) >= LaunchAnim.crownAlpha(p) - 1e-3f)
            assertTrue("labelAlpha 在 t=$t 回退了", LaunchAnim.labelAlpha(t) >= LaunchAnim.labelAlpha(p) - 1e-3f)
            for (k in 0 until LaunchAnim.TICK_COUNT) {
                assertTrue(
                    "tick[$k] 在 t=$t 回退了",
                    LaunchAnim.tickAlpha(t, k) >= LaunchAnim.tickAlpha(p, k) - 1e-3f,
                )
            }
        }
    }

    @Test
    fun `刻度按固定间隔错峰亮起，最后一格也在预算内完成`() {
        assertEquals(0f, LaunchAnim.tickAlpha(0L, 0), 1e-3f)
        // 第一格先亮，越靠后越晚（错峰读起来才是"依次被拨到"，一起亮就是一闪）
        val first = LaunchAnim.tickAlpha(LaunchAnim.TICK_START_MS + LaunchAnim.TICK_FADE_MS, 0)
        val last = LaunchAnim.tickAlpha(LaunchAnim.TICK_START_MS + LaunchAnim.TICK_FADE_MS, LaunchAnim.TICK_COUNT - 1)
        assertTrue("第 0 格该亮了：$first", first > 0.9f)
        assertTrue("最后一格这时候还不该亮：$last", last < 0.2f)
        val allDone = LaunchAnim.TICK_START_MS + (LaunchAnim.TICK_COUNT - 1) * LaunchAnim.TICK_STAGGER_MS + LaunchAnim.TICK_FADE_MS
        assertEquals(1f, LaunchAnim.tickAlpha(allDone, LaunchAnim.TICK_COUNT - 1), 1e-3f)
        assertTrue("刻度全部亮完要留在总时长里：$allDone > ${LaunchAnim.totalMs()}", allDone <= LaunchAnim.totalMs())
    }

    @Test
    fun `表冠缩放不许过冲`() {
        // 我们红线里写过：欠阻尼回弹 + 速度驱动的缩放叠起来就是"奇怪的抖动"。进场只许 0.84→1 单调落位。
        var prev = 0f
        for (t in 0..900L step 5) {
            val s = LaunchAnim.crownScale(t)
            assertTrue("scale 越界 t=$t s=$s", s > 0.8f && s <= 1.0001f)
            assertTrue("scale 回退了 t=$t", s >= prev - 1e-3f)
            prev = s
        }
        assertEquals(1f, LaunchAnim.crownScale(900L), 1e-3f)
    }

    @Test
    fun `环是被拨动一下：只往一个方向转，末速归零`() {
        // 正负来回 = 视觉上"环在抖"。这里必须单调到目标角，且 easeOut（起步快收尾慢）才像手指拨完松手。
        var prev = -1f
        for (t in 0..900L step 5) {
            val a = LaunchAnim.crownRotation(t)
            assertTrue("旋转回退了 t=$t", a >= prev - 1e-3f)
            prev = a
        }
        assertEquals(LaunchAnim.CROWN_SWEEP_DEG, LaunchAnim.crownRotation(900L), 0.2f)
        // easeOut 的特征：中段进度必须已经跑过一半以上
        val mid = LaunchAnim.crownRotation((LaunchAnim.SWEEP_START_MS + LaunchAnim.SWEEP_END_MS) / 2) / LaunchAnim.CROWN_SWEEP_DEG
        assertTrue("缓动太线性/太靠后，中段进度=$mid", mid > 0.6f)
    }

    @Test
    fun `整层只在最后一段淡出，前面一直不透明`() {
        assertEquals(1f, LaunchAnim.layerAlpha(400L), 1e-3f)
        assertEquals(1f, LaunchAnim.layerAlpha(LaunchAnim.FADE_OUT_START_MS - 1), 1e-3f)
        assertEquals(0f, LaunchAnim.layerAlpha(LaunchAnim.totalMs()), 1e-3f)
    }

    @Test
    fun `浅色主题下滚花齿必须换成深色墨水`() {
        // 真机（1200x2608 / 浅色主题）录屏量出来的：齿带在半径 21.8~27.6，而深色表盘只到 19.2 ——
        // 齿是画在**页面背景**上的，默认那支近白 METAL 放到白底上等于没画，
        // 表冠于是变成一个光边圆盘（条带第 24、27 帧）。虚拟机是暗色，所以这条之前测不出来。
        assertTrue(LaunchAnim.toothOnLight(LaunchAnim.luma(1f, 1f, 1f)))
        assertTrue(LaunchAnim.toothOnLight(LaunchAnim.luma(0.92f, 0.95f, 0.98f)))
        assertFalse(LaunchAnim.toothOnLight(LaunchAnim.luma(0.05f, 0.07f, 0.10f)))
        // 中灰底不许被判成亮底（阈值留了余量，不然渐变背景上会突然换色）
        assertFalse(LaunchAnim.toothOnLight(LaunchAnim.luma(0.5f, 0.5f, 0.5f)))
    }

    @Test
    fun `减弱动效时压到极短而不是零时长黑屏`() {
        // "减弱动效"不等于"什么都不画"：直接给终态，再留一次极短淡出，用户才不会看到闪一下空白
        val reduced = LaunchAnim.reducedTotalMs()
        assertTrue("减弱档也要有个收尾：$reduced", reduced in 1L..300L)
        assertEquals(1f, LaunchAnim.reducedCrownAlpha(), 0f)
        assertEquals(1f, LaunchAnim.reducedTickAlpha(7), 0f)
        assertEquals(LaunchAnim.CROWN_SWEEP_DEG, LaunchAnim.reducedCrownRotation(), 0f)
    }
}
