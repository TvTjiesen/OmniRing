package xyz.nextalone.hyperzoomring.ring

import kotlin.math.abs
import kotlin.math.sign

/**
 * 触摸滚动的纯运动部分：把"应该滚到哪个像素"翻译成"这一帧手指怎么动"。
 *
 * 唯一的状态量是 [appliedPx]（已经真正拖过的像素），每帧只追
 * err = targetPx - appliedPx，因此：
 *  - 位移不会越过目标 → 误差符号不会翻转 → 控制器不会反向追 → 不会来回滚动；
 *  - 手停下后 err 几何收敛，观感是一小段自然收尾，而不是开环惯性；
 *  - 来回转动时 err 立刻变号，下一帧就朝反方向走，不需要等"动画"跑完。
 *
 * 刻意不做的事：不再"甩一把手指然后让控件自己飞"。控件 fling 的距离是开环的，
 * 记账必然失真，来回转动时的"乱跳"就来自这个失真（旧版还得在甩动结束时
 * 把已滚量硬抹成目标，等于自己骗自己）。要滚得远就调增益，1:1 反而更准。
 *
 * 必须守住的不变量（真机踩过坑）：抬手前累计位移 ≥ [minDragPx]，否则 DOWN/UP
 * 之间位移不足 touch slop，会被系统判成点击 —— 手没碰屏幕却打开了列表项。
 * 两条保障：误差不足落指门槛时不落指（继续累积）；万一刚落指目标就反向（来回转
 * 很常见），宁可来回抖二十几像素也不带着零位移抬手。
 *
 * 抽搐的来历（461 条真机样本回放定位，见 ScrollTrackerHoldTest）：旧版**每追平一次
 * 目标就抬手**，而 ease=0.55 三帧就把误差吃干、传感器 20~40ms 才给一个新目标，
 * 于是 34.5 秒里抬手 96 次（每秒 2.8 次"抓-放"）；每次抬手前只有 2 帧（32ms）零速，
 * 压不住控件以最近 ~100ms 算出来的 fling —— 内容被甩出去、下一根手指的 DOWN 又把它
 * 打断，观感就是抖。所以现在把"抬手"变成一件要凑齐条件才做的事：
 *  [holdFrames] 内没有新目标（=真停手）→ [dwellFrames] 帧零速 → 才 UP。
 *
 * 本类不依赖 Android，可以在 PC 上用真机样本回放、用单元测试验证手感。
 */
