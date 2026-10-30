package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统版本解析单测。
 *
 * 为什么要单测：我之前在 README、代码注释和字符串里把这台机器写成"HyperOS 3 / Android 16"，
 * 他纠正"我的手机是 HyperOS 4 安卓 17"。界面上现在全部改成设备现读，
 * 那"读出来长什么样"就得钉住 —— 真机 `getprop` 的实际值就是下面这几个用例。
 */
class DeviceInfoTest {

    @Test
    fun `真机值 OS4_0_0_22_XPACNXM 解析成 HyperOS 4_0_0_22`() {
        assertEquals(
            "HyperOS 4.0.0.22",
            DeviceInfo.hyperOsFrom(
                incremental = "OS4.0.0.22.XPACNXM",
                name = "OS4.0",
                code = "4",
                fallback = "V816",
            ),
        )
    }

    @Test
    fun `没有增量号时退回 mi os version name`() {
        assertEquals(
            "HyperOS 4.0",
            DeviceInfo.hyperOsFrom(incremental = "", name = "OS4.0", code = "4", fallback = "V816"),
        )
    }

    @Test
    fun `只有主版本号码时也别空着`() {
        assertEquals(
            "HyperOS 4",
            DeviceInfo.hyperOsFrom(incremental = "", name = "", code = "4", fallback = "V816"),
        )
    }

    @Test
    fun `什么都读不到就用兜底值，不许编一个版本号`() {
        assertEquals(
            "V816",
            DeviceInfo.hyperOsFrom(incremental = "", name = "", code = "", fallback = "V816"),
        )
    }

    @Test
    fun `HyperOS 3 带 OS 前缀的增量号解析成 3_0_34_0`() {
        // 这一条是给"全面适配 HyperOS 3"补的证据：OS3 的号形如 3.0.34.0.WPCCNXM /
        // HyperOS 3.0.15.0.WPACNXM（新闻里出现过两种写法），适配卡要能读出 3.x 而不是空。
        assertEquals(
            "HyperOS 3.0.34.0",
            DeviceInfo.hyperOsFrom(incremental = "OS3.0.34.0.WPCCNXM", name = "OS3.0", code = "3", fallback = "V806"),
        )
    }

    @Test
    fun `HyperOS 3 增量号没有 OS 前缀时退回 name 也必须读出 3 系`() {
        // 全球版见过 "3.0.301.3" 这种无前缀写法：此时正则命中不了，必须落到 ro.mi.os.version.name，
        // 而不是把增量号本身当成 HyperOS 版本。
        assertEquals(
            "HyperOS 3.0",
            DeviceInfo.hyperOsFrom(incremental = "3.0.301.3.WPAMIXM", name = "OS3.0", code = "3", fallback = "XPAMIXM"),
        )
    }

    @Test
    fun `MIUI 时代的增量号不该被当成 HyperOS 版本`() {
        // 老 ROM 的 incremental 形如 "V816.0.6.0.VNCCNXM"，没有 OS 前缀 → 不许解析出 "OS ..."
        assertEquals(
            "MIUI V816",
            DeviceInfo.hyperOsFrom(
                incremental = "V816.0.6.0.VNCCNXM",
                name = "",
                code = "",
                fallback = "MIUI V816",
            ),
        )
    }
}
