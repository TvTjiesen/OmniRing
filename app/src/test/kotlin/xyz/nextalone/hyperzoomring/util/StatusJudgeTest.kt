package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是否生效」判定的验收。
 *
 * 用户特别要求"一定要核实是否准确还有是否生效"——判定错了比不判定更糟：
 * 会让人去 LSPosed 里翻半天，而真正的原因只是没重启作用域。
 * 所以五种状态各钉一条，顺序（优先级）也钉一条。
 */
class StatusJudgeTest {

    private val sig = "base.apk|1234567|1700000000000"

    private fun hb(
        signature: String = sig,
        samples: Long = 50,
        ok: Int = 10,
        fail: Int = 0,
    ) = Heartbeat.Snapshot(signature, samples, 1_000_000L, "CW_SLOW", "scroll", ok, fail)

    @Test
    fun `没有心跳说明根本没进系统框架`() {
        assertEquals(
            StatusJudge.Verdict.NOT_LOADED,
            StatusJudge.judge(null, sig, enabled = true).verdict,
        )
    }

    @Test
    fun `签名不一致优先判定为待重启`() {
        val r = StatusJudge.judge(hb(signature = "old.apk|1|1"), sig, enabled = true)
        assertEquals(StatusJudge.Verdict.RELOAD_PENDING, r.verdict)
    }

    @Test
    fun `拿不到已安装签名时不能冤枉人说不一致`() {
        // 读不到 installedSignature（比如 pm 异常）时，应该继续往下判，而不是报"要重启"
        assertEquals(
            StatusJudge.Verdict.OK,
            StatusJudge.judge(hb(), null, enabled = true).verdict,
        )
        assertEquals(
            StatusJudge.Verdict.OK,
            StatusJudge.judge(hb(), "", enabled = true).verdict,
        )
    }

    @Test
    fun `代码在跑但总开关关着`() {
        assertEquals(
            StatusJudge.Verdict.DISABLED,
            StatusJudge.judge(hb(), sig, enabled = false).verdict,
        )
    }

    @Test
    fun `开关开着但环一个样本都没来过`() {
        val r = StatusJudge.judge(hb(samples = 0), sig, enabled = true)
        assertEquals(StatusJudge.Verdict.NO_SAMPLES, r.verdict)
    }

    @Test
    fun `有样本但每次都失败`() {
        val r = StatusJudge.judge(hb(ok = 0, fail = 7), sig, enabled = true)
        assertEquals(StatusJudge.Verdict.ALL_FAILED, r.verdict)
    }

    @Test
    fun `有成功就有救，失败计数只当参考`() {
        assertEquals(
            StatusJudge.Verdict.OK,
            StatusJudge.judge(hb(ok = 3, fail = 9), sig, enabled = true).verdict,
        )
    }

    @Test
    fun `判定顺序从底层往上`() {
        // 既没重启、开关又关着：应该先说"要重启"（更底层的原因）
        val r = StatusJudge.judge(hb(signature = "other", samples = 0), sig, enabled = false)
        assertEquals(StatusJudge.Verdict.RELOAD_PENDING, r.verdict)
    }

    @Test
    fun `年龄计算不会出现负数`() {
        assertEquals(3L, StatusJudge.ageSeconds(7_000L, 10_000L))
        assertEquals(0L, StatusJudge.ageSeconds(20_000L, 10_000L))
    }

    @Test
    fun `判定抖动被迟滞压住`() {
        // 一次读到 NOT_LOADED、一次读到 OK 来回翻：显示必须停在第一个稳定值上不乱跳
        var f = StatusJudge.FilterState()
        f = StatusJudge.step(f, StatusJudge.Verdict.OK)
        assertEquals(StatusJudge.Verdict.OK, f.shown)
        f = StatusJudge.step(f, StatusJudge.Verdict.NOT_LOADED)
        assertEquals("单次抖动不能改口", StatusJudge.Verdict.OK, f.shown)
        f = StatusJudge.step(f, StatusJudge.Verdict.OK)
        assertEquals("回到原值则候选清零", StatusJudge.Verdict.OK, f.shown)
        // 连续两次都读到新状态，才允许改口
        f = StatusJudge.step(f, StatusJudge.Verdict.NOT_LOADED)
        f = StatusJudge.step(f, StatusJudge.Verdict.NOT_LOADED)
        assertEquals(StatusJudge.Verdict.NOT_LOADED, f.shown)
    }

    @Test
    fun `心跳载荷解析容错`() {
        val raw = "$sig;42;1234;CW_FAST;scroll;5;1"
        val s = Heartbeat.parse(raw)!!
        assertEquals(sig, s.signature)
        assertEquals(42L, s.samples)
        assertEquals("scroll", s.lastAction)
        assertEquals(5, s.ok)
        assertEquals(1, s.fail)

        assertNull(Heartbeat.parse(null))
        assertNull(Heartbeat.parse(""))
        assertNull(Heartbeat.parse("只有一坨"))
        // 签名里带 | 也不能把解析带偏（回归：曾经用 | 当分隔符，整条错位）
        assertEquals(sig, Heartbeat.parse("$sig;7;8;CW_SLOW;volume_up;1;0")!!.signature)
        assertNull(Heartbeat.parse("$sig;不是数字;1;a;b;2;3"))
    }

    @Test
    fun `配置同步状态能读出来`() {
        val s = StatusJudge.parseCfgState("3;17;1791809197963")!!
        assertEquals(3, s.pushed)
        assertEquals(17, s.keys)
        assertEquals(1791809197963L, s.at)
        // 一次都没推过：界面要能据此说"改设置还没送到系统里"
        assertEquals(0, StatusJudge.parseCfgState("0;0;0")!!.pushed)
    }

    @Test
    fun `配置同步状态读不出来时返回 null 而不是猜`() {
        assertNull(StatusJudge.parseCfgState(null))
        assertNull(StatusJudge.parseCfgState(""))
        assertNull(StatusJudge.parseCfgState("乱码"))
        assertNull(StatusJudge.parseCfgState("a;b"))
        // 只有一段也算坏：宁可不说这行，也不要说错
        assertNull(StatusJudge.parseCfgState("3"))
    }

    @Test
    fun `认得出这是不是实测机型`() {
        // 实测机的 Build.MODEL 是型号代码，不是市场名，别拿市场名去比
        assertTrue(StatusJudge.isSupportedModel("25128PNA1C"))
        assertTrue(StatusJudge.isSupportedModel("Xiaomi 17 Ultra by Leica"))
        assertFalse(StatusJudge.isSupportedModel("23127PN0CC"))
        assertFalse(StatusJudge.isSupportedModel(""))
    }
}
