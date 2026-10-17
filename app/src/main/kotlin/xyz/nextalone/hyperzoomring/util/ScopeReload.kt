package xyz.nextalone.hyperzoomring.util

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「重启作用域」：模块代码更新后，system_server 里跑的还是旧的那份，
 * 必须让框架重启一次才生效 —— 但整机重启没必要，杀 system_server 就够了（约 20 秒）。
 *
 * 怎么知道"确实需要重启"：模块加载时会把**当时那个 APK 的路径+大小+修改时间**写进
 * Settings.Global（见 HookEntry），设置页拿它和现在已安装的 APK 比一下。
 * 一样就说明没有待生效的改动，按钮直接显示"无需重启"，不做没必要的重启。
 *
 * 执行方式只有 Root 一条路：`su -c "kill -9 $(pidof system_server)"`。
 * 拿不到 Root 就明确告诉他该手动授权或用 adb，不假装成功。
 */
object ScopeReload {
    /** system_server 里模块写这个键，需要 WRITE_SECURE_SETTINGS（系统进程自带） */
    const val LOADED_KEY = "hzr_loaded_apk"

    private const val SHELL_CMD = "kill -9 $(pidof system_server)"

    sealed class State {
        data object Unknown : State()
        data object UpToDate : State()
        data object ReloadPending : State()
    }

    /** 同一个 APK 路径在两侧算出的指纹必须完全一致，所以算法只写一遍 */
    fun signatureOf(apkPath: String?): String? {
        if (apkPath.isNullOrEmpty()) return null
        val f = File(apkPath)
        if (!f.exists()) return null
        return "${f.name}|${f.length()}|${f.lastModified()}"
    }

    /** 从 PackageManager 能看到的本模块安装路径（不需要任何隐藏 API） */
    fun installedPaths(context: Context): List<String> = runCatching {
        val ai = context.packageManager.getApplicationInfo(ModulePath.PKG, 0)
        listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
    }.getOrDefault(emptyList())

    /** 当前安装的 APK 指纹 */
    fun installedSignature(context: Context): String? = runCatching {
        signatureOf(
            context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        )
    }.getOrNull()

    /** system_server 里记下的"正在生效的那份" */
    fun loadedSignature(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, LOADED_KEY)
    }.getOrNull()

    fun state(context: Context): State {
        val installed = installedSignature(context) ?: return State.Unknown
        val loaded = loadedSignature(context) ?: return State.Unknown
        return if (installed == loaded) State.UpToDate else State.ReloadPending
    }

    /** 模块加载时调用（system_server 侧） */
    fun publishLoadedSignature(context: Context, signature: String?) {
        if (signature == null) return
        runCatching {
            Settings.Global.putString(context.contentResolver, LOADED_KEY, signature)
        }
    }

    enum class RestartResult { Started, NoRoot, Failed }

    fun restartFramework(): RestartResult = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", SHELL_CMD))
        val ok = p.waitFor(6, TimeUnit.SECONDS)
        when {
            !ok -> RestartResult.Started            // 超时没退：多半是框架正在重启
            p.exitValue() == 0 -> RestartResult.Started
            else -> RestartResult.NoRoot
        }
    } catch (e: Throwable) {
        if (e is java.io.IOException) RestartResult.NoRoot else RestartResult.Failed
    }
}
