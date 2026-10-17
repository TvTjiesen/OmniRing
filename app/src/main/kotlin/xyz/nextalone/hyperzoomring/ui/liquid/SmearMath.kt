package xyz.nextalone.hyperzoomring.ui.liquid

import kotlin.math.abs

/**
 * 底栏形变量的一维纯函数。
 *
 * 从 composable 里抽出来是为了能在 PC 上跑单测：这块直接看截图很难判断
 * "回没回到 0""方向对不对"，而它一旦不回到 0，底栏就会永久歪着。
 */
object SmearMath {

    /** 拖多少像素算"满形变"（1.0）。比 1.5 大 = 同样的拖拽量形变更小 */
    const val DRAG_FULL_PX_FACTOR = 2.6f

    /** 滑块领先量在形变里的权重 */
    const val LEAD_WEIGHT = 0.6f

    /** 合成后的上下限 */
    const val LIMIT = 1.0f

    /** 横向拉长上限：用户反馈 7.5% 太夸张，收到 3% */
    const val MAX_SCALE_X = 0.03f

    /** 纵向压扁上限 */
    const val MAX_SCALE_Y = 0.015f

    /**
     * @param rawDragPx 手指在本把手里累计的横向位移（带符号，松手会被动画拉回 0）
     * @param tabWidthPx 一个 tab 的宽度
     * @param leadUnits 滑块目标位置减当前位置（单位数，带符号）
     * @param tabsCount tab 数量，<=1 时没有"领先"可言
     */
    fun smear(rawDragPx: Float, tabWidthPx: Float, leadUnits: Float, tabsCount: Int): Float {
        // 还没量出宽度 = 这一帧根本没画，直接给 0：省得 lead 项先动一下，
        // 布局完成那一帧看起来"跳形"
        if (tabWidthPx <= 0f) return 0f
        val drag = (rawDragPx / (tabWidthPx * DRAG_FULL_PX_FACTOR)).coerceIn(-1f, 1f)
        val lead = if (tabsCount > 1) {
            (leadUnits / (tabsCount - 1)).coerceIn(-1f, 1f)
        } else {
            0f
        }
        return (drag + lead * LEAD_WEIGHT).coerceIn(-LIMIT, LIMIT)
    }

    /** 横向拉伸系数 */
    fun scaleX(s: Float): Float = 1f + MAX_SCALE_X * abs(s)

    /** 纵向压扁系数 */
    fun scaleY(s: Float): Float = 1f - MAX_SCALE_Y * abs(s)

    /**
     * 支点 x（0 = 左边缘，1 = 右边缘）：放在背对手指的那一侧，
     * 于是被拉长的是"朝手指去的那条边"。s=0 时两边等价，取 0 不影响画面。
     */
    fun pivotX(s: Float): Float = if (s >= 0f) 0f else 1f
}
