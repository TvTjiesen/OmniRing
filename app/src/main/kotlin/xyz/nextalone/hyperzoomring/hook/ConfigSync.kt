package xyz.nextalone.hyperzoomring.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigPayload
import xyz.nextalone.hyperzoomring.config.MemoryStore

/**
 * 钩子侧收配置推送的那一半。
 *
 * 注册接收器时把 [ConfigPayload.PERMISSION] 作为**发送方**要求传进去：
 * 它是 signature 级权限，只有与本应用同签名的进程推得进来，别的 app 伪造不了；
 * 而 system_server 自己不需要持有它。
 *
 * 开机时 App 处于 stopped 状态（没收到过广播、用户也没打开过），普通广播叫不醒它，
 * 所以这里主动发一次"要配置"的请求并带上 FLAG_INCLUDE_STOPPED_PACKAGES ——
 * 这个 flag 只有系统进程能用，我们正好是系统进程。请求发三次（2s / 8s / 20s），
 * 收到过一次推送就停手，不跟他手机抢资源。
 */
object ConfigSync {

    private const val TAG = "HyperZoomRing"

    @Volatile
    var appliedCount = 0; private set

    @Volatile
    var lastKeyCount = 0; private set

    @Volatile
    var lastAppliedAt = 0L; private set

    /** 注册接收器失败的原因（界面/探针要看这个才知道通道断在哪一环） */
    @Volatile
    var registerError: String? = null; private set

    /** Settings.Global 在 system_server 刚起来那几秒可能还写不进去，重试到成功为止 */
    @Volatile
    private var published = false

