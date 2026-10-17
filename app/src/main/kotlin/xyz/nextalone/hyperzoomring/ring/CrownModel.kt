package xyz.nextalone.hyperzoomring.ring

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 表冠模型的纯计算部分：输入 (时间, 环位置)，输出"应该滚到哪"。
 *
 * 刻意做成不依赖 Android 的纯类，这样所有手感参数都能在 PC 上用真机录制的
 * 样本回放、用单元测试验证，不必拿手机反复重启试错。
 *
 * 参数含义：
 *  - deadband      : 小于此位置变化视为噪声
 *  - backlash      : 换向所需的最小累计反向回差（固定部分）
 *  - backlashSpeed : 按峰值速度放宽回差的系数（秒），甩得猛时多吞回摆
 *  - backlashCap   : 上面两者之和的上限，防止阈值撑到永远无法换向
 *  - peakDecayPerSec: 峰值速度每秒保留比例，必须按真实时间衰减（不能按样本数）
 *  - unlockVel     : 保留给热调，当前版本不再用它解方向锁（见 sample 里的说明）
 *  - idleResetMs   : 静置超过此时长就清方向锁与速度，并丢弃这一帧的差值
 *  - maxStepUnits  : 单样本位置跳变超过此值判为传感器回零/环绕，丢弃该样本
 *  - reverseMinSamples : 换向至少要连续这么多个反向样本，单帧尖峰不许独自翻方向锁
 */
class CrownModel(
    var deadband: Float = 6f,
    var backlash: Float = 40f,
    var backlashSpeed: Float = 0.03f,
    var backlashCap: Float = 110f,
    var peakDecayPerSec: Float = 0.02f,
    var unlockVel: Float = 250f,
    var idleResetMs: Long = 350L,
    var maxStepUnits: Float = 400f,
    var reverseMinSamples: Int = 2,
    /**
     * 破阈换向时，把回差里攒着的反向位移一次性记上。
     *
     * 机械回差是"空程"，不是"消失"：旧写法在判定阶段把反向样本整条丢掉，翻方向那一刻
     * 又只记当前这一帧，于是他物理转了 700 单位、画面只走其中一截，走多少还取决于
     * 样本恰好怎么切 —— 观感就是"返回那一下有时原地不动、有时幅度更小"。
     * 关掉它（热调 hzr_backlash_credit=0）可以立刻对比出差别。
     */
    var reverseCredit: Boolean = true,
    var velTauMs: Double = 55.0,
) {
    /** 带符号累积目标（单位）。符号即方向，注入端只追它 */
    var targetUnits = 0f; private set
    /** 平滑后的角速度（单位/秒），符号为方向 */
    var velUnitsPerSec = 0f; private set
    /** 近期峰值速度（绝对值） */
    var peakVel = 0f; private set
    /** 当前锁定的运动方向：1 / -1 / 0 */
    var dirSign = 0; private set
    /** 本样本是否被采纳（false = 当噪声或回差吞掉了） */
    var lastAccepted = false; private set
    /** 最近一次被丢弃的跳变量，便于诊断回零问题 */
    var lastDiscardedJump = 0f; private set
    /** 本次被采纳的位移（单位，带符号），未采纳时为 0 */
    var lastDeltaUnits = 0f; private set

    private var lastPos: Float? = null
    private var lastTms = 0L
    private var reverseAccum = 0f
    private var reverseSamples = 0

    /** 喂入一个传感器样本，返回是否被采纳 */
    fun sample(tms: Long, position: Float): Boolean {
        lastAccepted = false
        val prev = lastPos
        val dt = (tms - lastTms).coerceAtLeast(1L)
        lastTms = tms

        if (prev == null) {
            lastPos = position
            return false
        }

        // 关键：停手期间传感器会把位置参考重新归零，起转的第一个样本带进来的是
        // "参考点偏移"而不是真实转动量。若照单累积就会瞬间飞移几百像素（观感=回到原点）。
        // 所以停手后必须重新锚定，并丢弃这一帧的差值。
        val resumed = dt > idleResetMs
        if (resumed) {
            dirSign = 0
            reverseAccum = 0f
            reverseSamples = 0
            velUnitsPerSec = 0f
            peakVel = 0f
            lastPos = position
            return false
        }

        val rawDelta = position - prev
        lastPos = position
        lastDeltaUnits = 0f
        val absRaw = abs(rawDelta)

        // 关键防呆：单样本跳变超过物理可能值 = 传感器回零/环绕，
        // 直接采纳会让目标瞬间飞掉，观感就是"转着转着回到原点"
        if (absRaw > maxStepUnits) {
            lastDiscardedJump = rawDelta
            return false
        }
        if (absRaw < deadband) return false

        val inst = rawDelta / dt * 1000f
        // 按时间常数做 EMA（tau≈55ms），而不是固定系数 —— 固定系数在 40~80ms 不等的
        // 采样间隔下会滞后约 300ms，导致快慢判定过期、模式切换迟钝
        val alpha = 1f - exp(-dt / velTauMs).toFloat()
        velUnitsPerSec = velUnitsPerSec * (1f - alpha) + inst * alpha
        val keep = exp(-peakDecayPerSec * dt / 1000.0).toFloat()
        peakVel = maxOf(abs(velUnitsPerSec), peakVel * keep)

        val sign = rawDelta.sign.toInt()
        // 破阈之后要记的量：默认只记本帧；补记打开时记"这一串反向样本的总和"
        var creditUnits = rawDelta
        if (dirSign != 0 && sign != dirSign) {
            val need = (backlash + peakVel * backlashSpeed).coerceAtMost(backlashCap)
            reverseAccum += absRaw
            reverseSamples++
            // 单帧尖峰不许独自把方向锁翻过去。真机两份日志里 123/204 次换向是"一个样本
            // 就撑够回差"，而其中很多下一帧又回到原方向（来回弹）：照单收下就等于把
            // ±300~400 单位（≈470px）的反向位移真的记进里程表 —— 观感就是"我明明往前拨，
            // 内容却往反方向一蹿"。要求连续两个反向样本，孤立尖峰会在下一帧被
            // reverseAccum=0 整条丢掉；真实换向本来就跨多帧，只慢一个样本（20~40ms）。
            if (reverseAccum < need || reverseSamples < reverseMinSamples) return false
            dirSign = sign
            // 空程不是消失：把判定阶段攒下的反向位移一次性记上，
            // 不然"转了多少"和"走了多少"就对不上，力度会一深一浅
            if (reverseCredit) creditUnits = sign * reverseAccum
            reverseAccum = 0f
            reverseSamples = 0
        } else {
            dirSign = sign
            reverseAccum = 0f
            reverseSamples = 0
        }

        // 注意：不要再在这里加"速度低了顺手解锁方向"之类的语句 —— 上面 resumed 分支
        // 已经保证走到这一行时 dt <= idleResetMs，任何以 dt > idleResetMs 为前提的条件
        // 都是死代码（cfg9 那次就是改在这种地方，所以真机上手感一点没变）。
        targetUnits += creditUnits
        lastDeltaUnits = creditUnits
        lastAccepted = true
        return true
    }

    /** 目标像素（gain = 每单位多少像素） */
    fun targetPx(gain: Float): Float = targetUnits * gain

    /** 外部改过参数后可调用，避免旧状态卡住方向锁 */
    fun resetDynamics() {
        dirSign = 0
        reverseAccum = 0f
        reverseSamples = 0
        velUnitsPerSec = 0f
        peakVel = 0f
    }
}
