package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 表冠分类器：**只认方向**。
 *
 * 2026-11-02 起速度不再参与选槽位（原四槽位 顺/逆 × 慢/快 → 两槽位 PLUS/MINUS）：
 * 转速在判定阈值上下抖动时，相邻样本会落进不同槽位、两个动作交替执行 ——
 * 这就是"音量/滚动概率冲突"的根（见 docs/CROWN-ACTION-V2-DESIGN.md）。
 * 速度只剩 `currentIntensity` 一个出口。
 */
class ZoomRingDetectorTest {
    private lateinit var detector: ZoomRingDetector

    @Before
    fun setUp() {
        detector = ZoomRingDetector(speedWindowMs = 200L, speedThreshold = 5)
    }

    @Test
    fun singleCwEvent_returnsPlus() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 1))
        assertEquals(CrownDir.PLUS, gesture)
    }

    @Test
    fun singleCcwEvent_returnsMinus() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = -1))
        assertEquals(CrownDir.MINUS, gesture)
    }

    @Test
    fun rapidCwEvents_returnsPlus() {
        for (i in 0 until 5) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 30L, value = 6, direction = 1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000 + 150, value = 6, direction = 1))
        assertEquals(CrownDir.PLUS, gesture)
    }

    @Test
    fun rapidCcwEvents_returnsMinus() {
        for (i in 0 until 5) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 30L, value = 6, direction = -1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000 + 150, value = 6, direction = -1))
        assertEquals(CrownDir.MINUS, gesture)
    }

    @Test
    fun noDirection_fallbacksToPlus() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 0))
        assertEquals(CrownDir.PLUS, gesture)
    }

    @Test
    fun eventsSpreadOut_stillPlus() {
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 1))
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1500, value = 6, direction = 1))
        assertEquals(CrownDir.PLUS, gesture)
    }

    @Test
    fun oldEvents_arePruned() {
        for (i in 0 until 10) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 10L, value = 6, direction = 1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1600, value = 6, direction = -1))
        assertEquals(CrownDir.MINUS, gesture)
    }

    /**
     * 不分裂主证：同一方向、转速在阈值上下抖动的长序列，必须**恒**返回同一个槽位。
     *
     * 造样本的方式是"一串密采样 + 一段静默"循环：老分类器（`count > speedThreshold`）在
     * 密采样段给"快"、静默后给"慢"，同方向的两个槽位交替命中 —— 于是同一个物理转动
     * 会轮流执行两个动作。这条断言就是业主报的"概率冲突"的回归锁。
     */
    @Test
    fun speedJitterAroundThreshold_staysInOneSlot() {
        val seen = mutableSetOf<CrownDir>()
        var ts = 1000L
        repeat(6) {
            repeat(4) {
                seen += detector.onEvent(
                    ZoomRingEvent(timestampMs = ts, value = 6, direction = 1)
                )
                ts += 20L
            }
            // 静默到超过 speedWindowMs，窗口里的事件被全部剪掉 → 老实现这里会翻成"慢"
            ts += 250L
            seen += detector.onEvent(ZoomRingEvent(timestampMs = ts, value = 6, direction = 1))
            ts += 20L
        }
        assertEquals("同一方向不该落到多个槽位", setOf(CrownDir.PLUS), seen)
    }

    @Test
    fun intensity_scalesWithEventCount() {
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 1))
        val lowIntensity = detector.currentIntensity
        assert(lowIntensity in 0f..0.5f) { "Expected low intensity, got $lowIntensity" }

        val detector2 = ZoomRingDetector(speedWindowMs = 200L, speedThreshold = 5)
        for (i in 0 until 10) {
            detector2.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 15L, value = 6, direction = 1))
        }
        val highIntensity = detector2.currentIntensity
        assert(highIntensity > 0.5f) { "Expected high intensity, got $highIntensity" }
    }

    @Test
    fun detectsCameraModeSwitch() {
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = -1, direction = 1))
        assert(detector.isCameraMode) { "Should detect camera mode" }
        detector.onEvent(ZoomRingEvent(timestampMs = 2000, value = 6, direction = 1))
        assert(!detector.isCameraMode) { "Should detect default mode" }
    }
}