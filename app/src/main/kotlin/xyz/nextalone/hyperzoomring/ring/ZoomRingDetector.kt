package xyz.nextalone.hyperzoomring.ring

class ZoomRingDetector(
    private val speedWindowMs: Long = ZoomRingConstants.SPEED_WINDOW_MS,
    private val speedThreshold: Int = ZoomRingConstants.DEFAULT_SPEED_THRESHOLD,
) {
    private val recentEvents = ArrayDeque<Long>()
    var currentIntensity: Float = 0f; private set
    var isCameraMode: Boolean = false; private set

    fun onEvent(event: ZoomRingEvent): GestureType {
        isCameraMode = event.isCameraMode
        val now = event.timestampMs
        recentEvents.addLast(now)
        while (recentEvents.isNotEmpty() && recentEvents.first() < now - speedWindowMs) {
            recentEvents.removeFirst()
        }
        val count = recentEvents.size
        currentIntensity = (count.toFloat() / (speedThreshold * 2)).coerceIn(0f, 1f)

        val fast = count > speedThreshold
        return when {
            event.isClockwise && fast -> GestureType.CW_FAST
            event.isClockwise -> GestureType.CW_SLOW
            event.isCounterClockwise && fast -> GestureType.CCW_FAST
            event.isCounterClockwise -> GestureType.CCW_SLOW
            // Fallback: no direction info, treat as CW
            fast -> GestureType.CW_FAST
            else -> GestureType.CW_SLOW
        }
    }

    fun reset() {
        recentEvents.clear()
        currentIntensity = 0f
        isCameraMode = false
    }
}
