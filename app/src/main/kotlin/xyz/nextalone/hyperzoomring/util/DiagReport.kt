package xyz.nextalone.hyperzoomring.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.CrownDir
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「反馈包」：把诊断页看得到的一切（心跳、探针回显、当前映射、模块日志）拼成一段文本，
 * 一键复制或存进「下载」目录 —— 目的是让反馈 bug 的人**不用连电脑、不用装 adb**，
 * 只要长按粘贴或者发个文件过来。
 *
 * 两条只有真机才暴露的坑，这个类就是围着它们设计的：
 *
 * 1. **App 写不进 Settings.Global**。App 是普通应用 uid，没有 WRITE_SECURE_SETTINGS，
 *    `Settings.Global.putString` 会被**静默丢弃**（不抛异常、返回 void）。真机实测：
 *    诊断页点「运行注入自测」后 `settings get global hzr_probe` 还是 null，
 *    而模块那半边一直是空的。本模块本身要求 root 才能装，所以这里改走
 *    `su -c "settings put global ..."` —— 与 adb shell 那条路等价。
 *
 * 2. **探针是边沿触发的**（见 DebugProbe.tick：值没变就不重跑）。所以不能只读现成的键，
 *    必须先清 0、再写位图，等一次探针节拍，三个回显键才会真的刷新。
 *    不这么做的话，用户导出的包里 `hzr_entry/hzr_cfg/hzr_map` 永远是空的或开机时的旧值。
 *
 * logcat 也按 tag 直取而不是"抓大缓冲区再筛"：模块的行在开机那一刻就产生了，
 * 等用户来反馈时早被 uiautomator / qcc_sdk 之类的噪声刷出缓冲区。
 * `-s TAG:V` 直接从各缓冲带上只取我们关心的 tag，能一路追到开机。
 */
object DiagReport {

    /** 退回非 root 通道时最多读这么多行（越大越慢，4000 行约 0.3 MB） */
    private const val DUMP_LINES = 4000

    /** 最终写进反馈包的最大行数，太长反而没人看 */
    internal const val MAX_LOG_LINES = 800

    private const val SU_TIMEOUT_S = 20L

    /**
     * 清 0 与写位图之间的间隔。
     *
     * **必须大于探针的轮询周期**（`ZoomRingSensorListener.PROBE_INTERVAL_MS` = 2000ms）。
     * 探针是 2 秒一拍地去读 `hzr_probe`：0 和位图如果落在同一个窗口里，那一拍只看得到
     * 位图，"清 0"等于没发生过，于是边沿判断又把同一个数当成"没变"。
     * 早先写 400ms 就是这个毛病 —— 反馈包和自测按钮都静默失效，见下面两处注释。
     */
    private const val PROBE_RESET_GAP_MS = 2_400L

    /**
     * 写完位图后等多久：探针 2 秒一拍，写进去之后最多要等满一拍才轮到它跑，
     * 而位图里最重的 8192（入口体检）自己还要读一圈系统设置。
     * 3 拍 ≈ 6 秒是稳妥值；再长用户会觉得按钮卡住了。
     */
    private const val PROBE_WAIT_MS = 6_000L

    /** 探针位图：128=映射回显，512=配置通道回显，8192=入口体检。三条都不改变设备行为 */
    internal const val PROBE_BITS = 128L or 512L or 8192L

    /**
     * 需要保留的日志 tag。前两个是本模块（system_server 侧 / App 侧），
     * 后几个是出问题时唯一能说明"框架到底有没有加载模块"的证据。
     * 用 tag 直取时这份清单同时是 `-s` 的参数来源。
     */
    private val KEEP_TAGS = listOf(
        "HyperZoomRing", "HyperZoomRingUI", "LSPosed", "Xposed", "libxposed",
    )

    /**
     * 噪声：uiautomator 是抓界面用的临时进程，一跑就往 AndroidRuntime 里刷十几行，
     * 真机第一次导出时 13 行里有 12 行是它，模块自己的证据反而被挤掉。
     */
    private val NOISE_TAGS = listOf("uiautomator", "chatty")

    /** 日志原文（未筛选） */
    private data class Raw(val text: String, val rooted: Boolean, val note: String?)

    /** 一次进程调用的结果：是否正常结束 + 退出码 + 全部输出 */
    private class Result(val ok: Boolean, val exit: Int, val text: String)

