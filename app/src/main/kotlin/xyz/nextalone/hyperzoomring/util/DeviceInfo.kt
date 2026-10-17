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
