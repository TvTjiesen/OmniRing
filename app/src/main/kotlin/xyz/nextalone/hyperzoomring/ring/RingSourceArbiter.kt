package xyz.nextalone.hyperzoomring.ring

/**
 * 两条环事件入口之间的租约式仲裁（纯函数，PC 上有单测）。
 *
 * 为什么要它：`InputInterceptorHook.startRuntime()` 无条件起光学传感器监听，而输入事件
 * 那条路（`handleZoomRingEvent`）自己也会执行动作。**两条路以前互不知情。**
 * HyperOS 4 上看不出来 —— 这台 ROM 把 `InputFilterEnabled` 关了，输入侧永远收不到环的
 * MotionEvent，实际干活的只有传感器侧。但 **HyperOS 3 上输入侧收得到**（上游模块当年就是
 * 靠它做的），同一次转动会被执行两遍：滚动双倍速、音量跳两格、离散动作触发两次。
 *
 * 为什么用租约而不是"识别到 OS3 就硬关传感器"：我没有 OS3 的 `InputFilterEnabled` 实测读数，
 * 那个假设可能是错的。硬关万一错了 = OS3 整套功能全灭；租约万一错了 = 最多多等一个租约周期，
 * 输入侧一断供，传感器侧就自己接上。判据口径和 [ConfigSync] 的 `stagePaused`/`touchPaused` 一致。
 */
object RingSourceArbiter {

    /**
     * 输入侧（`InputInterceptorHook.handleZoomRingEvent`）这一拍该怎么办。
     *
     * 方向选择：**让传感器那条路做主，输入侧降级成"只把硬件事件吞掉"。**
     * 理由是我手上只有 OS4 的实测数据，而传感器那条路的累计量、广播契约、手感全是在真机上
     * 调校过的；OS3 上 `AXIS_SCROLL` 到底是增量还是累计位置我没法离线判定，所以不敢让它做主。
     * 但输入侧也不能什么都不做 —— 吞掉事件是必须的，否则系统自己照 AXIS_SCROLL 再滚一遍，
     * 又变成"一次转两下"。传感器那条路万一在 OS3 上不存在/挂了，租约一到输入侧立刻自己顶上。
     */
    enum class InputPathPolicy {
        /** 相机在前台且没开覆盖：原样还给系统，让它自己做变焦 */
        PASSTHROUGH,

        /** 传感器路活着：吞掉事件，但不执行动作、不再发广播（避免两个生产者） */
        CONSUME_ONLY,

        /** 传感器路没活着：这条老 AOSP 路自己执行 */
        EXECUTE,
    }

    /**
     * 输入侧的策略。纯函数，PC 上有单测。
     *
     * @param cameraPassthrough 相机在前台且未开启覆盖（`isCameraPackage(fg) && !overrideCamera`）
     * @param nowMs             uptimeMillis
     * @param lastSensorAtMs    传感器路上一次"收到有效样本"的 uptimeMillis，0 = 从没来过
     * @param leaseMs           租约；<=0 = 关掉仲裁
     */
    fun inputPolicy(cameraPassthrough: Boolean, nowMs: Long, lastSensorAtMs: Long, leaseMs: Long): InputPathPolicy =
        when {
            cameraPassthrough -> InputPathPolicy.PASSTHROUGH
            sensorYields(nowMs, lastSensorAtMs, leaseMs) -> InputPathPolicy.CONSUME_ONLY
            else -> InputPathPolicy.EXECUTE
        }

    /**
     * 租约时长。要比"一次转动里输入事件的间隔"（几十毫秒）大得多，又要短到"输入侧死了
     * 之后用户几乎察觉不到空窗"。700ms 是两者之间的取值，可热调：
     * `adb shell settings put global hzr_ring_lease_ms 0`（0 = 关掉仲裁，退回两条路都执行的老行为）
     */
    const val DEFAULT_LEASE_MS = 700L

    /**
     * 传感器这一拍要不要让路。
     *
     * @param nowMs         当前 uptimeMillis（**必须和 lastInputAtMs 同一个时钟**，
     *                       混用 currentTimeMillis 会被改系统时间骗到）
     * @param lastInputAtMs 输入侧上一次真正处理环事件的 uptimeMillis，0 = 从来没处理过
     * @param leaseMs       租约时长；<=0 = 关掉仲裁
     */
    fun sensorYields(nowMs: Long, lastInputAtMs: Long, leaseMs: Long): Boolean {
        if (lastInputAtMs <= 0L) return false        // 输入侧从来没活过 = OS4 常态，不许压制传感器
        val age = nowMs - lastInputAtMs
        // age < 0 也判"过期"：戳比现在还新只可能是时钟被改/两个时钟混用，
        // 那种情况下要是按"负数小于租约"让路，传感器侧会被永久关死。
        return age in 0L until leaseMs
    }
}
