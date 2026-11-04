package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「适配程度」的判定单测 —— 这张卡是给用户判断"我这台能不能用、还差哪一步"的，
 * 分级判错比界面画错更糟，所以每条都钉住。
 */
class CompatibilityTest {

    private fun grade(
        supported: Boolean = true,
        sdk: Int = 37,
        verdict: StatusJudge.Verdict? = StatusJudge.Verdict.OK,
        samples: Long = 120L,
        activePath: String = "sensor",
    ) = Compatibility.grade(
        supportedDevice = supported,
        modelText = "XIAOMI Xiaomi 17 Ultra by Leica · nezha",
        osText = "HyperOS 4.0.0.22 · Android 17（SDK $sdk）",
        sdk = sdk,
        verdict = verdict,
        ringSamples = samples,
        activePath = activePath,
    )

    @Test
    fun `实测机型 + 新系统 + 已加载 + 有样本 = 完整适配`() {
        val r = grade()
        assertEquals(Compatibility.Level.FULL, r.level)
        assertEquals(3, r.passed)
        assertEquals(3, r.total)
    }

    @Test
    fun `分数只数硬项，技术路径那条不计分`() {
        val r = grade()
        assertTrue(r.items.any { it.check == Compatibility.Check.PATH })
        assertEquals(
            "PATH 不许进分母",
            3,
            r.total,
        )
    }

    @Test
    fun `机型不对就是未适配，不管其他项多好`() {
        val r = grade(supported = false)
        assertEquals(Compatibility.Level.UNSUPPORTED, r.level)
    }

    @Test
    fun `模块没加载 = 还差一步，不是未适配`() {
        val r = grade(verdict = StatusJudge.Verdict.NOT_LOADED)
        assertEquals(Compatibility.Level.SETUP, r.level)
        assertEquals(Compatibility.State.FAIL, r.items.first { it.check == Compatibility.Check.LOADED }.state)
    }

    @Test
    fun `装完没重启作用域也算差一步`() {
        val r = grade(verdict = StatusJudge.Verdict.RELOAD_PENDING)
        assertEquals(Compatibility.Level.SETUP, r.level)
    }

    @Test
    fun `系统低于实测范围 = 未支持（不是还差一步）`() {
        // 这一档必须和 SETUP 分开：SETUP 是"你去重启一下就好"，PARTIAL 是"这台系统我们不保"。
        // 混在一起会让 OS3 用户以为修一修就能用。
        val r = grade(sdk = 34)
        assertEquals(Compatibility.Level.PARTIAL, r.level)
    }

    @Test
    fun `Android 16（HyperOS 3）没实测过 不许报成通过`() {
        // 上一版把 TESTED_MIN_SDK 写成 36，等于对外宣称"Android 16 我们跑过"，
        // 而手上唯一一台实测机是 OS4 / Android 17（SDK 37）。虚报的代价很具体 ——
        // 酷安上会有人装了之后回来说"你写着支持结果不行"。
        //
        // 2026-11-04 业主定案：**不再适配 OS3**，源码公开、要用的自己改。
        // 所以这条断言从"等实测后抬回 36"变成终态：只要没实测证据，就一直是 PARTIAL。
        val r = grade(sdk = 36)
        assertEquals(Compatibility.Level.PARTIAL, r.level)
        assertEquals(Compatibility.State.WARN, r.items.first { it.check == Compatibility.Check.OS }.state)
    }

    @Test
    fun `技术路径那行说真话`() {
        // 这一行原来是写死的 "sensor"。HyperOS 3 上输入侧其实也在收事件，
        // 写死就等于把"走的哪条路"变成一句废话 —— 而这恰恰是远程测试最想知道的一行。
        assertEquals("sensor", grade().items.first { it.check == Compatibility.Check.PATH }.value)
        assertEquals(
            "input+sensor",
            grade(sdk = 36, activePath = "input+sensor").items.first { it.check == Compatibility.Check.PATH }.value,
        )
        // 路径永远只是说明，不进分母
        assertEquals(3, grade(activePath = "input+sensor").total)
    }

    @Test
    fun `在跑但一个环样本都没进来 = 差一步（这是真故障信号）`() {
        val r = grade(samples = 0L)
        assertEquals(Compatibility.Level.SETUP, r.level)
        assertEquals(Compatibility.State.WARN, r.items.first { it.check == Compatibility.Check.RING }.state)
    }

    @Test
    fun `心跳读不到时不许崩，按未知处理`() {
        val r = grade(verdict = null)
        assertEquals(Compatibility.Level.SETUP, r.level)
        assertEquals(Compatibility.State.WARN, r.items.first { it.check == Compatibility.Check.LOADED }.state)
    }
}