class ScrollTracker(
    /** 每帧吃掉误差的比例：1=完全直驱，越小收尾越长 */
    var ease: Float = 0.55f,
    /** 单帧最大位移，限制最高速度，也决定撞边频率 */
    var maxStepPx: Float = 170f,
    /** touch slop：不足此位移绝不允许抬手 */
    var minDragPx: Float = 28f,
    /** 手指可拖拽区间（屏幕坐标，y 向下增大） */
    var topPx: Float = 240f,
    var bottomPx: Float = 2040f,
    /**
     * 追平目标之后，手指在原地待命的帧数上限。还在转的时候新目标会不断把它清零，
     * 于是一根手指能用满整条拖拽带；只有真停手才会走到抬手。
     * 按 16ms/帧算 10 帧≈160ms，而样本间隔只有 20~40ms，正常转动绝不会被误判成停手。
     */
    var holdFrames: Int = 10,
    /**
     * 每次 UP 之前必须连续零速的帧数：短于控件 VelocityTracker 的 ~100ms 窗口，
     * fling 就压不掉。只用在"真停手"那一路 —— 撞边换指走 [edgeInstant]，不在那里停。
     * 8 帧≈128ms 有余量，而 hold+dwell≈288ms 仍远低于 500ms 长按阈值。
     */
    var dwellFrames: Int = 8,
    /**
     * 撞边时是否就地抬手、下一帧立刻在另一侧重抓（默认 true）。
     *
     * 这是"卡手"的主开关，真机回放量出来的（461 样本 / 34.5s，"还有货没送出却一动不动"
     * 的帧数）：撞边也压 128ms 零速 = 274 帧，就地换指 = 84 帧，旧版追平即抬手 = 178 帧。
     * 撞边那一刻误差还大着，停手才值得停留；在这里停 = 直接把停顿压在关键路径上。
     * 换指导出的 fling 会被 1~2 帧后的 DOWN 掐掉，代价只是一小段不记账的滚动，
     * 而滚动是相对量，偏移看不出来。
     */
    var edgeInstant: Boolean = true,
) {
    sealed class Op {
        data class Down(val y: Float) : Op()
        data class Move(val y: Float) : Op()
        data class Up(val y: Float) : Op()
    }

    class Step(val ops: List<Op>, val keepGoing: Boolean)

    /** 上游算出来的绝对目标（像素，带符号，相对起始点） */
    var targetPx = 0f

    /** 已经真实拖过的像素，与 targetPx 同一记账口径 */
    var appliedPx = 0f; private set

    var fingerDown = false; private set
    var fingerY = 0f; private set
    var movedSinceDown = 0f; private set

    private var dwellLeft = 0
    private var holdLeft = 0
    private var pressDir = 0f
    private var padTries = 0

    /** 累计抬手次数：抽搐就是这个数字失控，装机后可以用探针直接看 */
    var releases = 0; private set

    /** 本帧是否为了"不被判成点击"而补了位移（会短暂地把误差推大，是故意的） */
    var lastFramePadded = false; private set

    /** 累计补过的位移绝对值，用来量化"防误触代价" */
    var paddedPx = 0f; private set

    /** 还没追平的误差 */
    val errorPx: Float get() = targetPx - appliedPx

    /** 落指门槛：误差够大才值得落指 */
    private val pressPx get() = minDragPx + PRESS_MARGIN_PX

    /** 一帧 */
    fun frame(): Step {
        val ops = ArrayList<Op>(4)
        lastFramePadded = false

        if (dwellLeft > 0) {
            dwellLeft--
            if (fingerDown) {
                if (!draggedEnough()) {
                    padDrag(ops)
                    return Step(ops, true)
                } else {
                    ops += Op.Move(fingerY)
                }
            }
            if (dwellLeft == 0 && fingerDown) {
                // 硬不变量：累计位移不足就绝不抬手。补不动时宁可再等一帧，
                // 也不能带着零位移抬手 —— 那会被判成点击。
                if (!draggedEnough()) {
                    if (padTries < MAX_PAD_TRIES) {
                        padTries++
                        padDrag(ops)
                        return Step(ops, true)
                    }
                    // 兜底：补了这么多帧还是不够（几何退化），只能抬手，
                    // 但至少不会把手指永久留在屏幕上
                }
                padTries = 0
                holdLeft = 0
                releases++
                ops += Op.Up(fingerY)
                fingerDown = false
                pressDir = 0f
            }
            return Step(ops, keep())
        }

        val err = errorPx
        if (abs(err) < EPS_PX) {
            if (!fingerDown) {
                appliedPx = targetPx
                return Step(ops, false)
            }
            // 追平了但手指不离开屏幕：还在转的话，下一两个帧内就有新目标把 holdLeft 清零，
            // 同一根手指接着拖，一次拖拽能用满整条带 —— 旧版就是在这里抬手，
            // 才搞出每秒近三次的"抓-放"和随之而来的 fling 抖动。
            holdLeft++
            if (holdLeft < holdFrames) {
                if (!draggedEnough()) padDrag(ops) else ops += Op.Move(fingerY)
                return Step(ops, true)
            }
            // 到点 = 真的停手了：走零速停留，把控件自己的 fling 压掉之后才 UP
            holdLeft = 0
            dwellLeft = dwellFrames
            return Step(ops, true)
        }

        holdLeft = 0
        val dir = err.sign
        if (!fingerDown) {
            // 差得太小：不落指，等下一次目标推进再说（防点击）。
            // 真实手指也是"拖过 slop 列表才开始动"，所以这个死区不是缺陷。
            if (abs(err) < pressPx) return Step(ops, false)
            if (bottomPx - topPx < pressPx) return Step(ops, false)  // 几何异常，宁可不滚
            fingerY = if (dir > 0) bottomPx else topPx
            fingerDown = true
            pressDir = dir
            movedSinceDown = 0f
            padTries = 0
            ops += Op.Down(fingerY)
            // 落指和移动分到两帧：同一时间戳的 DOWN+MOVE 会被手势管线当成抖动
            return Step(ops, true)
        }

        var step = err * ease
        if (step > maxStepPx) step = maxStepPx
        if (step < -maxStepPx) step = -maxStepPx

        // 撞手指边界的那一帧自动缩短：靠近边上就是减速，也保证不会越界
        val room = if (step > 0) fingerY - topPx else bottomPx - fingerY
        if (room <= 0f) {
            // 贴边了但还没追平：必须换到另一侧重抓。
            if (!draggedEnough() && padTries < MAX_PAD_TRIES) {
                padTries++
                padDrag(ops)
                return Step(ops, true)
            }
            if (edgeInstant) {
                // 就地抬手，下一帧在另一侧立刻落指继续追 —— 这里绝不压零速：
                // 此刻误差还大着，停 128ms 就是他说的"卡手"（详见 edgeInstant 的注释）
                releases++
                ops += Op.Up(fingerY)
                fingerDown = false
                pressDir = 0f
                holdLeft = 0
                return Step(ops, true)
            }
            ops += Op.Move(fingerY)
            dwellLeft = dwellFrames
            return Step(ops, true)
        }
        if (abs(step) > room) step = room * dir

        val newY = (fingerY - step).coerceIn(topPx, bottomPx)
        val actual = fingerY - newY
        fingerY = newY
        appliedPx += actual
        movedSinceDown += abs(actual)
        ops += Op.Move(newY)
        return Step(ops, true)
    }

    /** 外部改了几何参数后，把手指收回可用区间，避免贴边卡死 */
    fun clampFinger() {
        fingerY = fingerY.coerceIn(topPx, bottomPx)
    }

    /**
     * 这次手势作废：事件没能真的进系统时用（比如 DOWN 注入失败）。
     * 只把手指状态收回，不动 [appliedPx]（没注入就等于没滚过，而 err 本来就按
     * appliedPx 算，留着它下一帧会在同一点重新落指）。
     */
    fun abort() {
        fingerDown = false
        dwellLeft = 0
        holdLeft = 0
        pressDir = 0f
        movedSinceDown = 0f
        padTries = 0
    }

    private fun draggedEnough(): Boolean = movedSinceDown >= minDragPx + PAD_MARGIN_PX

    private fun keep(): Boolean =
        dwellLeft > 0 || fingerDown || abs(errorPx) >= pressPx

    /**
     * 补一段真实位移，好让这次手势不会被判成点击。
     * 优先沿落指方向补（就是本来要滚的方向），边上没位置了就反向补，
     * 补完由正常追目标逻辑把多滚的那点收回来 —— 观感只是几十像素的轻微抖动，
     * 比"手没碰屏幕却打开了应用"轻得多。
     */
    private fun padDrag(ops: MutableList<Op>): Boolean {
        val need = minDragPx + PAD_MARGIN_PX - movedSinceDown
        if (need <= 0f) return false
        var dir = pressDir
        var room = if (dir > 0f) fingerY - topPx else bottomPx - fingerY
        if (room <= 0f) {
            dir = -pressDir
            room = if (dir > 0f) fingerY - topPx else bottomPx - fingerY
        }
        if (dir == 0f || room <= 0f) return false
        val m = minOf(need, room)
        val newY = (fingerY - m * dir).coerceIn(topPx, bottomPx)
        val actual = fingerY - newY
        if (actual == 0f) return false
        fingerY = newY
        appliedPx += actual
        movedSinceDown += abs(actual)
        paddedPx += abs(actual)
        lastFramePadded = true
        ops += Op.Move(newY)
        return true
    }

    companion object {
        private const val EPS_PX = 0.5f
        private const val PRESS_MARGIN_PX = 8f
        private const val PAD_MARGIN_PX = 6f

        /** 补位移的重试上限：超过就认为几何退化，放行抬手（宁可误触也不把指针对卡住） */
        const val MAX_PAD_TRIES = 6
    }
}
