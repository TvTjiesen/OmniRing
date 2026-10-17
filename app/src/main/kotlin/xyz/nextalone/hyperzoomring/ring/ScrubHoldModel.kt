package xyz.nextalone.hyperzoomring.ring

import kotlin.math.abs
import kotlin.math.sign

/**
 * 进度拖拽的"按住不放"模型：真手指调进度是落指→拖→看完预览再抬，
 * 我们原来 96ms 就把 DOWN/MOVE/UP 全套打完了，播放器的进度预览自然一闪就没。
 * 这里改成会话式：第一次转动落指，之后只推进偏移，停手 [idleMs] 才抬手 ——
 * 预览能一直挂着，观感就是"转的时候进度条浮着，停一下就自己收"。
 *
 * 两条不变量：
 *  - 落指前必须攒够 [minPressPx]，否则 DOWN/UP 位移不足 touch slop 会被判成点击；
 *  - 偏移只在 ±[maxOffsetPx] 内，反向是往回走而不是瞬移（瞬移=抽搐）。
 *
 * 第三条（2026-10-14 补）：**贴边之后不许"推不动"**。真机日志里他横屏一直转环，
 * 每一把拖拽都被 maxOffsetPx 卡在 +900px（x=2204 连续两次会话一模一样），
 * 按他转环的速度大约 150ms 就顶到边，之后怎么转进度都不走 —— 观感就是
 * "快进到一个地方就不动了"。顶边且还在同向推时本类报 [passBoundary]，
 * 由调用方决定"抬手落定 + 从另一端重新起把"（接力），瞬移的禁忌仍然不破。
 *
 * 抬手时机由注入端的帧循环按时间判定，本类只负责"该去哪、要不要落指"。
 */
class ScrubHoldModel(
    var pxPerUnit: Float = 1.1f,
    /** 相对起点的最大偏移：再远就贴边了，继续推也没意义 */
    var maxOffsetPx: Float = 900f,
    /** 停手这么久就该抬手 */
    var idleMs: Long = 700L,
    /** 不足这么多像素不落指（防误触点击） */
    var minPressPx: Float = 40f,
) {
    var active = false; private set

    /** 当前应处的横向偏移（相对落指点，带符号） */
    var offsetPx = 0f; private set

    var lastMoveAt = 0L; private set

    /** 本帧需要落指（注入端据此先发 DOWN） */
    var needsPress = false; private set

    /**
     * 顶到偏移边界、这一帧却还在朝同一边推：+1 往右推到头，-1 往左推到头，0 = 正常。
     * 调用方据此结束这一把并从另一端重新起把（接力快进）。
     */
    var passBoundary = 0; private set

    private var pending = 0f

    /**
     * @return 本帧手指应该去到的偏移；null = 这帧什么都不做（还没攒够落指量）
     */
    fun onDelta(nowMs: Long, deltaUnits: Float): Float? {
        needsPress = false
        passBoundary = 0
        if (active && nowMs - lastMoveAt > idleMs) release()

        val d = deltaUnits * pxPerUnit
        if (!active) {
            pending += d
            if (abs(pending) < minPressPx) {
                lastMoveAt = nowMs      // 还在转，别被判成停手
                return null
            }
            active = true
            needsPress = true
            // 首次落指也要钳位：猛甩一次可能攒出几千像素，不钳的话反向要先把它花光，
            // 观感就是"这个方向转不动"—— 和注入端的屏幕钳位是两回事，这里得自己守住
            offsetPx = if (pending > maxOffsetPx) maxOffsetPx
                else if (pending < -maxOffsetPx) -maxOffsetPx
                else pending
            pending = 0f
            lastMoveAt = nowMs
            return offsetPx
        }

        var next = offsetPx + d
        if (next > maxOffsetPx) next = maxOffsetPx
        if (next < -maxOffsetPx) next = -maxOffsetPx
        // 这一把装不下的那点余量留给下一把，别让它凭空蒸发（否则每接力一次就丢一帧行程）
        passCarry = d - (next - offsetPx)
        // 已经贴边、这一帧还在朝同一边推 = 这一把到头了。不报的话调用方就没机会起新把，
        // 进度会永远停在边上（他 2026-10-14 报的"快进到一个地方就不动了"）
        if (d != 0f && offsetPx != 0f && abs(offsetPx) >= maxOffsetPx - EDGE_EPS &&
            d.sign == offsetPx.sign
        ) {
            passBoundary = offsetPx.sign.toInt()
        }
        offsetPx = next
        lastMoveAt = nowMs
        return offsetPx
    }

    /** 顶边那一帧装不下的余量，交给下一把的起点 */
    private var passCarry = 0f

    /**
     * 开新的一把：偏移从 [passCarry] 接着长（而不是从 0 重来），[passBoundary] 撤掉。
     * 由调用方在抬手落定之后调用 —— 于是"接力"是把一次长拖切成几段真拖，
     * 不是把手指瞬移回起点（那会像抽搐，也是本类一开始就禁止的事）。
     */
    fun beginPass() {
        pending = 0f
        passBoundary = 0
        offsetPx = passCarry.coerceIn(-maxOffsetPx, maxOffsetPx)
        passCarry = 0f
    }

    /** 注入端每帧按时间判定抬手（它才有帧时钟），本类只提供这个时长 */
    val releaseAfterMs: Long get() = idleMs

    fun release() {
        active = false
        needsPress = false
        passBoundary = 0
        offsetPx = 0f
        pending = 0f
    }

    /** 已经走过的偏移（画预览/日志用） */
    val isDrifting: Boolean get() = active && offsetPx != 0f

    /** 本帧方向，仅诊断用 */
    val lastDir: Int get() = offsetPx.sign.toInt()

    companion object {
        /** 贴边后继续同向转动的容差：到这里就认为"拖到头了" */
        const val EDGE_EPS = 0.5f
    }
}
