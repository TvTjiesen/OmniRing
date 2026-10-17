package xyz.nextalone.hyperzoomring.util

import android.os.Build

/**
 * 设置页「本机与适配」要显示的系统信息。
 *
 * 全部走 runCatching：这是一台会被装到各种 ROM 上的模块，读不到属性是常态，
 * 不能让一个属性名把设置页搞崩。getprop 用 exec 而不是反射 SystemProperties，
 * 因为后者在 targetSdk 35 上属于受限隐藏 API。
 */
object DeviceInfo {

    /** 本模块实测过的机型：Xiaomi 17 Ultra 徕卡版（dev name nezha） */
    private const val SUPPORTED_DEVICE = "nezha"

    val device: String get() = prop("ro.product.marketname").ifBlank { Build.MODEL }
    val deviceName: String get() = Build.DEVICE
    val brand: String get() = Build.BRAND
    val androidVersion: String get() = Build.VERSION.RELEASE
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    /** HyperOS / MIUI 的版本号，取第一个能读到的 */
    val osVersion: String
        get() = firstNonBlank(
            prop("ro.mi.os.version.name"),
            prop("ro.miui.ui.version.name"),
            prop("ro.vivo.os.version"),
            prop("ro.build.version.opporom"),
            Build.DISPLAY,
        )

    /**
     * HyperOS 的完整版本号，例如 `HyperOS 4.0.0.22`。
     *
     * 为什么不用 `ro.miui.ui.version.name`：那台机子上它是 `V816`，是 MIUI 框架的内部序号，
     * 不是用户认得的"HyperOS 4"。真正能读出来的是
     * `ro.build.version.incremental = OS4.0.0.22.XPACNXM`，退一步才是 `ro.mi.os.version.name = OS4.0`。
     * 2026-10-17 他纠正"我的手机是 HyperOS 4 安卓 17"之后，全仓的口径都改成了从设备现读。
     */
    val hyperOsVersion: String
        get() = hyperOsFrom(
            incremental = prop("ro.build.version.incremental"),
            name = prop("ro.mi.os.version.name"),
            code = prop("ro.mi.os.version.code"),
            fallback = osVersion,
        )

    /** 纯函数，单测直接喂真机读到的那几串（我把系统版本写错过一次，这次钉住它） */
    internal fun hyperOsFrom(incremental: String, name: String, code: String, fallback: String): String {
        Regex("""OS(\d+(?:\.\d+)+)\.""").find(incremental)?.groupValues?.get(1)?.let { return "HyperOS $it" }
        if (name.isNotBlank()) return "HyperOS ${name.removePrefix("OS")}"
        if (code.isNotBlank()) return "HyperOS $code"
        return fallback
    }

    /** HyperOS 主版本号（4 = HyperOS 4）。认不出来 = 0，调用方按"未知"处理 */
    val hyperOsMajor: Int
        get() {
            val v = hyperOsVersion
            if (!v.startsWith("HyperOS")) return 0
            return Regex("""\d+""").find(v)?.value?.toIntOrNull() ?: 0
        }

    /** 设置页展示的一行系统说明：`HyperOS 4.0.0.22 · Android 17（SDK 37）` */
    val osLine: String
        get() = "$hyperOsVersion · Android $androidVersion（SDK $sdkInt）"

    val isSupportedDevice: Boolean get() = Build.DEVICE == SUPPORTED_DEVICE

    /** 给设置页展示的一行机型说明 */
    val modelLine: String get() = "${brand.uppercase()} ${device} · $deviceName"

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: "?"

    private val propCache = HashMap<String, String>()

    private fun prop(key: String): String = propCache.getOrPut(key) {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            p.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
    }
}
