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

    /**
     * 这个动作能不能执行。**相机在前台时，一切要往屏幕合成手指的动作都一票否决**，
     * 与 `override_camera` 无关。
     *
     * 为什么必须是硬的（2026-10-31 真机定案）：原来那条放行是"相机在前台**且没开覆盖**才放行"，
     * 于是开着覆盖时滚动照样进相机；日志证据是
     * `执行注入 scroll fg=com.android.camera cam=true 切换后 8015ms` —— 包名认对、前台读到、
     * 也早过了转场窗口，纯粹是这条开关把它放过去了。而一次合成拖拽落在相机界面上，
     * 按到的就是模式按钮/快门旁边那些控件，也就是他报的"转动时误触到其他地方"。
     *
     * `override_camera` 还是要有意义的：它现在只代表"相机里允许非注入类动作"（音量、亮度这些
     * 不碰屏幕的），不再代表"可以往相机界面上扔手指"。
     */
    fun allowsInjectedAction(camera: Boolean, injectsTouch: Boolean): Boolean =
        !(camera && injectsTouch)
}