    @Volatile
    private var lastPublishTry = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ConfigPayload.ACTION_STAGE) {
                stageUntil = intent.getLongExtra(ConfigPayload.EXTRA_STAGE_UNTIL, 0L)
                return
            }
            if (intent.action == ConfigPayload.ACTION_TOUCH) {
                touchUntil = intent.getLongExtra(ConfigPayload.EXTRA_TOUCH_UNTIL, 0L)
                return
            }
            val raw = intent.getStringExtra(ConfigPayload.EXTRA_PAYLOAD)
            if (raw == null) {
                Log.w(TAG, "CFG recv 没有载荷，忽略")
                return
            }
            val values = ConfigPayload.decode(raw)
            if (values.isEmpty()) {
                Log.w(TAG, "CFG recv 空载荷，忽略（保持现有配置）")
                return
            }
            saveSnapshot(context, raw)
            adopt(ConfigManager.fromStore(MemoryStore(values)), values.size, "push")
        }
    }

    /** 注册用的 Context（Yuki 给的那个也能发广播，注册要试到 AMS 起来为止） */
    @Volatile
    private var host: Context? = null

    @Volatile
    private var registered = false

    @Volatile
    private var lastTry = 0L

    fun start(context: Context) {
        host = context
        ensureRegistered()
        publishState(context)
        request(2_000L); request(8_000L); request(20_000L)
    }

    /**
     * 上一次推送的整份快照落到 Settings.Global，开机时自己读回来。
     *
     * 为什么必须这么做（他报的"重启之后模块功能完全失效"）：配置只能由 App 推，
     * 而 App 那侧接"要配置"请求的接收器是 **MainActivity 里运行时注册**的 ——
     * 进程没起来就没有接收器，`FLAG_INCLUDE_STOPPED_PACKAGES` 只能唤醒"声明过接收器"
     * 的进程，叫不醒一个只能在界面上打开时才存在的进程。于是开机后 system_server 手里
     * 只有那份冻结的启动快照（实测常常是空的），转环自然什么都不做，
     * 直到他手动打开一次 App 才恢复。Settings.Global 跨重启持久，写在这儿
     * 就等于模块自己带着配置开机，不依赖 App 自启、也不碰 MIUI 的后台启动限制。
     */
    private fun saveSnapshot(context: Context, raw: String) {
        runCatching {
            android.provider.Settings.Global.putString(context.contentResolver, KEY_SNAPSHOT, raw)
        }.onFailure { Log.w(TAG, "CFG 快照落盘失败（下次开机只能等 App 推）", it) }
    }

    /** @return 真的从快照恢复出一份配置才 true */
    private fun restoreSnapshot(context: Context): Boolean {
        val raw = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, KEY_SNAPSHOT)
        }.getOrNull()
        if (raw.isNullOrEmpty()) return false
        val values = ConfigPayload.decode(raw)
        if (values.isEmpty()) return false
        adopt(ConfigManager.fromStore(MemoryStore(values)), values.size, "snapshot")
        // 正面证据：开机自恢复到底有没有真的跑过。光看 hzr_map 分不清是这份快照
        // 还是 Yuki 那份启动快照喂出来的，所以单独落一个标记键。
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver, KEY_BOOT_RESTORE,
                "${values.size};${System.currentTimeMillis()}"
            )
        }
        Log.i(TAG, "CFG 已从开机快照恢复（$KEY_SNAPSHOT ${raw.length}B, ${values.size} 键）")
        return true
    }

    /**
     * 由已有的 2 秒节拍反复调用，直到注册成功。
     *
     * 实测：本模块的 hook() 跑在 system_server 极早期，那时
     * `ActivityManager.getService()` 还是 null —— 直接 registerReceiver 会抛
     * `NullPointerException: ... IActivityManager.registerReceiverWithFeature(...)
     * on a null object reference`（空的是 AMS 这个 binder 代理，不是 ApplicationThread，
     * 我一开始判断错过一次）。所以必须等 AMS 发布之后再注册，而不是在 hook() 里一次定生死。
     */
    fun ensureRegistered() {
        if (registered) return
        val ctx = host ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTry < 3_000L) return
        lastTry = now
        runCatching {
            ctx.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ConfigPayload.ACTION)
                    addAction(ConfigPayload.ACTION_STAGE)
                    addAction(ConfigPayload.ACTION_TOUCH)
                },
                ConfigPayload.PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
            registered = true
            registerError = null
            Log.i(TAG, "CFG receiver registered")
        }.onFailure {
            registerError = it.javaClass.simpleName
            Log.d(TAG, "CFG receiver 还没法注册（AMS 可能没就绪）", it)
        }
    }

    /** 换配置的入口由 InputInterceptorHook 提供 —— 只给它 setter，
     *  不给 getter：在 `with(param)` 里读 `config` 会命中同名函数参数，拿到的是启动那份旧快照 */
    private var swap: ((ConfigManager) -> Unit)? = null

    fun bind(set: (ConfigManager) -> Unit) {
        swap = set
    }

    /** 这份配置是从哪来的：推送 / 开机快照（诊断要看，别混） */
    @Volatile
    var lastSource = "boot"; private set

    /**
     * 沉浸态的静默到期时间（elapsedRealtime 毫秒）。0 = 没在静默。
     * 由 App 每 1 秒续一次租约、3 秒到期：关闭那条广播万一丢了，表冠最多 3 秒后自动恢复。
     */
    @Volatile
    var stageUntil = 0L

    fun stagePaused(): Boolean =
        android.os.SystemClock.elapsedRealtime() < stageUntil

    /**
     * 用户手指正压在我们 App 上（App 每 ~200ms 续一次、600ms 到期）。
     * 到期即恢复，不需要 App 显式"结束"一条广播 —— 万一那条广播丢了，最多 600ms 后表冠就活。
     */
    @Volatile
    var touchUntil = 0L

    fun touchPaused(): Boolean =
        android.os.SystemClock.elapsedRealtime() < touchUntil

    /** 表冠这一轮要不要什么都不做：沉浸态静默期 或 用户正在用手 */
    fun paused(): Boolean = stagePaused() || touchPaused()

    private fun adopt(next: ConfigManager, keys: Int, source: String = "push") {
        swap?.invoke(next)
        appliedCount++
        lastKeyCount = keys
        lastAppliedAt = System.currentTimeMillis()
        lastSource = source
        InputInterceptorHook.appContext?.let { publishState(it) }
        Log.i(TAG, "CFG applied $source config (#$appliedCount, $keys keys)")
    }

    /**
     * 由已有的 2 秒节拍驱动的重试。
     *
     * 实测坑：start() 跑在 system_server 启动很早的时刻，那时 SettingsProvider 还没就绪，
     * 这一次 putString 会**静默失败**（hzr_cfg_state 读出来是 null）。所以不能只写一次，
     * 要挂着重试，成功之后只在状态真的变了以后再写。
     */
    @Synchronized
    fun tick(context: Context) {
        ensureRegistered()
        val now = android.os.SystemClock.uptimeMillis()
        // 开机快照：还没收到任何推送时试几次，试到恢复成功或次数用完
        // （start() 跑得太早，那时 SettingsProvider 还没就绪，读 global 会是 null）
        if (appliedCount == 0 && snapshotTries < MAX_SNAPSHOT_TRIES &&
            now - lastSnapshotTry >= SNAPSHOT_RETRY_MS
        ) {
            lastSnapshotTry = now
            snapshotTries++
            if (restoreSnapshot(context)) snapshotTries = MAX_SNAPSHOT_TRIES
        }
        if (published && appliedCount == lastPublishedCount) return
        if (now - lastPublishTry < 5_000L) return
        lastPublishTry = now
        published = runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver, KEY_STATE,
                "$appliedCount;$lastKeyCount;$lastAppliedAt"
            ); true
        }.getOrDefault(false)
        if (published) lastPublishedCount = appliedCount
    }

    private var lastPublishedCount = -1

    // 开机快照恢复的重试状态（由 tick 驱动，见 restoreSnapshot 的注释）
    private const val SNAPSHOT_RETRY_MS = 5_000L
    private const val MAX_SNAPSHOT_TRIES = 12
    private var snapshotTries = 0
    private var lastSnapshotTry = 0L

    /** 把同步状态写给界面看（界面没法直接问 system_server，只能靠这条共享键） */
    private fun publishState(context: Context) {
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                KEY_STATE,
                "$appliedCount;$lastKeyCount;$lastAppliedAt"
            )
            published = true
            lastPublishedCount = appliedCount
        }
    }

    const val KEY_STATE = "hzr_cfg_state"

    /** 上次推送的整份编码快照（跨重启持久，让模块自己带着配置开机） */
    const val KEY_SNAPSHOT = "hzr_snapshot"

    /** 开机自恢复跑过的证据：`键数;时间戳`（分不清两份快照谁起作用时看它） */
    const val KEY_BOOT_RESTORE = "hzr_boot_restore"
    private fun request(delayMs: Long) {
        handler.postDelayed({
            // 已经收到过真推送就不再要了；只恢复了开机快照时仍然要 —— 快照可能比他
            // 后来改的配置旧，App 活着的时候必须有机会把新的推过来
            if (lastSource == "push") return@postDelayed
            val send = InputInterceptorHook.appContext ?: return@postDelayed
            runCatching {
                send.sendBroadcast(
                    Intent(ConfigPayload.ACTION_REQUEST)
                        .setPackage(xyz.nextalone.hyperzoomring.util.ModulePath.PKG)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                )
                Log.i(TAG, "CFG request sent (delay=$delayMs)")
            }
        }, delayMs)
    }
}