    private fun run(cmd: Array<String>): Result {
        val process = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return Result(false, -1, "")
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        out.append(line).append('\n')
                        if (out.length > 2_000_000) break
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = runCatching { process.waitFor(SU_TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) runCatching { process.destroyForcibly() }
        runCatching { reader.join(3_000) }
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        return Result(finished, exit, out.toString())
    }

    /**
     * 写一个 Settings.Global 键，先走 su，**写完全都回读确认**。
     *
     * 为什么必须回读：`Settings.Global.putString` 对没有 WRITE_SECURE_SETTINGS 的调用者是
     * **静默丢弃**（return void，不抛异常），所以 `runCatching{...}.isSuccess` 恒为 true。
     * 上一版就是栽在这里 —— 探针没被触发，包里却没有任何失败迹象。
     * 现在只有"读回来等于目标值"才算成功，其余一律 false，让调用方去写 NOTE。
     */
    internal fun writeGlobal(context: Context, key: String, value: String): Boolean {
        val su = run(arrayOf("su", "-c", "settings put global $key $value"))
        if (su.ok && su.exit == 0 && readGlobal(context, key) == value) return true
        // 退回直接写：将来 App 拿到 WRITE_SECURE_SETTINGS、或某些 ROM 放行时这条路才通
        runCatching { Settings.Global.putString(context.contentResolver, key, value) }
        return readGlobal(context, key) == value
    }

    private fun readGlobal(context: Context, key: String): String =
        runCatching {
            Settings.Global.getString(context.contentResolver, key) ?: ""
        }.getOrDefault("")

    /**
     * 触发一次 system_server 里的探针，把 `hzr_entry` / `hzr_cfg` / `hzr_map` 刷新出来。
     *
     * 必须**先清 0、隔一拍、再写位图**：DebugProbe.tick 只在"读到非 0 且与上次不同"时才干活。
     * 中间那一拍（[PROBE_RESET_GAP_MS]）不是保险，是必要条件 —— 探针是 2 秒一次的轮询，
     * 如果 0 和位图都落在同一个 2 秒窗口里，tick 根本看不到那个 0，于是一模一样的位图
     * 又被判成"没变"。这里踩过一次，见 [requestProbe]。
     *
     * @return 位图写进去了才 true（写不进去时反馈包里那三行仍然会是空的，得让调用方知道）
     */
    fun refreshProbe(context: Context): Boolean {
        writeGlobal(context, "hzr_probe", "0")
        Thread.sleep(PROBE_RESET_GAP_MS)
        val ok = writeGlobal(context, "hzr_probe", PROBE_BITS.toString())
        if (ok) Thread.sleep(PROBE_WAIT_MS)
        return ok
    }

    /**
     * 诊断页「运行注入自测」：位图 32 = 让 system_server 自己滚一次列表。
     *
     * 这里同样要隔一拍。曾经写成 `写 0 && 立刻写 32`，第一次按能通（32 与上一次的 8832
     * 本来就不同），**第二次按就再也没反应** —— 0 和 32 挤进同一个轮询窗口，
     * 探针看到的仍是"32，与上次相同"。用户会以为按钮坏了，其实是没触发。
     */
    fun requestProbe(context: Context, bits: Long): Boolean {
        writeGlobal(context, "hzr_probe", "0")
        Thread.sleep(PROBE_RESET_GAP_MS)
        return writeGlobal(context, "hzr_probe", bits.toString())
    }

    /**
     * 读日志。先试 root（能拿到 system_server 的行），失败再退回本进程。
     * `rooted=false` 时输出里必须写清楚 —— 否则用户发来的包看着"干净"，
     * 反而会让人误判成"模块没跑"。
     */
    private fun rawLog(): Raw {
        // 按 tag 直取：只留我们关心的 tag，不带 -t，于是能一路追到开机那一刻。
        // crash 缓冲单独再读一遍 —— FATAL EXCEPTION 不在 main 里，而它恰好最要紧。
        val tags = KEEP_TAGS.joinToString(" ") { "$it:V" }
        val script = "logcat -d -v time -b main,system -s $tags; " +
            "echo '=== crash buffer ==='; logcat -d -v time -b crash"
        val rooted = run(arrayOf("su", "-c", script))
        if (rooted.ok && rooted.text.isNotBlank()) {
            return Raw(rooted.text, rooted = true, note = null)
        }
        val plain = run(
            arrayOf(
                "logcat", "-d", "-v", "time", "-b", "main,system",
                "-s", *KEEP_TAGS.map { "$it:V" }.toTypedArray(),
            )
        )
        return when {
            plain.ok && plain.text.isNotBlank() -> Raw(
                plain.text,
                rooted = false,
                note = "root 不可用（su 未授权），只包含本 App 进程的日志；" +
                    "system_server 里的 HyperZoomRing 行需要给 su 授权后再导出",
            )
            else -> Raw("", rooted = false, note = "logcat 读取失败（root 未授权，本进程也没有可读日志）")
        }
    }

    /**
     * 这一行值不值得进反馈包。抽成 internal 是为了能在 JVM 单测里钉住：
     * 筛选一旦写错（比如匹配不到 tag），用户发来的包会"看着干净"，
     * 反而把人往"模块没跑"的错误方向带。
     */
    internal fun keepLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("===")) return true
        if (NOISE_TAGS.any { line.contains(it) }) return false
        if (KEEP_TAGS.any { line.contains(it) }) return true
        // AndroidRuntime 只在崩溃时值得留：uiautomator / am 每次启动都会刷几行 START/SHUTDOWN
        return line.contains("FATAL EXCEPTION") || line.contains("AndroidRuntime: Process:")
    }

    /**
     * 崩溃段里值得留的行。
     *
     * 这里**不能整段全留**：真机 20:34 那份包里 crash 缓冲第一条是 11-01 的 native
     * tombstone（`com.zhongan.ibank` 的 SIGSEGV），光寄存器就几十行，把 800 行预算吃光，
     * 反而把真正相关的 `DeadSystemException` 挤出上限。所以只挑"哪崩了 + 为什么"这几类。
     */
    internal fun keepCrashLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("===") || line.startsWith("--------- beginning")) return true
        val keys = listOf(
            "FATAL EXCEPTION", "Process:", "DeadSystemException", "Cmdline:", "pid: ",
            "signal ", "Cause:", "Abort message", "CRASH", "HyperZoomRing",
        )
        return keys.any { line.contains(it) }
    }

    /**
     * 从原始日志里挑出要写进反馈包的行。抽成 internal 是为了能在 JVM 单测里钉住：
     * 筛选一旦写错，用户发来的包会"看着干净"，反而把人往"模块没跑"的错误方向带。
     */
    internal fun selectLines(text: String): List<String> {
        val kept = ArrayList<String>()
        var inCrash = false
        for (line in text.lineSequence()) {
            if (line.startsWith("=== crash buffer ===")) {
                inCrash = true
                kept.add(line)
                continue
            }
            if (inCrash) {
                if (keepCrashLine(line)) kept.add(line)
                continue
            }
            if (keepLine(line)) kept.add(line)
        }
        return kept.takeLast(MAX_LOG_LINES)
    }

    private fun filtered(raw: Raw): List<String> = selectLines(raw.text)

    private fun global(context: Context, key: String): String = readGlobal(context, key)

    /** 环境 + 状态证据：没有日志也能看出"装的是哪版、映射是什么" */
    private fun environment(context: Context): String {
        val config = runCatching { ConfigManager.fromContext(context) }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("=== OmniRing 反馈包 ===")
            appendLine("time=$time")
            val version = runCatching {
                val p = context.packageManager.getPackageInfo(context.packageName, 0)
                "${p.versionName} (${p.longVersionCode})"
            }.getOrDefault("?")
            appendLine("app=${context.packageName} version=$version")
            appendLine("sdk=${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE}) model=${Build.MODEL} device=${Build.DEVICE}")
            // 这四行是从 system_server 里读回来的：真正的"模块到底跑没跑"证据
            appendLine("hzr_entry=${global(context, "hzr_entry")}")
            appendLine("hzr_cfg=${global(context, "hzr_cfg")}")
            appendLine("hzr_map=${global(context, "hzr_map")}")
            appendLine("hzr_hb=${global(context, Heartbeat.KEY)}")
            appendLine("hzr_loaded_apk=${global(context, ScopeReload.LOADED_KEY)}")
            if (config != null) {
                appendLine("enabled=${config.isEnabled} dispatch=${config.dispatchMode} speedThreshold=${config.speedThreshold}")
                appendLine("per_app=${config.getConfiguredPackages().joinToString(",")}")
                CrownDir.entries.forEach { dir ->
                    appendLine(
                        "map.${dir.name}=${config.getActionId(dir) ?: "-"}" +
                            " cfg=${config.getActionConfig(dir) ?: "-"}"
                    )
                }
                appendLine("legacy_slots=${runCatching { config.hasLegacySlots() }.getOrDefault(false)}")
            }
        }
    }

    /**
     * 完整的反馈包文本。会先触发一次探针再读，所以是**阻塞**调用（约 5 秒），
     * 调用方必须在 IO 线程上跑。
     */
    fun compose(context: Context): String {
        val probeOk = runCatching { refreshProbe(context) }.getOrDefault(false)
        val raw = rawLog()
        val log = filtered(raw)
        return buildString {
            append(environment(context))
            appendLine()
            appendLine("=== logcat (rooted=${raw.rooted}, kept=${log.size}, probe=$probeOk) ===")
            if (!probeOk) {
                appendLine(
                    "NOTE 探针没能触发（su 未授权？），上面的 hzr_entry/hzr_cfg/hzr_map " +
                        "可能是空的或开机时的旧值"
                )
            }
            if (raw.note != null) appendLine("NOTE ${raw.note}")
            if (log.isEmpty()) {
                appendLine("(没有匹配到 HyperZoomRing / LSPosed 的行)")
            } else {
                log.forEach { appendLine(it) }
            }
        }
    }

    fun copy(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OmniRing diag", text))
    }.isSuccess

    /**
     * 存进公共「下载/OmniRing」目录（MediaStore，API 29+ 不需要任何存储权限，
     * 也不要 FileProvider 和额外清单声明）。
     * @return 给用户看的落点描述，失败返回 null
     */
    fun save(context: Context, text: String): String? = runCatching {
        val name = "omniring-diag-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val relative = Environment.DIRECTORY_DOWNLOADS + "/OmniRing"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "$relative/$name"
    }.getOrNull()
}
