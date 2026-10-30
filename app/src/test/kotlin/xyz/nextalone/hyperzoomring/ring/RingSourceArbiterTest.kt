package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双入口互斥的判据（HyperOS 3 适配的核心那条）。
 *
 * 背景：`startRuntime()` 无条件起光学传感器监听，而输入事件那条路
 * （`InputInterceptorHook.handleZoomRingEvent`）自己也会 `action.execute()`。
 * 两条路之间原来**没有任何互斥**。HyperOS 4 上看不出来，是因为这台 ROM 把
 * `InputFilterEnabled` 关了、输入侧永远不来事件；**HyperOS 3 上输入侧是能收到环的
 * MotionEvent 的**（上游模块当年就是靠它工作的），于是同一次转动会被执行两遍 ——
 * 滚动双倍速、音量跳两格、离散动作触发两次。
 *
 * 这里用"租约"而不是"按系统版本硬关一条"：我没有 OS3 的 `InputFilterEnabled` 读数，
 * 假设可能是错的。硬关错了 = OS3 上整套功能全灭；租约错了 = 最多多等一个租约周期。
 */
class RingSourceArbiterTest {

    private val lease = RingSourceArbiter.DEFAULT_LEASE_MS

    @Test
    fun `输入侧一个事件都没来过 传感器不许让路`() {
        // 这就是 HyperOS 4 上的常态：lastInputAt 永远是 0，仲裁器必须完全惰性。
        assertFalse(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 0L, leaseMs = lease))
    }

    @Test
    fun `输入侧刚处理过环事件 传感器这一拍让路`() {
        // HyperOS 3 的常态：输入侧每几十毫秒来一次，传感器侧必须整条跳过，否则双倍执行。
        assertTrue(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 9_970L, leaseMs = lease))
    }

    @Test
    fun `输入侧断供超过租约 传感器自动接管`() {
        // 自愈那条：万一输入侧半路死了（ROM 更新、钩子没挂上），不许永久静默。
        assertFalse(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 9_200L, leaseMs = lease))
    }

    @Test
    fun `正好到租约边界算过期`() {
        // 边界钉死：age == lease 算"已经过期"，让路用的是严格小于。
        // 不钉住的话，"700 还是 701"这种问题下次改参数时会没人知道哪个是有意的。
        assertFalse(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 10_000L - lease, leaseMs = lease))
        assertTrue(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 10_000L - lease + 1, leaseMs = lease))
    }

    @Test
    fun `时间戳倒挂时不许让路`() {
        // 输入侧那个戳如果是"未来"的（时钟被改、两个时钟混用过），
        // age 是负数 —— 按"负数小于租约"就会**永久**让路，等于传感器侧被自己关死。
        // 他这台机器今天在动（工程从一个盘迁到另一个盘，系统时间也动过），必须防住。
        assertFalse(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 12_000L, leaseMs = lease))
    }

    @Test
    fun `租约设成 0 就是关掉仲裁`() {
        // A/B 用：`settings put global hzr_ring_lease_ms 0` 要退回"两条路都执行"的老行为，
        // 而不是"谁都不执行"。
        assertFalse(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 9_990L, leaseMs = 0L))
    }

    @Test
    fun `同毫秒内输入侧刚处理过 这一拍还是要让路`() {
        assertTrue(RingSourceArbiter.sensorYields(nowMs = 10_000L, lastInputAtMs = 10_000L, leaseMs = lease))
    }

    @Test
    fun `交错回放里一次转动只执行一遍 且输入侧断供后按租约接管`() {
        // 这就是 OS3 的真实形态：输入侧每 40ms 来一个 MotionEvent，传感器侧每 20ms 一个样本。
        // 断言的是"没有双倍执行"和"接管时刻"，不是某个内部字段。
        val sensorEvery = 20L
        val inputEvery = 40L
        val inputStopsAt = 800L
        val end = 2_000L

        var lastInputAt = 0L
        var nextInputAt = inputEvery
        var sensorRuns = 0
        var inputRuns = 0
        val firstSensorRun = mutableListOf<Long>()

        var now = 0L
        while (now <= end) {
            if (nextInputAt <= inputStopsAt && now >= nextInputAt) {
                inputRuns++
                lastInputAt = now
                nextInputAt += inputEvery
            }
            val yields = RingSourceArbiter.sensorYields(now, lastInputAt, lease)
            if (!yields) {
                sensorRuns++
                firstSensorRun.add(now)
            }
            now += sensorEvery
        }

        // 1) 输入侧活着的那段（0..800ms）里，传感器一次都不许执行 —— 否则就是双倍。
        //    注意 t=0 这一拍：那时输入侧还没来过（lastInputAt=0），仲裁器判"不让路"，
        //    但那一拍本身也没有内容可执行（CrownModel 的累计量还是 0），所以不算重复执行。
        val doubledDuringInput = firstSensorRun.filter { it in 40L..inputStopsAt }
        assertTrue("输入侧活着时传感器抢跑了：$doubledDuringInput", doubledDuringInput.isEmpty())
        // 2) 接管发生在输入侧断供满一个租约之后，第一次是 1500ms（800 + 700 边界）
        assertEquals(listOf(1500L), firstSensorRun.filter { it > inputStopsAt }.take(1))
        // 3) 两条路各干了活，没有一起停摆
        assertTrue(inputRuns > 0);
        assertTrue(sensorRuns > 0)
    }

    // ---- 输入侧那条路的策略（HyperOS 3 上真正决定"谁执行"的是这几条）----

    @Test
    fun `相机放行时输入侧永远把事件还给系统`() {
        // 传感器活着也不改这条：相机里就该让原生变焦吃掉这次转动。
        assertEquals(
            RingSourceArbiter.InputPathPolicy.PASSTHROUGH,
            RingSourceArbiter.inputPolicy(cameraPassthrough = true, nowMs = 10_000L, lastSensorAtMs = 9_990L, leaseMs = lease),
        )
        assertEquals(
            RingSourceArbiter.InputPathPolicy.PASSTHROUGH,
            RingSourceArbiter.inputPolicy(true, 10_000L, 0L, lease),
        )
    }

    @Test
    fun `传感器在租约内时输入侧只消费不执行`() {
        // OS3 的稳态：执行权归我们唯一实测调校过的传感器那条路（累计量、广播契约都是它）；
        // 输入侧仍要把事件吞掉 —— 否则系统自己还会照 AXIS_SCROLL 再滚一遍，又变成双倍。
        assertEquals(
            RingSourceArbiter.InputPathPolicy.CONSUME_ONLY,
            RingSourceArbiter.inputPolicy(false, 10_000L, 9_990L, lease),
        )
    }

    @Test
    fun `传感器断供超过租约 输入侧自己执行`() {
        // 万一 OS3 上那个光学传感器根本不存在/挂了，输入侧必须自己顶上，不许整体失灵。
        assertEquals(
            RingSourceArbiter.InputPathPolicy.EXECUTE,
            RingSourceArbiter.inputPolicy(false, 10_000L, 9_200L, lease),
        )
    }

    @Test
    fun `传感器一个样本都没来过 输入侧自己执行`() {
        assertEquals(
            RingSourceArbiter.InputPathPolicy.EXECUTE,
            RingSourceArbiter.inputPolicy(false, 10_000L, 0L, lease),
        )
    }
}
