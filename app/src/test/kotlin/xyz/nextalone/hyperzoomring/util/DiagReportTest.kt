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
        assertTrue(DiagReport.keepLine("E AndroidRuntime: FATAL EXCEPTION: main"))
    }

    @Test
    fun dropsUnrelatedNoise() {
        assertFalse(DiagReport.keepLine("11-02 21:00:00.000  9000  9000 I chatty: uid=1000 expire 3 lines"))
        assertFalse(DiagReport.keepLine("W ActivityManager: slow operation"))
        assertFalse(DiagReport.keepLine(""))
    }

    @Test
    fun capIsBounded() {
        // 反馈包是给人看的，不是给机器解析的；上限改了要一起改这条
        assertTrue(DiagReport.MAX_LOG_LINES in 200..2000)
    }
}
