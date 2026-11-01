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

    /**
     * 安装包名（applicationId）。**必须跟着构建走，不许再写字面量**：
     * 广播的 action、签名级权限名、`setPackage()`、作用域过滤全都以它为主键，
     * 手写一遍就有漏的一处（2026-10-31 为进 LSPosed 商店改 applicationId 时，
     * 仓库里散落着 11 处字面量，靠人记是记不全的）。
     *
     * 注意它和 Kotlin 的 `package`/`namespace` 是两件事：包声明可以一直是
     * `xyz.nextalone.hyperzoomring`，而 applicationId 已经改成
     * `io.github.tvtjiesen.omniring`（商店要求"应用 ID 必须属于你"，
     * 见 docs/LSPOSED-STORE.md）。这里取的是**装机用的那一个**。
     */
    val PKG = xyz.nextalone.hyperzoomring.BuildConfig.APPLICATION_ID

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
