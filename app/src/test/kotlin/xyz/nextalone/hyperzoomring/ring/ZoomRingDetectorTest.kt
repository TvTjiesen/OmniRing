package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ZoomRingDetectorTest {
    private lateinit var detector: ZoomRingDetector

    @Before
    fun setUp() {
        detector = ZoomRingDetector(speedWindowMs = 200L, speedThreshold = 5)
    }

    @Test
    fun singleCwEvent_returnsCwSlow() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 1))
        assertEquals(GestureType.CW_SLOW, gesture)
    }

    @Test
    fun singleCcwEvent_returnsCcwSlow() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = -1))
        assertEquals(GestureType.CCW_SLOW, gesture)
    }

    @Test
    fun rapidCwEvents_returnsCwFast() {
        for (i in 0 until 5) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 30L, value = 6, direction = 1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000 + 150, value = 6, direction = 1))
        assertEquals(GestureType.CW_FAST, gesture)
    }

    @Test
    fun rapidCcwEvents_returnsCcwFast() {
        for (i in 0 until 5) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 30L, value = 6, direction = -1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000 + 150, value = 6, direction = -1))
        assertEquals(GestureType.CCW_FAST, gesture)
    }

    @Test
    fun noDirection_fallbacksToCw() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 0))
        assertEquals(GestureType.CW_SLOW, gesture)
    }

    @Test
    fun eventsSpreadOut_returnsSlow() {
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6, direction = 1))
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1500, value = 6, direction = 1))
        assertEquals(GestureType.CW_SLOW, gesture)
    }

    @Test
    fun oldEvents_arePruned() {
        for (i in 0 until 10) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 10L, value = 6, direction = 1))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1600, value = 6, direction = -1))
        assertEquals(GestureType.CCW_SLOW, gesture)
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
