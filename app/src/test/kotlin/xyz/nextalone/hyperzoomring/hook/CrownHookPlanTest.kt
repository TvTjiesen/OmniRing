package xyz.nextalone.hyperzoomring.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 102 入口自己挑钩点，所以"挑对没有"必须能在电脑上判死 —— 装错了是 system_server 的
 * 输入链路出事，不是他手机上多看一行日志能发现的。
 *
 * 这里不引用任何 Android 类：[MethodSig] 故意只带简单名，正是为了让这套判定能脱离 framework
 * 跑单测（旧路径靠 YukiHook 的 DSL 编译期就把签名钉死了，换成手写反射后那层保障没了）。
 */
class CrownHookPlanTest {

    private fun sig(
        owner: String,
        name: String,
        vararg params: String,
        ret: String = "void",
    ) = MethodSig(owner, name, params.toList(), ret)

    private val ims = "com.android.server.input.InputManagerService"
    private val shortcut = "com.miui.server.input.util.ShortCutActionsUtils"

    @Test
    fun `filterInputEvent 只认那两个参数，别的重载一律不钩`() {
        val want = sig(ims, "filterInputEvent", "InputEvent", "int", ret = "boolean")
        assertTrue(HookTarget.FILTER_INPUT.matches(want))
        // 参数个数/类型/顺序错了都不能钩：钩上去会把不该吞的事件吞掉
        assertFalse(HookTarget.FILTER_INPUT.matches(sig(ims, "filterInputEvent", "InputEvent", "long")))
        assertFalse(HookTarget.FILTER_INPUT.matches(sig(ims, "filterInputEvent", "InputEvent")))
        assertFalse(HookTarget.FILTER_INPUT.matches(sig(ims, "filterInputEvent", "int", "InputEvent")))
        // 同名但别家的类也不能撞上
        assertFalse(HookTarget.FILTER_INPUT.matches(sig("com.other.InputManagerService", "filterInputEvent", "InputEvent", "int")))
    }

    @Test
    fun `injectInputEvent 同名全钩，拦截只观察`() {
        val a = sig(ims, "injectInputEvent", "InputEvent", "int", ret = "boolean")
        val b = sig(ims, "injectInputEvent", "InputEvent", "String", ret = "void")
        assertTrue(HookTarget.INJECT_INPUT.matches(a))
        assertTrue(HookTarget.INJECT_INPUT.matches(b))
        assertFalse(HookTarget.INJECT_INPUT.matches(sig(ims, "injectInputEventUnchecked", "InputEvent", "int")))
        assertTrue(HookTarget.INTERCEPT_KEY.matches(sig(ims, "interceptKeyBeforeQueueing", "KeyEvent", "int", ret = "int")))
    }

    @Test
    fun `吞掉调用时返回值的类型必须对得上，对不上就不吞`() {
        val filter = HookTarget.FILTER_INPUT
        val booleanSig = sig(ims, "filterInputEvent", "InputEvent", "int", ret = "boolean")
        // 旧路径这里是 resultFalse()
        assertEquals(CancelValue.Of(false), filter.cancelFor(booleanSig, truthy = false))
        // launchCamera 旧路径是 resultTrue()
        val cam = sig(shortcut, "launchCamera", "Context", "int", ret = "boolean")
        assertEquals(CancelValue.Of(true), HookTarget.LAUNCH_CAMERA.cancelFor(cam, truthy = true))
        // void：不 proceed 就等于吞掉，不能塞值
        assertEquals(CancelValue.None, HookTarget.INJECT_INPUT.cancelFor(sig(ims, "injectInputEvent", "InputEvent", "int"), truthy = false))
        // 返回 int 的方法：我们造不出"合法结果"，必须选 Unsafe（宁可不吞），否则会往框架里塞错类型
        assertEquals(CancelValue.Unsafe, HookTarget.LAUNCH_CAMERA.cancelFor(sig(shortcut, "launchCamera", "int", ret = "int"), truthy = true))
        // 只观察的那条永远不吞
        assertEquals(CancelValue.Unsafe, HookTarget.INTERCEPT_KEY.cancelFor(booleanSig, truthy = true))
    }

    @Test
    fun `方法指纹从反射对象取，简单名不带包前缀`() {
        val m = Fixture::class.java.getDeclaredMethod("probe", String::class.java, IntArray::class.java)
        val s = MethodSig.of("xyz.nextalone.hyperzoomring.hook.Fixture", m)
        assertEquals("probe", s.name)
        // 数组的 simpleName 是 "int[]"（JVM 给的），不是 Kotlin 的 "IntArray"：
        // 匹配规则只用得上类名和 int，这里钉住这个差异，别让它下次悄悄变回去
        assertEquals(listOf("String", "int[]"), s.paramTypes)
        assertEquals("boolean", s.returnType)
    }

    @Test
    fun `诊断串在找不到钩点时也讲清楚是哪一个目标`() {
        val empty = HookTarget.describe(HookTarget.LAUNCH_CAMERA, emptyList())
        assertTrue(empty, empty.contains("LAUNCH_CAMERA=没找到") || empty.endsWith("没找到"))
        val one = HookTarget.describe(HookTarget.FILTER_INPUT, listOf(sig(ims, "filterInputEvent", "InputEvent", "int", ret = "boolean")))
        assertTrue(one, one.contains("filterInputEvent(InputEvent,int)=boolean"))
    }

    @Suppress("unused")
    class Fixture {
        fun probe(s: String, a: IntArray): Boolean = s.isNotEmpty() && a.isNotEmpty()
    }
}
