package xyz.nextalone.hyperzoomring.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入闸门的规约 —— 对应他报的"有概率丢失触控，特别是设置页面，不管是表冠还是触摸有概率死"。
 *
 * 旧实现是终身累计 failures，加到 8 就**永久**拒绝注入且永不复位：system_server 能活好几天，
 * 锁屏时注入必然失败、偶尔一帧抛异常，攒够 8 次只是时间问题，表现出来就是"用着用着全死"。
 * 这三条就是防止再退回那个写法的钉子。
 */
class InjectionBudgetTest {

    private val max = 8
    private val cooldown = 5_000L

    @Test
    fun `连续失败到达上限才冷却`() {
        val b = InjectionBudget(max, cooldown)
        for (i in 1 until max) {
            assertTrue("第 $i 次失败不该冷却", b.allow(i * 100L))
            assertFalse(b.onFailure(i * 100L))
        }
        assertTrue("第 $max 次失败必须触发冷却", b.onFailure(max * 100L))
        assertFalse(b.allow(max * 100L))
    }

    @Test
    fun `冷却期一过就恢复，不会永久停用`() {
        val b = InjectionBudget(max, cooldown)
        repeat(max) { b.onFailure(1_000L) }
        assertFalse(b.allow(1_000L))
        assertFalse("冷却还没到", b.allow(1_000L + cooldown - 1))
        assertTrue("冷却结束必须重新可用", b.allow(1_000L + cooldown))
        // 恢复之后又能在冷却期满前正常计数
        repeat(max - 1) { assertFalse(b.onFailure(6_000L)) }
        assertTrue(b.allow(6_000L))
    }

    @Test
    fun `一次成功就把连续计数清零`() {
        val b = InjectionBudget(max, cooldown)
        repeat(max - 1) { b.onFailure(1L) }
        b.onSuccess(2L)
        assertEquals(0, b.consecutiveFailures)
        repeat(max - 1) { assertFalse("清零后不该再触发冷却", b.onFailure(3L)) }
        assertFalse(b.coolingAt(3L))
        assertTrue(b.onFailure(4L))
    }

    @Test
    fun `总数只用于诊断，不参与判定`() {
        val b = InjectionBudget(max, cooldown)
        repeat(3) { b.onSuccess(1L) }
        repeat(2) { b.onFailure(1L) }
        assertEquals(3, b.totalSuccesses)
        assertEquals(2, b.totalFailures)
        assertEquals(2, b.consecutiveFailures)
        assertTrue(b.allow(1L))
    }
}
