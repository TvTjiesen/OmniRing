package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈包的日志筛选：只留能说明"模块到底跑没跑"的行。
 * 这些断言防的是"筛选正则/子串写错 → 用户发来的包看着干净 → 误判成模块没加载"。
 */
class DiagReportTest {

    @Test
    fun keepsModuleAndFrameworkLines() {
        assertTrue(DiagReport.keepLine("11-02 21:00:00.000  1234  1500 I HyperZoomRing: deliver actionId=scroll"))
        assertTrue(DiagReport.keepLine("11-02 21:00:00.000  1234  1500 I HyperZoomRingUI: GLASS 画好了"))
        assertTrue(DiagReport.keepLine("11-02 21:00:00.000  1234  1500 I LSPosed: loaded module"))
        assertTrue(DiagReport.keepLine("11-02 21:00:00.000  1234  1500 I Xposed: module class loaded"))
        assertTrue(DiagReport.keepLine("E AndroidRuntime: FATAL EXCEPTION: main"))
        // crash 缓冲的分隔行必须留下，否则崩溃段会跟正文粘在一起、看不出边界
        assertTrue(DiagReport.keepLine("=== crash buffer ==="))
    }

    @Test
    fun dropsUnrelatedNoise() {
        assertFalse(DiagReport.keepLine("11-02 21:00:00.000  9000  9000 I chatty: uid=1000 expire 3 lines"))
        assertFalse(DiagReport.keepLine("W ActivityManager: slow operation"))
        assertFalse(DiagReport.keepLine(""))
        // 真机第一次导出时 13 行里有 12 行是这个：抓界面用的临时进程，跟模块毫无关系
        assertFalse(
            DiagReport.keepLine(
                "11-03 19:30:17.000 11363 11363 D AndroidRuntime: " +
                    ">>> START com.android.commands.uiautomator.Launcher"
            )
        )
    }

    @Test
    fun keepsAndroidCrashHeaderButNotAmNoise() {
        // am 每次启动都会刷这些，留着只会把崩溃挤出去
        assertFalse(DiagReport.keepLine("11-03 19:30:17.000  2000  2000 D AndroidRuntime: SHUTDOWN"))
        // 真正的崩溃头必须留
        assertTrue(DiagReport.keepLine("11-03 19:30:17.000  2000  2000 D AndroidRuntime: Process: io.github.tvtjiesen.omniring, PID: 2569"))
    }

    @Test
    fun crashSectionKeepsBlameNotRegisters() {
        // 真机 20:34 那份包里 crash 缓冲第一条是 11-01 的 native tombstone，
        // 光寄存器就几十行，把 800 行预算吃光，反而挤掉了真正相关的崩溃头。
        // 所以崩溃段要挑"哪崩了 + 为什么"，不能整段倒进去。
        val raw = listOf(
            "--------- beginning of main",
            "11-03 19:42:56.872 I/HyperZoomRing(14860): PROBE start cmd=8832",
            "11-03 19:42:56.900 I/chatty(9000): uid=1000 expire 3 lines",
            "=== crash buffer ===",
            "--------- beginning of crash",
            "11-01 18:37:38.024 F/libc    (17936): Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)",
            "11-01 18:37:38.706 F/DEBUG   (21445):     x0  0000000000000000  x1  000000005c000000",
            "11-01 18:37:38.706 F/DEBUG   (21445):     x28 00000076181f6400  fp  00000076181f63b8",
            "11-01 18:37:38.706 F/DEBUG   (21445): Cmdline: com.zhongan.ibank",
            "11-03 19:42:17.558 E/AndroidRuntime(25171): FATAL EXCEPTION: main",
            "11-03 19:42:17.558 E/AndroidRuntime(25171): Process: com.android.systemui, PID: 25171",
            "11-03 19:42:17.558 E/AndroidRuntime(25171): DeadSystemException: The system died",
        ).joinToString("\n")
        val picked = DiagReport.selectLines(raw)
        assertTrue(picked.contains("11-03 19:42:56.872 I/HyperZoomRing(14860): PROBE start cmd=8832"))
        assertTrue(picked.contains("=== crash buffer ==="))
        assertTrue(picked.any { it.contains("DeadSystemException") })
        assertTrue(picked.any { it.contains("Cmdline: com.zhongan.ibank") })
        // 寄存器转储必须掉，否则几百行预算全被它吃掉
        assertFalse(picked.any { it.contains("x0  0000000000000000") })
        assertFalse(picked.any { it.contains("x28 00000076181f6400") })
        // 崩溃段之前的噪声照样要掉
        assertFalse(picked.any { it.contains("chatty") })
    }

    @Test
    fun capIsBounded() {
        // 反馈包是给人看的，不是给机器解析的；上限改了要一起改这条
        assertTrue(DiagReport.MAX_LOG_LINES in 200..2000)
    }
}
