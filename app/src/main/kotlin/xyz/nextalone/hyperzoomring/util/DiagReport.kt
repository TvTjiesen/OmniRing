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
 * 一键复制或存进「下载」目录 —— 目的是让反馈 bug 的人**不用 root 手机、不用装 adb**，
 * 只要长按粘贴或者发个文件过来。
 *
 * 为什么必须走 root 读 logcat：模块跑在 system_server 里（日志 tag 是 HyperZoomRing），
 * 普通 App 的 logcat 只看得到自己 UID 的行，看不到 system_server。而这个模块本身
 * 就要求 root 才能装（LSPosed + 作用域 = 系统框架），所以用户手上必然有 su；
 * 拿不到 su 时退回读自己进程的日志，并在输出里**明确标注**，不假装成功。
 */
object DiagReport {

    /** 抓多少行原始 logcat 来筛（越大越慢，4000 行大约 0.3 MB） */
    private const val DUMP_LINES = 4000

    /** 最终写进反馈包的最大行数，太长反而没人看 */
    internal const val MAX_LOG_LINES = 800

    private const val SU_TIMEOUT_S = 20L

    /**
     * 需要保留的日志来源。前两个是本模块（system_server 侧 / App 侧），
     * 后几个是出问题时唯一能说明"框架到底有没有加载模块"的证据。
     */
    private val KEEP_TAGS = listOf(
        "HyperZoomRing", "HyperZoomRingUI", "LSPosed", "Xposed",
        "AndroidRuntime", "libxposed", "KernelSU", "Magisk",
    )

    /** 日志原文（未筛选） */
    private data class Raw(val text: String, val rooted: Boolean, val note: String?)

    data class Bundle(val text: String)

    /** 一次进程调用的结果：是否正常结束 + 全部输出 */
    private class Result(val ok: Boolean, val text: String)

    private fun run(cmd: Array<String>): Result {
        val process = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return Result(false, "")
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
        return Result(finished, out.toString())
    }

    /**
     * 读日志。先试 root（能拿到 system_server 的行），失败再退回本进程。
     * `rooted=false` 时输出里必须写清楚 —— 否则用户发来的包看着"干净"，
     * 反而会让人误判成"模块没跑"。
     */
    private fun rawLog(): Raw {
        val cmd = "logcat -d -v time -t $DUMP_LINES"
        val rooted = run(arrayOf("su", "-c", cmd))
        if (rooted.ok && rooted.text.isNotBlank()) {
            return Raw(rooted.text, rooted = true, note = null)
        }
        val plain = run(arrayOf("logcat", "-d", "-v", "time", "-t", "$DUMP_LINES"))
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
    internal fun keepLine(line: String): Boolean = KEEP_TAGS.any { line.contains(it) }

    private fun filtered(raw: Raw): List<String> =
        raw.text.lineSequence()
            .filter { keepLine(it) }
            .toList()
            .takeLast(MAX_LOG_LINES)

    private fun global(context: Context, key: String): String =
        runCatching {
            Settings.Global.getString(context.contentResolver, key) ?: ""
        }.getOrDefault("")

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

    /** 完整的反馈包文本 */
    fun compose(context: Context): String {
        val raw = rawLog()
        val log = filtered(raw)
        return buildString {
            append(environment(context))
            appendLine()
            appendLine("=== logcat (rooted=${raw.rooted}, kept=${log.size}) ===")
            if (raw.note != null) appendLine("NOTE ${raw.note}")
            if (log.isEmpty()) {
                appendLine("(没有匹配到 HyperZoomRing / LSPosed / Xposed 的行)")
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