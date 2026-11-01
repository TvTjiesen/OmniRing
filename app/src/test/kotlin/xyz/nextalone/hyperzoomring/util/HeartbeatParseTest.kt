package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 心跳载荷的解析兼容性。
 *
 * 为什么单独钉住：第 8 个字段（输入侧"执行/只消费"两个计数）是这一轮为 HyperOS 3 远程测试加的，
 * 而**设备上可能还留着老版本写的 7 段载荷**（升级完没转环的时候就是这种状态）。
 * 解析要是因为少一个字段就整条判废，首页那张"是否生效"卡会直接变成"读不到心跳"——
 * 一个纯兼容问题会被他看成"模块又挂了"。
 */
class HeartbeatParseTest {

    private val s = Heartbeat.SEPARATOR

    @Test
    fun `七个字段的老载荷照旧能解析`() {
        val snap = Heartbeat.parse("apk|1|2${s}12${s}999${s}CW_SLOW${s}scroll${s}3${s}1")
        assertNotNull(snap)
        assertEquals(12L, snap!!.samples)
        assertEquals(3, snap.ok)
        // 老载荷里没有第八段，按"输入侧从没来过"处理，而不是崩或判废
        assertEquals(0, snap.inputExec)
        assertEquals(0, snap.inputConsumeOnly)
    }

    @Test
    fun `输入侧一次都没来过时判成纯传感器路`() {
        val snap = Heartbeat.parse("x${s}1${s}2${s}g${s}a${s}0${s}0${s}0/0")!!
        assertEquals("sensor", snap.activePath)
    }

    @Test
    fun `第八段读回输入侧执行与只消费的次数`() {
        // OS3 上预期就是这个形状：执行 0 次、只消费 5 次 —— 证明撞车被仲裁器挡住了
        val snap = Heartbeat.parse("x${s}1${s}2${s}g${s}a${s}0${s}0${s}0/5")!!
        assertEquals(0, snap.inputExec)
        assertEquals(5, snap.inputConsumeOnly)
        assertEquals("input+sensor", snap.activePath)
    }

    @Test
    fun `空载荷和残缺载荷都判废而不是猜`() {
        assertNull(Heartbeat.parse(null))
        assertNull(Heartbeat.parse(""))
        assertNull(Heartbeat.parse("x;y;z"))
    }

    @Test
    fun `已加载签名只要没落到键上就必须一直重试`() {
        // 真机抓到的形状：模块加载的是新 APK（心跳里签名已是新的），
        // 但 hzr_loaded_apk 还停在旧值 —— 因为发布只发生在"第一次解析出签名"那一拍，
        // 那一拍的 Settings.Global 写失败了就永远不会再补写。判据 installed==loaded
        // 于是长期停在"待重启"，等于把 #46 说成用户的使用问题。
        val newSig = "base.apk|47646324|1793509028639"
        val oldSig = "base.apk|47121321|1793448036170"
        assertTrue(Heartbeat.shouldPublish(newSig, null))
        assertTrue("和键上现值不一样 → 必须重写", Heartbeat.shouldPublish(newSig, oldSig))
        assertFalse("已经落过了就别每两秒写一次 XML", Heartbeat.shouldPublish(newSig, newSig))
        assertFalse("签名还没解析出来时不许把空串写进去", Heartbeat.shouldPublish("", oldSig))
    }
}
