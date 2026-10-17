package xyz.nextalone.hyperzoomring.ui

import kotlin.math.abs
import kotlin.math.exp

/**
 * 应用内表冠的缓动数学（纯函数，PC 上可回放验证）。
 *
 * 为什么要有这一层（2026-10-16 他报"在 app 里转动变焦环顿顿的"）：
 * 模块侧的环事件是 **200ms 节流**的，之前收到一拍就直接 `scrollBy` 一次，
 * 于是滚动变成"停 200ms → 窜一下 → 停 200ms → 窜一下"。手指滚动是连续的像素流，
 * 我们的是一秒 5 个台阶，眼睛看的就是顿挫，不是速度。
 *
 * 这里把它改成临界阻尼的一阶跟踪：每帧只吃掉剩余差额的一个比例
 * `1 - exp(-dt/tau)`，不越过目标（没有弹性项 → 不会来回过冲，那正是"抖动"的来源），
 * 所以一拍台阶会被摊成随后若干帧的连续位移；后续拍子到达时只是在同一个 target 上继续累加。
 *
 * `tau` 是时间常数（毫秒）：越小越贴手、越大越滑。默认 45ms ≈ 63% 在三帧内吃完，
 * 200ms 的台阶间隔足够收敛完，同时又绝对连续 —— 想试别的值不用重装：
 * `adb shell settings put global hzr_ring_tau_ms 70`
 */
object RingEase {

    /** 死区（像素）：差额小于它就认定收敛，不再挤半像素的抖动 */
    const val DEAD_PX = 0.6f

    /** 单帧最多按这么长的时间推进：卡帧/后台回来时不许一次跳到目标（那又会变成一窜） */
    const val MAX_DT_MS = 64f
    /** 单帧最大像素，和外面那条 `hzr_scroll_max_step` 同一个默认值 */
    const val MAX_STEP_PX = 170f

    /**
     * 推进一帧。
     *
     * @return Pair(本帧应该滚动的像素, 新的 applied)
     */
    fun step(
        target: Float,
        applied: Float,
        dtMs: Float,
        tauMs: Float,
        maxStepPx: Float = MAX_STEP_PX,
    ): Pair<Float, Float> {
        val diff = target - applied
        if (abs(diff) < DEAD_PX) return 0f to target
        // 零长帧不推进（不能让一帧的 dt=0 变成"一次吃完"）
        if (dtMs <= 0f) return 0f to applied
        // tau<=0 = 缓动关掉：一次到位，等价于旧行为（用来 A/B 对比"顿顿的"是不是缓动能解的）
        if (tauMs <= 0f) return diff to target
        val dt = if (dtMs > MAX_DT_MS) MAX_DT_MS else dtMs
        val tau = if (tauMs < 1f) 1f else tauMs
        val k = 1f - exp(-dt / tau)
        var move = diff * k
        // 单帧封顶：这个 170 不是拍的，是外面那条（第三方 App 里 ScrollAction）的
        // hzr_scroll_max_step 默认值。他要"里面和外面一样"，那最高速度也得一样，
        // 不然一次大累计会在 16ms 里跳掉一整屏（实测过 973px/帧）。
        if (maxStepPx > 0f) {
            if (move > maxStepPx) move = maxStepPx
            if (move < -maxStepPx) move = -maxStepPx
        }
        return move to (applied + move)
    }

    /** 是否已经追平（帧循环靠这个决定什么时候停下来，不空转逐帧） */
    fun converged(target: Float, applied: Float): Boolean = abs(target - applied) < DEAD_PX

    /**
     * 一段环事件回放：第 f 帧开始时收到 events[f]（可为 0），返回每帧位移。
     * 单测用它证明"200ms 的台阶确实被摊平成连续位移"。
     */
    fun replay(events: List<Float>, frameMs: Float, tauMs: Float): List<Float> {
        var target = 0f
        var applied = 0f
        return events.map { e ->
            target += e
            val (move, a) = step(target, applied, frameMs, tauMs)
            applied = a
            move
        }
    }
}

/**
 * 缓动的可变状态：`target` 由环事件累加，`applied` 由界面帧循环写回。
 *
 * 顺带记一组"手感证据"计数（ticks/frames/busy/maxMove），用来在 logcat 里证明
 * 一拍确实被摊成了连续若干帧，而不是"滚了一下然后干等 200ms"。
 * 判据口径：**busy/frames = 有货且在动的帧占比**，旧直驱是 1 帧吃完（占比 ~8%），
 * 缓动后应该在 50% 以上 —— 这就是他眼睛看到的"顿顿的"和"顺滑"的分别。
 */
class RingEaseState {
    var target = 0f
    var applied = 0f
    var ticks = 0
    var demandedPx = 0f

    fun reset() {
        target = 0f
        applied = 0f
        ticks = 0
        demandedPx = 0f
    }
}

/**
 * 一个传感器单位换多少滚动像素。**默认 1.2 就是外面那条（第三方 App 里
 * `ScrollAction` 的 `hzr_scroll_gain`）的默认值** —— 同一个单位、同一个像素增益，
 * 里面和外面转一样多角度就滚一样远。
 *
 * 之前对不上不是这个数的问题：广播那条把 200ms 窗口里除最后一个样本之外的增量全丢了，
 * App 只看到约十分之一的转动量。那个累加补上之后，这里就该用回和外面一样的 1.2。
 * 热调：`adb shell settings put global hzr_ring_px_per_unit 1.2`
 */
private var pxCache = 0f
private var pxAt = 0L

fun ringPxPerUnit(context: android.content.Context): Float {
    val now = System.currentTimeMillis()
    if (now - pxAt > 2000L) {
        pxAt = now
        pxCache = runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, "hzr_ring_px_per_unit", 1.2f)
        }.getOrDefault(1.2f)
    }
    return pxCache
}

/**
 * 缓动时间常数（毫秒），0 = 关掉缓动（退回"一拍滚一次"的旧行为，用来 A/B 对比）。
 * 热调：`adb shell settings put global hzr_ring_tau_ms 70`
 */
private var tauCache = 0f
private var tauAt = 0L

fun ringTauMs(context: android.content.Context): Float {
    val now = System.currentTimeMillis()
    if (now - tauAt > 2000L) {
        tauAt = now
        tauCache = runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, "hzr_ring_tau_ms", 45f)
        }.getOrDefault(45f)
    }
    return tauCache
}

/**
 * 单帧最大位移（像素），默认 170 = 外面那条 `hzr_scroll_max_step` 的同一个默认值。
 * 热调：`adb shell settings put global hzr_ring_max_step 170`
 */
private var stepCache = 0f
private var stepAt = 0L

fun ringMaxStepPx(context: android.content.Context): Float {
    val now = System.currentTimeMillis()
    if (now - stepAt > 2000L) {
        stepAt = now
        stepCache = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, "hzr_ring_max_step", RingEase.MAX_STEP_PX
            )
        }.getOrDefault(RingEase.MAX_STEP_PX)
    }
    return stepCache
}
