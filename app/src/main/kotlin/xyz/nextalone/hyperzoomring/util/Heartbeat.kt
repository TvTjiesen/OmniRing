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
     * 输入侧那条路（AOSP 的 `filterInputEvent`）有没有真的收到过环事件。
     *
     * 这是**给 HyperOS 3 远程测试用的判据**，不是给我们自己看的：
     * 我们没有 OS3 的机器，而这条路上"收得到事件"恰恰是 OS3 与 OS4 的分水岭 ——
     * OS4 上 `InputFilterEnabled=false`，它一个事件都收不到；OS3 上收得到，于是会
     * 和传感器路撞车。撞车由 [RingSourceArbiter] 挡，挡住之后这条计数会涨在
     * "只消费"那一档 —— 酷友只要把这一行带回来，我们就能确定：
     * 输入侧活着、仲裁器在挡、动作只执行了一遍。
     */
    private val inputExec = AtomicInteger(0)
    private val inputConsumeOnly = AtomicInteger(0)

    fun onInputPath(executed: Boolean) {
        if (executed) inputExec.incrementAndGet() else inputConsumeOnly.incrementAndGet()
    }

    /**
     * 上一次落到 `hzr_loaded_apk` 的值。
     *
     * 为什么必须重试（2026-11-01 真机抓到的，判据链是闭合的，不是猜的）：
     * 原来 `flush()` 里"解析签名"和"发布签名"在同一条 `signature.isEmpty()` 分支里，
     * 于是**一辈子只发一次**。真机现场是：新模块确实加载了新 dex（探针回显 `build=cfg43`、
     * 心跳载荷里的签名是 `base.apk|47646324|…` = 新 APK），而 `hzr_loaded_apk` 还停在上一版的
     * `base.apk|47121321|…`。心跳载荷能带上新签名，说明那条分支**跑过了、解析成功了**；
     * 键里却是旧值，说明**同一次里的 Settings.Global 写没落地**（开机头几十秒
     * SettingsProvider 还不接受写，这条链路上我们早就见过同类失败）——
     * 而它再也不会重试。App 的"是否待重启"判的正是 `installed == loaded`，
     * 结果模块明明好了，首页还一直叫他去重启作用域（#46"生效状态判定不稳定"的一条真根因）。
     */
    @Volatile
    private var publishedSignature: String? = null

    /** 纯判据：签名非空且和上次落过的不一样才写（SettingsProvider 写一次要动 XML，别每拍都写） */
    fun shouldPublish(current: String, lastPublished: String?): Boolean =
        current.isNotEmpty() && current != lastPublished

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
            if (!sig.isNullOrEmpty()) signature = sig
        }
        if (shouldPublish(signature, publishedSignature)) {
            publishedSignature = signature
            runCatching { ScopeReload.publishLoadedSignature(context, signature) }
                .onFailure { Log.w(TAG, "publish loaded signature failed: ${it.message}") }
        }
        val payload = listOf(
            signature,
            sampleSeq.get().toString(),
            lastSampleAt.get().toString(),
            lastGesture,
            lastAction,
            injectOk.get().toString(),
            injectFail.get().toString(),
            // 第 8 个字段：输入侧"执行过 / 只消费过"的次数。字段只增不改序，老 App 读前 7 个照旧。
            "${inputExec.get()}/${inputConsumeOnly.get()}",
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
        /** 输入侧（AOSP `filterInputEvent`）执行过动作的次数。0 = 这条 ROM 收不到环的输入事件 */
        val inputExec: Int = 0,
        /** 输入侧"只吞事件不执行"的次数 —— 在 HyperOS 3 上这个数字应该涨，涨了才证明没双倍执行 */
        val inputConsumeOnly: Int = 0,
    ) {
        /** 哪条路在干活：输入侧完全没来过 = 本机（OS4）走传感器；来过 = OS3 那种两条路并存 */
        val activePath: String
            get() = if (inputExec + inputConsumeOnly == 0) "sensor" else "input+sensor"

        /**
         * 一行"看得懂截图"的证据：路径 + 输入侧被挡下 / 真执行的次数。
         *
         * 为什么要它：业主决定不发给测试者任何 adb 步骤，只让他们"有问题来找我"。
         * 那样一来我们最怕的那件事（OS3 上两条路各执行一遍 = 滚一下走两下）就没有数据面。
         * 这一行让**一张首页截图**足够判掉：
         *   `sensor`                  → 输入侧一个事件都没收到（和 OS4 一样）
         *   `input+sensor 挡N/执0`     → 收到事件了，但全被仲裁器挡下 → 只执行一遍，正常
         *   `input+sensor 挡N/执M>0`   → 有 M 次是输入侧真的执行的 → 传感器路当时在断，
         *                              不是双倍执行，但要问他那一下是不是"走了两下/慢了半拍"
         */
        val pathEvidence: String
            get() = if (inputExec + inputConsumeOnly == 0) "sensor"
            else "input+sensor 挡${inputConsumeOnly}/执${inputExec}"
    }

    fun parse(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val f = raw.split(SEPARATOR)
        if (f.size < 7) return null
        return runCatching {
            // 第 8 个字段是后加的：老 payload（7 段）必须还能解析，别因为长度不够就整条判废
            val path = f.getOrNull(7).orEmpty().split("/")
            Snapshot(
                signature = f[0],
                samples = f[1].toLong(),
                lastSampleAt = f[2].toLong(),
                lastGesture = f[3],
                lastAction = f[4],
                ok = f[5].toInt(),
                fail = f[6].toInt(),
                inputExec = path.getOrNull(0)?.toIntOrNull() ?: 0,
                inputConsumeOnly = path.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }.getOrNull()
    }
}
