package xyz.nextalone.hyperzoomring.ring

import kotlin.math.abs

/**
 * 离散动作（音量/亮度/上下曲…）的触发累计器。
 *
 * 从 `hook/SensorRingDriver` 里抽出来的唯一理由是**可测**：那个 `object` 的状态是进程级私有，
 * 单测够不着，而"方向翻转清零"这条规则是业主 2026-11-02 亲自拍的，必须有断言钉住。
 * 逻辑逐字搬运，没有行为改动（见 docs/CROWN-ACTION-V2-DESIGN.md）。
 *
 * 触发条件（三者全过才执行一次）：累计转幅 ≥ [stepUnits]；距上次执行 ≥ [minIntervalMs]。
 * 注意节流判定发生在清零**之后** —— 被节流吃掉的那一次不会把累计量留下，这是原实现的语义，
 * 一并照搬，不顺手改。
 */
internal class DiscreteAccum(
    private val stepUnits: Float = DEFAULT_STEP_UNITS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {

    private var accum = 0f
    private var lastAt = 0L
    private var dirSign = 0

    /**
     * 吃一个样本，返回"这一把该不该执行动作"。
     *
     * 方向一翻就把累计清零：累计量是全局一份，顺时针攒了一半再回手，那半份会算到逆时针的
     * 动作头上 —— 表现就是"回手时白送一格音量"（业主 2026-11-02 批准清零）。
     * 速度只取绝对值：转多快只决定幅度，归谁执行由转向决定。
     */
    fun onSample(dirSign: Int, velUnitsPerSec: Float, tms: Long): Boolean {
        if (dirSign != this.dirSign) {
            this.dirSign = dirSign
            accum = 0f
        }
        accum += abs(velUnitsPerSec) / 60f
        if (accum < stepUnits) return false
        accum = 0f
        if (tms - lastAt < minIntervalMs) return false
        lastAt = tms
        return true
    }

    companion object {
        /** 每累计多少位置单位触发一次（原 SensorRingDriver.DISCRETE_STEP_UNITS） */
        const val DEFAULT_STEP_UNITS = 120f

        /** 两次执行之间的最小间隔 */
        const val DEFAULT_MIN_INTERVAL_MS = 60L
    }
}