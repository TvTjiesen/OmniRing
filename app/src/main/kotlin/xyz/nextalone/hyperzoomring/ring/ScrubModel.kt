package xyz.nextalone.hyperzoomring.ring

import kotlin.math.abs

/**
 * 进度环的纯计算部分：把"转了多少"翻译成"应该 seek 到哪个绝对位置"。
 *
 * 为什么必须用绝对目标而不是"每次相对跳一点"：播放器在自己也在往前走，
 * 每次拿"当前位置 + 偏移"去 seek 会把播放进度也算进去，观感就是每按一次又往前窜、
 * 来回转时对不上账（抽搐）。表冠的做法是：一开始先拍下基准位置 basePos，
 * 之后所有旋转只推进一个目标偏移，界面（播放器）自己追这个绝对位置。
 *
 * 会话边界：静置 [idleMs] 之后再转就重新拍基准。这样上一次没花完的余量不会
 * 突然作用到新的播放位置上（"转着转着回到原点"的对应物）。
 *
 * 不依赖 Android，参数与行为都能用单元测试验证。
 */
class ScrubModel(
    /** 每单位位置 = 多少毫秒 */
    var msPerUnit: Float = 12f,
    /** 目标至少移动这么多毫秒才值得发一次 seek */
    var minStepMs: Long = 200L,
    /** 两次 seek 的最小间隔，防止把播放器打爆 */
    var throttleMs: Long = 110L,
    /** 静置超过这么久就结束本次会话，下次重新拍基准 */
    var idleMs: Long = 450L,
) {
    var active = false; private set
    var basePosMs = 0L; private set
    var durationMs = 0L; private set

    /** 相对 basePos 的目标偏移（带符号），HUD 显示的就是它 */
    var targetOffsetMs = 0f; private set

    private var lastDeltaAt = 0L
    private var lastEmitAt = 0L
    private var lastEmittedOffset = 0f

    /** 该不该去取新的播放位置（会话开始才需要，省 IPC） */
    fun needsSnapshot(nowMs: Long): Boolean =
        !active || nowMs - lastDeltaAt > idleMs

    /**
     * @param currentPosMs 播放器当前位置；拿不到就传 null
     * @param durationMs 总时长，未知传 0 或负数
     * @return 要 seek 到的绝对位置（毫秒），null 表示这一帧不发
     */
    fun onDelta(
        nowMs: Long,
        deltaUnits: Float,
        currentPosMs: Long?,
        durationMs: Long,
    ): Long? {
        if (currentPosMs == null) return null

        if (needsSnapshot(nowMs)) {
            active = true
            basePosMs = currentPosMs
            this.durationMs = if (durationMs > 0) durationMs else 0L
            targetOffsetMs = 0f
            lastEmittedOffset = 0f
            lastEmitAt = 0L
        }
        lastDeltaAt = nowMs

        targetOffsetMs += deltaUnits * msPerUnit
        // 边界钳位：不能倒播到 0 之前，也不能超过片尾
        val lower = -basePosMs.toFloat()
        val upper =
            if (this.durationMs > 0) (this.durationMs - basePosMs).toFloat() else Float.MAX_VALUE
        if (targetOffsetMs < lower) targetOffsetMs = lower
        if (targetOffsetMs > upper) targetOffsetMs = upper

        if (abs(targetOffsetMs - lastEmittedOffset) < minStepMs) return null
        if (lastEmitAt != 0L && nowMs - lastEmitAt < throttleMs) return null

        lastEmitAt = nowMs
        lastEmittedOffset = targetOffsetMs
        return (basePosMs + targetOffsetMs).toLong().coerceAtLeast(0L)
    }

    /** 会话结束（静置）后调用，让下次重新拍基准 */
    fun endSession() {
        active = false
        targetOffsetMs = 0f
    }

    /** 当前目标绝对位置，HUD 用 */
    fun targetPosMs(): Long = (basePosMs + targetOffsetMs).toLong().coerceAtLeast(0L)

    /** 目标偏移（带符号毫秒），HUD 用 */
    fun offsetMs(): Float = targetOffsetMs
}
