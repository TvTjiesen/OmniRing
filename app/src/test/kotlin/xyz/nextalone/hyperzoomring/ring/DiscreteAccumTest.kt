package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离散动作的累计器：累计到阈值才触发一次、两次触发之间有最小间隔、
 * **方向翻转清零**（业主 2026-11-02 批准 —— 累计量是全局一份，顺时针攒一半再回手，
 * 那半份会算到逆时针的动作头上，表现就是"回手时白送一格音量"）。
 *
 * 样本速度的换算是 `|v| / 60`：v=60 时每个样本值 1 个单位，便于手算。
 */
class DiscreteAccumTest {

    @Test
    fun `累计到阈值才触发`() {
        val acc = DiscreteAccum(stepUnits = 120f, minIntervalMs = 0L)
        for (i in 0 until 119) {
            assertFalse("第 ${i + 1} 个样本不该触发", acc.onSample(1, 60f, i * 10L))
        }
        assertTrue(acc.onSample(1, 60f, 1190L))
    }

    @Test
    fun `触发后累计清零，重新攒`() {
        val acc = DiscreteAccum(stepUnits = 120f, minIntervalMs = 0L)
        var fired = 0
        for (i in 0 until 240) {
            if (acc.onSample(1, 60f, i * 10L)) fired++
        }
        assertTrue("240 个样本应触发两次，实际 $fired", fired == 2)
    }

    @Test
    fun `两次触发之间有最小间隔`() {
        val acc = DiscreteAccum(stepUnits = 10f, minIntervalMs = 100L)
        // 一个大速度样本就凑满 10 单位 → t=1000 触发
        assertTrue(acc.onSample(1, 6000f, 1000L))
        // 立刻又凑满，但距上次只有 50ms → 被节流吃掉
        assertFalse(acc.onSample(1, 6000f, 1050L))
        assertTrue(acc.onSample(1, 6000f, 1100L))
    }

    @Test
    fun `方向翻转清零：半份累计不会算到另一头`() {
        val acc = DiscreteAccum(stepUnits = 120f, minIntervalMs = 0L)
        // 顺时针攒了 60 个单位（不够触发）
        for (i in 0 until 60) acc.onSample(1, 60f, i * 10L)
        // 一翻方向立刻清零：这 60 个单位的"存款"作废，重新从 0 攒起
        for (i in 60 until 179) {
            assertFalse("翻转后第 ${i - 59} 个样本不该触发", acc.onSample(-1, 60f, i * 10L))
        }
        assertTrue(acc.onSample(-1, 60f, 1790L))
    }

    @Test
    fun `方向翻转那一刻就清零，不触发`() {
        val acc = DiscreteAccum(stepUnits = 120f, minIntervalMs = 0L)
        for (i in 0 until 119) acc.onSample(1, 60f, i * 10L)
        // 第 120 个单位本该触发，但这一刻方向翻了 → 清零，这一把不执行
        assertFalse(acc.onSample(-1, 60f, 1190L))
    }

    @Test
    fun `速度取绝对值，只有转向决定归谁`() {
        val acc = DiscreteAccum(stepUnits = 120f, minIntervalMs = 0L)
        for (i in 0 until 119) acc.onSample(1, -60f, i * 10L)
        assertTrue("负速度只是反向转的同一个量，也必须累计", acc.onSample(1, -60f, 1190L))
    }
}