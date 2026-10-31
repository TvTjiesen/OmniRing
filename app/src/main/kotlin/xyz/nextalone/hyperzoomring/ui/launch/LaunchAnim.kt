package xyz.nextalone.hyperzoomring.ui.launch

import kotlin.math.pow

/**
 * 冷启动进入动画的时间规格（纯函数）。
 *
 * **这套动画讲的是什么**：本 App 唯一做的事就是"把那圈物理变焦环接到任意动作上"。
 * 所以进入动画的主体不是镜头、不是光圈，而是**我们自己的表冠被手指拨了一下**：
 * 环从略小的尺寸落到 1:1，外圈刻度依次亮起（错峰，读起来像一格一格被拨到），
 * 整圈往一个方向转过一个固定角度（只单向往、末速归零 —— 那一下就是"转环"），
 * 然后中心浮出 App 名，最后整层淡出，露出已经画好的首页。
 *
 * 节奏参照小米17 Ultra 徕卡版相机那一下（从 `guide_optical_zoom_anim.json` 实测出来：
 * 60fps×100 帧=1667ms、元素按 50ms 错峰淡入、先亮后滑），但**主体、颜色、图形全部是我们自己的**，
 * 素材不搬（那是小米/徕卡的资产）。原参照 1667ms，我们压到 900ms，
 * 因为系统开屏退场动画的可用时长上限是 1000ms。
 *
 * 三条纪律直接写进单测，别靠眼睛调：进场量单调不减（不许回头抖）、缩放不许过冲、
 * 旋转只往一个方向。这三条都是我们在这轮之前的真机上栽过的跟头。
 */
object LaunchAnim {

    /** 环身外圈刻度数量（12 格 = 每格 30°，读起来才像一圈齿） */
    const val TICK_COUNT = 12

    /** 第一格开始亮起的时间 */
    const val TICK_START_MS = 60L

    /** 每格自己的淡入时长 */
    const val TICK_FADE_MS = 120L

    /** 相邻两格的错峰量：一起亮=一闪，错峰才有"被依次拨到"的感觉 */
    const val TICK_STAGGER_MS = 26L

    /** 表冠落位（alpha 与 scale 同一个窗口） */
    const val CROWN_IN_START_MS = 0L
    const val CROWN_IN_DUR_MS = 120L

    /** 环被拨动的那一下：单向往、easeOut（起步快、收尾慢 = 手指松手） */
    const val SWEEP_START_MS = 200L
    const val SWEEP_END_MS = 620L
    const val CROWN_SWEEP_DEG = 34f

    /** 中心名字浮出。原来定在 480→760，条带图量出来"名字刚看清就开始整层淡出"，
     *  往前挪并把停留拉开到 200ms（淡出从 840 才开始） */
    const val LABEL_START_MS = 420L
    const val LABEL_END_MS = 640L

    /** 整层淡出，把画面交给已经画好的首页 */
    const val FADE_OUT_START_MS = 840L
    const val FADE_OUT_DUR_MS = 150L

    /** 落位前的起始缩放。0.84 是"能感觉到长大但绝不过冲"的上限 */
    const val CROWN_SCALE_FROM = 0.84f

    /** 减弱动效档的收尾时长：不是零，是"直接给终态 + 一次极短淡出" */
    const val REDUCED_TOTAL_MS = 150L

    fun totalMs(): Long = FADE_OUT_START_MS + FADE_OUT_DUR_MS

    /** easeOutCubic：起步快、收尾慢，最像"被拨完松手"。线性在收尾处仍看得出急停 */
    private fun easeOut(p: Float): Float = 1f - (1f - p).pow(3f)

    /** 某个阶段的归一化进度，越界一律夹到 0..1（调用方不必再判负数） */
    private fun phase(tMs: Long, startMs: Long, durMs: Long): Float =
        ((tMs - startMs).toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

    fun crownAlpha(tMs: Long): Float = easeOut(phase(tMs, CROWN_IN_START_MS, CROWN_IN_DUR_MS))

    fun crownScale(tMs: Long): Float =
        CROWN_SCALE_FROM + (1f - CROWN_SCALE_FROM) * easeOut(phase(tMs, CROWN_IN_START_MS, CROWN_IN_DUR_MS))

    /** 第 [index] 格刻度的透明度（错峰） */
    fun tickAlpha(tMs: Long, index: Int): Float =
        easeOut(phase(tMs, TICK_START_MS + index * TICK_STAGGER_MS, TICK_FADE_MS))

    /** 环被拨过的角度，单调到 [CROWN_SWEEP_DEG] */
    fun crownRotation(tMs: Long): Float =
        CROWN_SWEEP_DEG * easeOut(phase(tMs, SWEEP_START_MS, SWEEP_END_MS - SWEEP_START_MS))

    fun labelAlpha(tMs: Long): Float = easeOut(phase(tMs, LABEL_START_MS, LABEL_END_MS - LABEL_START_MS))

    /** 整层的不透明度：前面恒 1，只在最后一段淡出 */
    fun layerAlpha(tMs: Long): Float =
        if (tMs < FADE_OUT_START_MS) 1f else 1f - easeOut(phase(tMs, FADE_OUT_START_MS, FADE_OUT_DUR_MS))

    // ---- 减弱动效档：直接给终态，只留一次极短淡出（不是"零动效"，那会闪一下空白）----

    fun reducedTotalMs(): Long = REDUCED_TOTAL_MS
    fun reducedCrownAlpha(): Float = 1f
    fun reducedCrownScale(): Float = 1f
    fun reducedTickAlpha(index: Int): Float = 1f
    fun reducedLabelAlpha(): Float = 1f
    fun reducedCrownRotation(): Float = CROWN_SWEEP_DEG
}
