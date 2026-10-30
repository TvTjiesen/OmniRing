package xyz.nextalone.hyperzoomring.ring

/**
 * 前台包名这一环的判据（纯函数，PC 上有单测）。
 *
 * 为什么要它（对应他报的"相机界面有概率触发变焦环的滑动功能"）：相机放行闸门读的是
 * `getRunningTasks(1).topActivity`，而**这台 ROM 在转场过程中"当前前台是谁"是会读空的** ——
 * 历史抓包里就有 `mCurrentFocus=null` 而真实聚焦还在微信的那种行。读空或读慢一拍时
 * `isCameraPackage()` 得到 false，于是照样往屏幕正中注一根手指，落点就在相机界面上。
 *
 * 两条判据：
 *  1. [cacheTtlMs] —— **读失败不许按成功值缓存**。老代码是"先占时间戳再查询"，查询抛异常时
 *     null 会被当成接下来 200ms 内的合法答案复用，正好把最容易读空的转场窗口放大 200 倍。
 *  2. [deferTouchInject] —— **刚换应用的窗口内，要往屏幕注手指的动作先等一拍**。
 *     相机是"刚换应用"最典型的场景，这一拍也正是他看到的"有概率"来源。窗口 600ms，
 *     手感上察觉不到；可热调 `adb shell settings put global hzr_fg_stable_ms 0` 关掉这条。
 */
object ForegroundGate {

    /** 刚换应用后的观察窗（毫秒）。0 = 关掉这条闸门 */
    const val DEFAULT_STABLE_MS = 600L

    /** 读失败时值的有效时长：只为了不"每个样本都打一次 binder"，不缓存成 200ms */
    const val FAIL_TTL_MS = 40L

    /**
     * 换应用时刻怎么记。
     *
     * 读到空**不算**切换：否则一次读失败会把"刚换应用"这个状态一直挂住，
     * 越失败越拦，把功能拦没了。
     */
    fun nextChangedAt(prevPkg: String?, nextPkg: String?, nowMs: Long, lastChangedAt: Long): Long =
        if (nextPkg.isNullOrEmpty() || nextPkg == prevPkg) lastChangedAt else nowMs

    /** 这一拍要不要先把"注手指"类动作拦住。`changedAtMs = 0`（还没观察到过切换）时绝不拦 */
    fun deferTouchInject(nowMs: Long, changedAtMs: Long, windowMs: Long): Boolean =
        changedAtMs > 0L && windowMs > 0L && nowMs - changedAtMs < windowMs

    /** 这次读到的值可以复用多久 */
    fun cacheTtlMs(pkg: String?, okTtlMs: Long, failTtlMs: Long = FAIL_TTL_MS): Long =
        if (pkg.isNullOrEmpty()) failTtlMs else okTtlMs
}
