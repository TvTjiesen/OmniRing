package xyz.nextalone.hyperzoomring.ui.screen

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.util.Heartbeat
import xyz.nextalone.hyperzoomring.util.ScopeReload
import xyz.nextalone.hyperzoomring.util.StatusJudge

/**
 * 首页顶部的「到底生效了没有」卡。
 *
 * 判定逻辑在 [StatusJudge]（纯函数，有单测），这里只负责把它翻成人话：
 * 正常就一行绿字加一行小事实；不正常就只说**卡在哪一层 + 下一步做什么**。
 *
 * 数据来源是 system_server 里模块自己写的心跳（正面证据），不是"没报错就当好了"。
 * 2 秒读一次即可 —— 心跳本身也是 2 秒落一次，读更快没有意义。
 */
@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<StatusJudge.Result?>(null) }
    var filter by remember { mutableStateOf(StatusJudge.FilterState()) }
    var installedSig by remember { mutableStateOf<String?>(null) }
    var cfgState by remember { mutableStateOf<StatusJudge.CfgState?>(null) }

    LaunchedEffect(refreshTrigger) {
        while (true) {
            // 这一拍要读：包管理器签名（binder + 摘要）、两个 Settings.Global 键、
            // 一份 SharedPreferences。之前全在主线程上跑（LaunchedEffect 默认跟随
            // composition 的 dispatcher）—— 主线程被 binder 拖住就是掉帧和"按下去没反应"。
            // 现在只把"读"搬到 IO，判定和写状态留在主线程，语义一个字没改。
            val read = withContext(Dispatchers.IO) {
                // 注意：这里必须先落到局部变量。写成 `installedSig = 表达式` 当实参的话，
                // Kotlin 会把它当成 judge(installedSig = ...) 的具名参数，状态变量根本没被赋值
                // —— 结果版本那行永远显示"不一致"。这个坑我自己踩过一次。
                val sig = ScopeReload.installedSignature(context)
                val cfg = StatusJudge.parseCfgState(readCfgState(context))
                val raw = StatusJudge.judge(
                    heartbeat = Heartbeat.parse(readHeartbeat(context)),
                    installedSig = sig,
                    // 开关也每拍重读，别用建卡那一刻的快照
                    enabled = ConfigManager.fromContext(context).isEnabled,
                )
                Triple(sig, cfg, raw)
            }
            installedSig = read.first
            cfgState = read.second
            val raw = read.third
            filter = StatusJudge.step(filter, raw.verdict)
            val shown = filter.shown ?: raw.verdict
            result = raw.copy(verdict = shown)
            delay(2000)
        }
    }

    val r = result ?: return
    val ok = r.verdict == StatusJudge.Verdict.OK
    val accent = MiuixTheme.colorScheme.primary
    val errorColor = MiuixTheme.colorScheme.error
    val dot = when (r.verdict) {
        StatusJudge.Verdict.OK -> accent
        StatusJudge.Verdict.DISABLED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> errorColor
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dot, CircleShape)
                )
                Text(
                    text = stringResource(titleOf(r.verdict)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ok) MiuixTheme.colorScheme.onSurface else dot,
                )
            }
            if (!ok) {
                Text(
                    text = stringResource(howtoIntOf(r.verdict, android.os.Build.MODEL)),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            val snap = r.snapshot
            val age = if (snap != null) StatusJudge.ageSeconds(snap.lastSampleAt, android.os.SystemClock.uptimeMillis()) else -1L
            Text(
                text = listOf(
                    stringResource(
                        if (snap != null && snap.signature == installedSig) R.string.status_line_version_ok
                        else R.string.status_line_version_diff
                    ),
                    if (snap == null || snap.lastSampleAt == 0L) {
                        stringResource(R.string.status_line_age_never)
                    } else {
                        stringResource(R.string.status_line_age, age)
                    },
                    if (snap != null) {
                        stringResource(R.string.status_line_counts, snap.samples, snap.ok, snap.fail)
                    } else {
                        ""
                    },
                    // 配置同步：system_server 读不到我们的私有 prefs，改动靠推送。
                    // 这一行就是"推送到底到没到"的正面证据。
                    run {
                        val c = cfgState
                        when {
                            c == null -> ""
                            c.pushed > 0 -> stringResource(R.string.status_cfg_synced, c.pushed, c.keys)
                            else -> stringResource(R.string.status_cfg_pending)
                        }
                    },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f),
            )
        }
    }
}

private fun readHeartbeat(context: Context): String? = runCatching {
    Settings.Global.getString(context.contentResolver, Heartbeat.KEY)
}.getOrNull()

private fun readCfgState(context: Context): String? = runCatching {
    Settings.Global.getString(context.contentResolver, "hzr_cfg_state")
}.getOrNull()

private fun titleOf(v: StatusJudge.Verdict): Int = when (v) {
    StatusJudge.Verdict.OK -> R.string.status_ok
    StatusJudge.Verdict.NOT_LOADED -> R.string.status_not_loaded
    StatusJudge.Verdict.RELOAD_PENDING -> R.string.status_reload_pending
    StatusJudge.Verdict.DISABLED -> R.string.status_disabled
    StatusJudge.Verdict.NO_SAMPLES -> R.string.status_no_samples
    StatusJudge.Verdict.ALL_FAILED -> R.string.status_all_failed
}

/**
 * 「没收到环数据」在实测机型上不能说"属正常" —— 那等于把 bug 说成特性。
 * 所以这条按机型分文案，其余状态照旧。
 */
private fun howtoIntOf(v: StatusJudge.Verdict, model: String): Int = when (v) {
    StatusJudge.Verdict.NO_SAMPLES ->
        if (StatusJudge.isSupportedModel(model)) R.string.status_no_samples_howto_supported
        else R.string.status_no_samples_howto
    StatusJudge.Verdict.NOT_LOADED -> R.string.status_not_loaded_howto
    StatusJudge.Verdict.RELOAD_PENDING -> R.string.status_reload_pending_howto
    StatusJudge.Verdict.DISABLED -> R.string.status_disabled_howto
    StatusJudge.Verdict.ALL_FAILED -> R.string.status_all_failed_howto
    else -> R.string.status_ok
}

/** 未生效时的提示色：正常态用主色，异常才用警示色，别一上来就红彤彤 */
val StatusOkColor: Color = Color.Unspecified
