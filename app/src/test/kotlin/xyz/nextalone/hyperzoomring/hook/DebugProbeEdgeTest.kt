package xyz.nextalone.hyperzoomring.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针边沿判定的回归测试。
 *
 * 背景：反馈包的 `hzr_cfg` / `hzr_map` / `hzr_entry` 三行曾经一直在真机上**全空**，
 * 而反馈包自己"看起来"是成功的。查下来的根因不是日志没抓到，也不是 su 没权限，
 * 而是这里：`DebugProbe.tick` 当时写的是
 *
 *     if (cmd == 0L || cmd == lastCmd) return
 *     lastCmd = cmd
 *
 * 读到 0 时直接 return、**不把 lastCmd 也清掉**。于是 App 侧那套
 * "清 0 → 等一下 → 写位图" 的协议完全失效：清 0 那一拍被吞掉，
 * 位图又和上一轮的位图一模一样，被判定成"没变"。
 * 用户看到的就是按钮点了没反应、反馈包里三行永远是旧的。
 *
 * 这组用例把语义钉住：0 是"空闲"而不是"一个命令"。
 */
class DebugProbeEdgeTest {

    @Test
    fun zeroIsIdleNotACommand() {
        // 空闲时什么都不做
        assertFalse(DebugProbe.shouldRun(cmd = 0L, last = 0L))
        // 即便上一轮刚跑过 8832，读到 0 也不该再跑一次
        assertFalse(DebugProbe.shouldRun(cmd = 0L, last = 8832L))
    }

    @Test
    fun sameValueTwiceOnlyRunsOnce() {
        assertTrue(DebugProbe.shouldRun(cmd = 8832L, last = 0L))
        // 轮询每 2 秒读一次，位图还挂在那儿 —— 不能重复执行
        assertFalse(DebugProbe.shouldRun(cmd = 8832L, last = 8832L))
    }

    @Test
    fun resetThenSameBitmapRunsAgain() {
        // 这是那套「清 0 → 写回同一个位图」协议的完整时序。
        // 关键在第二步：读到 0 之后 last 必须被清成 0（由 tick 负责），
        // 于是第三步的同一个 8832 才能重新触发。
        var last = 0L
        val script = listOf(8832L, 0L, 8832L, 0L, 8832L)
        val ran = mutableListOf<Long>()
        for (cmd in script) {
            if (cmd == 0L) {
                last = 0L               // tick 里的"清记忆"
                continue
            }
            if (DebugProbe.shouldRun(cmd, last)) {
                last = cmd
                ran += cmd
            }
        }
        assertTrue("清 0 之后同一个位图必须能再跑，实际跑了 ${ran.size} 次", ran.size == 3)
    }

    @Test
    fun differentBitmapRunsWithoutReset() {
        // 位图不同（比如探针 32 换成 8832）时不需要先清 0
        assertTrue(DebugProbe.shouldRun(cmd = 8832L, last = 32L))
    }
}
