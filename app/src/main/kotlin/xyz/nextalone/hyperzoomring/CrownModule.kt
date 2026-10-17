package xyz.nextalone.hyperzoomring

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import xyz.nextalone.hyperzoomring.hook.CrownHooks
import xyz.nextalone.hyperzoomring.hook.DebugProbe

/**
 * libxposed API 102 入口 —— 这一版是"真把废弃 API 换掉"，不是把提示藏起来。
 *
 * 判据（读 LSPosed 的模块解析代码 + 他机上那份 manager 确认）：daemon 看
 * `META-INF/xposed/module.prop` 的 `targetApiVersion`，≥101 才走 `java_init.list`，
 * 否则只要 APK 里有 `assets/xposed_init` 就判 LEGACY，manager 那句
 * "此模块使用了已废弃且即将移除的功能"跟着这个位显示。
 *
 * 为什么不能停在 101：101 仍然允许模块解析 `de.robv.android.xposed.*`，那次尝试就靠这个
 * 把参数原封不动交给 YukiHook 的 DSL（[ModernEntry] 旧实现）。102 起不许，所以 hook 这一层
 * 必须真的换成 `XposedInterface.hook(Executable).intercept { chain -> ... }`，见 [CrownHooks]。
 *
 * 这个类里**一行 legacy/YukiHook 都不碰**：
 *  - 装钩子 → [CrownHooks.install]
 *  - 拿 system_server 的 Context（102 面不在回调参数里给）→ [CrownHooks.Boot]
 *  - 剩下的运行时（配置推送、传感器、场景）与 legacy 入口共用同一段
 *    `InputInterceptorHook.startRuntime`，两条入口不可能出现行为分裂。
 *
 * `assets/xposed_init` 与 [HookEntry] 保留：LSPosed 1.x 和其它框架还靠它加载，
 * 不能为了消警告把老框架的用户弄坏。两条入口互斥：框架只会选一条。
 */
class CrownModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        DebugProbe.entryPath = "modern${runCatching { apiVersion }.getOrDefault(0)}"
        Log.i(
            TAG,
            "MODERN 模块已加载 systemServer=${param.isSystemServer} proc=${param.processName} " +
                "api=${runCatching { apiVersion }.getOrNull()} " +
                "fw=${runCatching { frameworkName }.getOrNull()} " +
                "${runCatching { frameworkVersion }.getOrNull()}"
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // 整段包起来：这条回调跑在 system_server 的 main() 之前，这里抛出去的东西
        // 会把框架启动直接带崩（他不是那种能接受"开机进不去"的实验机）
        runCatching {
            val loader = param.classLoader
            val t0 = android.os.SystemClock.elapsedRealtime()
            CrownHooks.install(this, loader)
            // Context 要等 ActivityThread 起来才有，交给后台节拍去试，不占 system_server 主线程
            CrownHooks.Boot.schedule()
            Log.i(
                TAG,
                "MODERN system_server 入口完成 ${CrownHooks.diagnostics()} " +
                    "用时=${android.os.SystemClock.elapsedRealtime() - t0}ms"
            )
        }.onFailure { Log.e(TAG, "MODERN system_server 入口抛了（这一次开机表冠这条链路不可用）", it) }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 作用域只有 system，这里正常不该收到应用回调；真收到了也只记一行，
        // 绝不重复装钩子（旧路径的 loadSystem 同理只会有一次）
        Log.i(TAG, "MODERN onPackageLoaded pkg=${param.packageName}（预期只有 system_server，这条用来定位）")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        Log.i(TAG, "MODERN onPackageReady pkg=${param.packageName}")
    }

    private companion object {
        const val TAG = "HyperZoomRing"
    }
}
