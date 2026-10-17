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
    ) = Compatibility.grade(
        supportedDevice = supported,
        modelText = "XIAOMI Xiaomi 17 Ultra by Leica · nezha",
        osText = "HyperOS 4.0.0.22 · Android 17（SDK $sdk）",
        sdk = sdk,
        verdict = verdict,
        ringSamples = samples,
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
    fun `系统低于实测范围 = 部分适配`() {
        val r = grade(sdk = 34)
        assertEquals(Compatibility.Level.PARTIAL, r.level)
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
