package xyz.nextalone.hyperzoomring.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 模块"我确实活着"的正面证据。
 *
 * 为什么需要它：界面原来只能靠"没报错"推断模块在不在跑，而 system_server 里的钩子
 * 到底有没有被加载、传感器有没有来过数据、注入有没有成功，App 进程一概看不见。
 * 所以让钩子自己往 Settings.Global 写一条心跳，App 读它 —— 这是**正面证据**，
 * 和"日志里搜不到"是两回事。
 *
 * 写入节制：只在 2 秒一次的探针节拍里落盘（SettingsProvider 写一次要动 XML），
 * 计数在内存里累加，不随传感器样本刷。
 *
 * 载荷格式（`|` 分隔，字段只增不改序）：
 *   sig | seq | ts | gesture | action | ok | fail
 *   sig     = 已加载 APK 的签名（名字|长度|mtime），用来判断"装的是不是生效的那份"
 *   seq     = 累计传感器样本数
 *   ts      = 最后一个样本的 uptimeMillis（和 App 进程同一个时钟，可以直接比新旧）
 *   gesture = 最近一次判定的手势枚举名
 *   action  = 最近一次分发的动作 id
 *   ok/fail = 注入成功 / 失败计数
 */
object Heartbeat {

    private const val TAG = "HyperZoomRing"
    const val KEY = "hzr_heartbeat"

    /**
     * 字段分隔符必须是签名里不会出现的那个字符：签名本身形如
     * `base.apk|1234567|1700000000000`（ScopeReload.signatureOf 的格式），
     * 用 `|` 当分隔符会把签名拆成三段、后面所有字段集体错位 —— 单测抓到过。
     */
    const val SEPARATOR = ";" 

    private val sampleSeq = AtomicLong(0)
    private val lastSampleAt = AtomicLong(0)
    private val injectOk = AtomicInteger(0)
    private val injectFail = AtomicInteger(0)

    @Volatile
    private var signature: String = ""

    @Volatile
    private var lastGesture: String = ""

    @Volatile
    private var lastAction: String = ""

    /** 钩子加载完成时调用一次：这是"模块真的进了 system_server"的最直接证据 */
    fun markLoaded(apkSignature: String) {
        signature = apkSignature
    }

    /** 每个传感器样本调一次，只做内存累加 */
    fun onSample() {
        sampleSeq.incrementAndGet()
        lastSampleAt.set(SystemClock.uptimeMillis())
    }

    fun onGesture(gesture: String) {
        lastGesture = gesture
    }

    fun onDispatch(actionId: String, success: Boolean) {
        lastAction = actionId
        if (success) injectOk.incrementAndGet() else injectFail.incrementAndGet()
    }

    /**
     * 由 2 秒一次的探针节拍调用，把内存计数落到 Settings.Global。
     *
     * 签名在这里惰性补齐：原来那套做法是在 HookEntry 里反射 ActivityThread.systemMain()
     * 拿 context，真机上那条反射不稳（隐藏 API），结果 hzr_loaded_apk 一直是 null、
     * 心跳签名一直是空串 —— "是否待重启"从来没真正判过。探针节拍本来就有可用 context，
     * 用它最省事也最可靠。
     */
    fun flush(context: Context) {
        if (signature.isEmpty()) {
            // 先问 classloader（真正"已加载"的那份），拿不到再退回 PackageManager 的安装路径
            val path = ModulePath.apkPath(Heartbeat::class.java)
                ?: ScopeReload.installedPaths(context).firstOrNull { it.contains(ModulePath.PKG) }
            val sig = ScopeReload.signatureOf(path)
            if (!sig.isNullOrEmpty()) {
                signature = sig
                runCatching { ScopeReload.publishLoadedSignature(context, sig) }
                    .onFailure { Log.w(TAG, "publish loaded signature failed: ${it.message}") }
            }
        }
        val payload = listOf(
            signature,
            sampleSeq.get().toString(),
            lastSampleAt.get().toString(),
            lastGesture,
            lastAction,
            injectOk.get().toString(),
            injectFail.get().toString(),
        ).joinToString(SEPARATOR)
        runCatching {
            Settings.Global.putString(context.contentResolver, KEY, payload)
        }.onFailure { Log.w(TAG, "heartbeat flush failed: ${it.message}") }
    }

    /** 解析结果。App 侧和这里共用同一份字段定义，避免两边各写一遍解析 */
    data class Snapshot(
        val signature: String,
        val samples: Long,
        val lastSampleAt: Long,
        val lastGesture: String,
        val lastAction: String,
        val ok: Int,
        val fail: Int,
    )

    fun parse(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val f = raw.split(SEPARATOR)
        if (f.size < 7) return null
        return runCatching {
            Snapshot(
                signature = f[0],
                samples = f[1].toLong(),
                lastSampleAt = f[2].toLong(),
                lastGesture = f[3],
                lastAction = f[4],
                ok = f[5].toInt(),
                fail = f[6].toInt(),
            )
        }.getOrNull()
    }
}
