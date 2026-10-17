package xyz.nextalone.hyperzoomring.action

/**
 * 触摸注入的健康闸门 —— 纯计数，不碰 Android，能在 PC 上单测。
 *
 * 为什么要有它（真机踩到的"有概率丢失触控"根因）：旧版这里是一个**终身累计**的
 * `failures`，加到 8 就永久拒绝一切注入，而且永不复位。system_server 能活好几天，
 * 锁屏时注入必然失败、偶尔一帧抛异常，攒够 8 次只是时间问题 —— 表现出来就是
 * "用着用着表冠和触摸一起死，重启作用域才好"。
 *
 * 现在的口径：只数**连续**失败，一次成功就清零；到达上限也只是**冷却**一段时间，
 * 冷却期过后照常工作。冷却触发时调用方必须先把可能压在屏幕上的手指抬起来，
 * 否则系统里留一根永不抬起的幽灵手指，之后的真实触摸全会被当成双指手势。
 */
class InjectionBudget(
    /** 连续失败多少次进入冷却 */
    private val maxConsecutive: Int = 8,
    /** 冷却多久（毫秒）；期间不接受新指令 */
    private val cooldownMs: Long = 5_000L,
) {
    var consecutiveFailures = 0; private set
    var totalFailures = 0; private set
    var totalSuccesses = 0; private set

    private var cooldownUntil = 0L

    /** 冷却是否正在生效（只读判定，不改状态） */
    fun coolingAt(nowMs: Long): Boolean = nowMs < cooldownUntil

    /** 现在能不能接受新的注入指令 */
    fun allow(nowMs: Long): Boolean = !coolingAt(nowMs)

    fun onSuccess(nowMs: Long) {
        totalSuccesses++
        consecutiveFailures = 0
        cooldownUntil = 0L
    }

    /** @return true 表示这次失败刚好把闸门打到冷却，调用方该去抬手了 */
    fun onFailure(nowMs: Long): Boolean {
        totalFailures++
        consecutiveFailures++
        if (consecutiveFailures < maxConsecutive) return false
        consecutiveFailures = 0
        cooldownUntil = nowMs + cooldownMs
        return true
    }
}
