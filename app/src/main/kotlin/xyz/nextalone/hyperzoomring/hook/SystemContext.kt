package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.util.Log

/**
 * 拿一个**能注册广播接收器**的 Context。
 *
 * 实测坑（真机日志抓出来的）：YukiHook 在 `loadSystem` 里给的 `appContext` 是
 * Xposed 为 "android" 包造的 Context，它的 `IApplicationThread` 是 null ——
 * 用它 `registerReceiver` 直接
 * `NullPointerException: ... IActivityManager.registerReceiverWithFeature(...) on a null object reference`。
 * 读 Settings.Global、拿 SensorManager 都没问题，偏偏注册接收器不行。
 *
 * system_server 本身就是个正常的 ActivityThread 进程，所以走
 * `ActivityThread.currentActivityThread().getSystemContext()` 拿到的那个
 * 系统 Context 才具备 mPackageInfo。拿不到就退回原 Context（顶多推送不生效，
 * 不能让钩子崩）。
 */
object SystemContext {
    private const val TAG = "HyperZoomRing"

    @Volatile
    private var cached: Context? = null

    @Volatile
    private var tried = false

    /** @param fallback 拿不到系统 Context 时用的那个（Yuki 给的 appContext） */
    fun receiverContext(fallback: Context?): Context? {
        cached?.let { return it }
        if (tried) return fallback
        synchronized(this) {
            if (tried) return fallback
            tried = true
            val ok = runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread")
                    .invoke(null) ?: return@runCatching null
                val ctx = at.javaClass.getMethod("getSystemContext").invoke(at) as? Context
                ctx?.applicationContext ?: ctx
            }.onFailure { Log.w(TAG, "ActivityThread.systemContext 拿不到，用 fallback", it) }.getOrNull()
            if (ok != null) {
                cached = ok
                Log.i(TAG, "CFG 用 system context 注册接收器")
                return ok
            }
            Log.w(TAG, "CFG 只能用 Yuki 的 appContext 注册接收器（可能失败）")
            return fallback
        }
    }
}
