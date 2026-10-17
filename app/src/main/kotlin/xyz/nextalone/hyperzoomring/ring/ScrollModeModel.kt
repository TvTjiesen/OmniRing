package xyz.nextalone.hyperzoomring.ring

/**
 * 滚动类动作的「里程表」：把传感器被采纳的转幅累加成注入端要追的绝对目标像素。
 *
 * 只有一个口径：**带符号累加**，所以方向就是表冠的实际转向 —— 顺时针往下滚、
 * 逆时针往上滚，来回转就来回滚。以前这里还分出一条"定方向"的绝对值里程表
 * （scroll_up / scroll_down），用户反馈它和表冠本身的转向语义冲突、清单又冗杂，
 * 按他的原话删掉了；残留的旧映射 id 由 ActionRouter 兜到同一条通道，不会失灵。
 *
 * 纯计算、无 Android 依赖，能在 PC 上单测（见 ScrollModeModelTest）。
 */
class ScrollModeModel {

    /** 带符号累积转幅 */
    var signedUnits = 0f; private set

    /** 喂入一个被采纳的位移样本（带符号） */
    fun onAccepted(deltaUnits: Float) {
        signedUnits += deltaUnits
    }

    /**
     * 注入端要追的绝对目标（像素，带符号：正=内容上移，即"向下滚"）。
     */
    fun targetPx(actionId: String, gainPxPerUnit: Float): Float =
        signedUnits * gainPxPerUnit

    companion object {
        const val SCROLL_CROWN = "scroll"
    }
}
