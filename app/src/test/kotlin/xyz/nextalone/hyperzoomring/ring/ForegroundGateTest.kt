package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台包名这一环的判据（相机误触发那条 bug 的根因候选都在这里）。
 *
 * 实测到的前提（历史抓包里翻出来的，不是我编的）：这台 ROM 的窗口状态在切换过程中会
 * 出现 `mCurrentFocus=null`，也就是说**转场那段时间"当前前台是谁"是可能读空的**；
 * 而我们相机放行闸门读的正是 `getRunningTasks(1).topActivity`。读空或者读慢一拍，
 * `isCameraPackage()` 就是 false → 照样往屏幕正中注一根手指 → 他在相机里看到的
 * "有概率触发滑动"。所以这里钉两条：
 *
 *  1. **读失败不许当成有效值缓存**。原来 `fgAt` 是先占时间戳再查询，查询抛异常时
 *     null 会被当成"这 200ms 内的合法答案"复用 —— 正好把最容易读空的转场窗口放大成 200ms。
 *  2. **刚换应用的窗口内，要往屏幕注手指的动作先等一拍**。窗口取一个手感上察觉不到的值。
 */
class ForegroundGateTest {

    @Test
    fun `读到的前台包名没变时 不算发生切换`() {
        assertEquals(
            5_000L,
            ForegroundGate.nextChangedAt(prevPkg = "com.android.camera", nextPkg = "com.android.camera", nowMs = 9_000L, lastChangedAt = 5_000L),
        )
    }

    @Test
    fun `包名真的换了才记切换时刻`() {
        assertEquals(
            9_000L,
            ForegroundGate.nextChangedAt("com.miui.home", "com.android.camera", 9_000L, 5_000L),
        )
    }

    @Test
    fun `读到空值不算切换 不要把切换时刻反复刷新`() {
        // 否则一次读失败会把"刚换应用"这个状态挂住，越失败越一直拦。
        assertEquals(
            5_000L,
            ForegroundGate.nextChangedAt("com.android.camera", null, 9_000L, 5_000L),
        )
    }

    @Test
    fun `切换后窗口内要注手指的动作先等一拍`() {
        // 边界口径：age < window 算还在窗口内，age == window 就放行（和租约那条同一个约定）。
        assertTrue(ForegroundGate.deferTouchInject(nowMs = 5_599L, changedAtMs = 5_000L, windowMs = 600L))
    }

    @Test
    fun `窗口过去后照常执行`() {
        assertFalse(ForegroundGate.deferTouchInject(nowMs = 5_600L, changedAtMs = 5_000L, windowMs = 600L))
        assertFalse(ForegroundGate.deferTouchInject(nowMs = 5_700L, changedAtMs = 5_000L, windowMs = 600L))
    }

    @Test
    fun `从没检测到切换时不许拦住动作`() {
        // changedAt = 0 是初始值。拿 now-0 去比会得到"刚切换"，等于开机后 600ms 内什么都不干。
        assertFalse(ForegroundGate.deferTouchInject(nowMs = 300L, changedAtMs = 0L, windowMs = 600L))
    }

    @Test
    fun `相机里注手指类动作永远不许执行，和 override_camera 无关`() {
        // 2026-10-31 真机定案：日志里 `执行注入 scroll fg=com.android.camera cam=true 切换后 8015ms`。
        // 包名认对、前台读到、切换也早过了 600ms 窗口 —— 它照样执行，只因为那条放行写的是
        // "相机在前台**且没开覆盖**"。而他打开的就是覆盖。一次合成拖拽落在相机界面上，
        // 就是他报的"转动时误触到其他地方"。所以这条必须是硬的。
        assertFalse(ForegroundGate.allowsInjectedAction(camera = true, injectsTouch = true))
    }

    @Test
    fun `相机里非注入的动作照旧放行`() {
        // 音量/亮度这类不碰屏幕的动作是 override_camera 存在的意义，不能一起砍掉。
        assertTrue(ForegroundGate.allowsInjectedAction(camera = true, injectsTouch = false))
    }

    @Test
    fun `不在相机里时这条闸门什么都不做`() {
        assertTrue(ForegroundGate.allowsInjectedAction(camera = false, injectsTouch = true))
        assertTrue(ForegroundGate.allowsInjectedAction(camera = false, injectsTouch = false))
    }

    @Test
    fun `前台包名读失败只短暂缓存 读成功才缓存满窗口`() {
        assertEquals(200L, ForegroundGate.cacheTtlMs(pkg = "com.android.camera", okTtlMs = 200L, failTtlMs = 40L))
        assertEquals(40L, ForegroundGate.cacheTtlMs(pkg = null, okTtlMs = 200L, failTtlMs = 40L))
        assertEquals(40L, ForegroundGate.cacheTtlMs(pkg = "", okTtlMs = 200L, failTtlMs = 40L))
    }
}
