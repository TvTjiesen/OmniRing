package xyz.nextalone.hyperzoomring.ring

data class ZoomRingEvent(
    val timestampMs: Long,
    val value: Int,
    /** Rotation direction: positive = clockwise, negative = counter-clockwise, 0 = unknown. */
    val direction: Int = 0,
) {
    val isCameraMode: Boolean get() = value == ZoomRingConstants.TICK_VALUE_CAMERA
    val isClockwise: Boolean get() = direction > 0
    val isCounterClockwise: Boolean get() = direction < 0
}
