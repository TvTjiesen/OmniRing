package xyz.nextalone.hyperzoomring.util

import java.io.File

/**
 * 找出"本模块是被哪个 APK 文件加载进来的"。
 *
 * 不能只信 protectionDomain.codeSource.location：真机实测，LSPosed 用自定义
 * ClassLoader 把模块装进 system_server 时那里是 null，于是"是否待重启"的比对
 * 从来没成立过（心跳签名一直是空串）。所以再退一步去 dexElements 里找我们自己的包名。
 */
object ModulePath {

    const val PKG = "xyz.nextalone.hyperzoomring"

    fun apkPath(entry: Class<*>): String? {
        runCatching { entry.protectionDomain?.codeSource?.location?.path }
            .getOrNull()?.takeIf { it.endsWith(".apk") && File(it).exists() }
            ?.let { return it }

        var loader: ClassLoader? = entry.classLoader
        while (loader != null) {
            dexElementPaths(loader).firstOrNull { it.contains(PKG) && File(it).exists() }?.let { return it }
            loader = loader.parent
        }

        // 找不到就不猜：拿不到"已加载"的证据时，宁可让版本比对退化，
        // 也不要在没证据的时候催人去重启框架。调用方可以再用 Context 问 PackageManager 兜底。
        return null
    }

    private fun dexElementPaths(loader: ClassLoader): List<String> = runCatching {
        val pathList = findField(loader.javaClass, "pathList")?.get(loader) ?: return emptyList()
        val elems = findField(pathList.javaClass, "dexElements")?.get(pathList) as? Array<*> ?: return emptyList()
        elems.filterNotNull()
            .flatMap { e -> listOf(readField(e, "rawName"), readField(e, "fileName")) }
            .filterNotNull()
    }.getOrDefault(emptyList())

    private fun findField(start: Class<*>?, name: String): java.lang.reflect.Field? {
        var c = start
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }

    private fun readField(o: Any, name: String): String? =
        runCatching { findField(o.javaClass, name)?.get(o) as? String }.getOrNull()
}
