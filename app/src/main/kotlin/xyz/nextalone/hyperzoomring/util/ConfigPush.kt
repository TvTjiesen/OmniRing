package xyz.nextalone.hyperzoomring.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigPayload

/**
 * 把 App 的整份配置推给 system_server。
 *
 * 为什么必须推：实测 system_server 看不见 App 的私有 prefs 文件（exists=false /
 * canRead=false），YukiHook 那份快照又是启动时冻结的（改文件、杀框架、整机重启都
 * 读不到新值）—— 这就是"全局手势改了没反应"的真根因。
 *
 * 三条通道一起用，任何一条断了都还有另一条兜着：
 *  1. 写盘即推：监听 SharedPreferences 变化，防抖 250ms 后整份推一次；
 *  2. 开机被问就答：钩子起来后主动广播一个"要配置"的请求（带
 *     FLAG_INCLUDE_STOPPED_PACKAGES，system 进程有权唤醒停着的 app），
 *     本进程被拉起来后立刻回推 —— 不依赖他手动打开 App；
 *  3. 打开界面也推一次：他进设置页那一刻一定同步一次。
 *
 * 回推时**不带** receiverPermission：那个参数是"要求接收方持有某权限"，
 * 而 system_server 拿不到我们自定义的签名权限。改成在发送方侧管住：
 * 钩子注册接收器时要求 [ConfigPayload.PERMISSION]，于是只有与本应用同签名的
 * 进程推得进来，别的 app 伪造不了。
 */
object ConfigPush {

    private const val TAG = "HyperZoomRing"
    private const val DEBOUNCE_MS = 250L

    private var attached = false
    private var prefs: SharedPreferences? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = Runnable { pushNow() }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule() }

    /** 在 MainActivity 里调一次；重复调用无副作用 */
    fun attach(context: Context) {
        synchronized(this) {
            val p = prefsOf(context)
            appCtx = context.applicationContext
            if (attached) {
                pushNow()   // 每次进界面都同步一次，成本是一次 binder 调用
                return
            }
            attached = true
            prefs = p
            p.registerOnSharedPreferenceChangeListener(listener)
            appCtx?.registerReceiver(
                RequestReceiver,
                IntentFilter(ConfigPayload.ACTION_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            pushNow()
        }
    }

    private fun schedule() {
        main.removeCallbacks(pending)
        main.postDelayed(pending, DEBOUNCE_MS)
    }

    /** 立刻整份推一次 */
    fun pushNow() {        val p = prefs ?: return
        val ctx = appCtx ?: return
        val payload = ConfigPayload.encode(p.all)
        try {
            ctx.sendBroadcast(
                Intent(ConfigPayload.ACTION).putExtra(ConfigPayload.EXTRA_PAYLOAD, payload)
            )
            Log.i(TAG, "CFG push ${payload.length}B")
        } catch (e: Throwable) {
            Log.w(TAG, "CFG push failed", e)
        }
    }

    /**
     * 沉浸态的静默租约（见 [ConfigPayload.ACTION_STAGE]）。
     * 打开表盘时每 1 秒续一次 3 秒的租约；退出就不再续，最多 3 秒后表冠自动恢复。
     * 用租约而不是"开/关"两条广播，是因为关的那条万一丢了，表冠就永久哑了。
     */
    fun sendStageLease(untilElapsedMs: Long) {
        val ctx = appCtx ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(ConfigPayload.ACTION_STAGE)
                    .putExtra(ConfigPayload.EXTRA_STAGE_UNTIL, untilElapsedMs)
            )
        }.onFailure { Log.w(TAG, "STAGE 租约发不出去", it) }
    }

    /** 用户手指按下/抬起时发一条短租约：这段时间里钩子不许注入手指（见 ConfigPayload.ACTION_TOUCH） */
    fun sendTouchLease(untilElapsedMs: Long) {
        val ctx = appCtx ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(ConfigPayload.ACTION_TOUCH)
                    .putExtra(ConfigPayload.EXTRA_TOUCH_UNTIL, untilElapsedMs)
            )
        }.onFailure { Log.w(TAG, "TOUCH 租约发不出去", it) }
    }

    /** 钩子开机来要配置时，被拉起来就回推一次 */
    internal object RequestReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            attach(context)
            pushNow()
        }
    }

    private fun prefsOf(context: Context): SharedPreferences = ConfigManager.appPrefs(context)
}
