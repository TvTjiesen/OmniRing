package xyz.nextalone.hyperzoomring.ring

class ZoomRingDetector(
    private val speedWindowMs: Long = ZoomRingConstants.SPEED_WINDOW_MS,
    private val speedThreshold: Int = ZoomRingConstants.DEFAULT_SPEED_THRESHOLD,
) {
    private val recentEvents = ArrayDeque<Long>()
    var currentIntensity: Float = 0f; private set
    var isCameraMode: Boolean = false; private set

    /**
     * 这次样本算哪个转向。
     *
     * 只认方向：`fast`（窗口样本数 > `speedThreshold`）**不再参与选槽位**。它在阈值上下抖动时
     * 会让相邻样本落进不同槽位、两个动作交替执行 —— 业主报的"音量/滚动概率冲突、像被抢"
     * 就是这个（业主 2026-11-01 拍板：转速只影响幅度）。转速如今只剩 `currentIntensity`
     * 这一个出口，公式一字未动。见 docs/CROWN-ACTION-V2-DESIGN.md。
     */
    fun onEvent(event: ZoomRingEvent): CrownDir {
        isCameraMode = event.isCameraMode
        val now = event.timestampMs
        recentEvents.addLast(now)
        while (recentEvents.isNotEmpty() && recentEvents.first() < now - speedWindowMs) {
            recentEvents.removeFirst()
        }
        val count = recentEvents.size
        currentIntensity = (count.toFloat() / (speedThreshold * 2)).coerceIn(0f, 1f)

        return when {
            event.isClockwise -> CrownDir.PLUS
            event.isCounterClockwise -> CrownDir.MINUS
            // Fallback: no direction info, treat as clockwise（与原 dirSign==0 按 PLUS 的语义一致）
            else -> CrownDir.PLUS
        }
    }

    fun reset() {
        recentEvents.clear()
        currentIntensity = 0f
        isCameraMode = false
    }
}
