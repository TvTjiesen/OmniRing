package xyz.nextalone.hyperzoomring.ui.screen

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.util.Compatibility
import xyz.nextalone.hyperzoomring.util.DeviceInfo
import xyz.nextalone.hyperzoomring.util.Heartbeat
import xyz.nextalone.hyperzoomring.util.ScopeReload
import xyz.nextalone.hyperzoomring.util.StatusJudge

/**
 * 设置页的「本机与适配」卡：机型、当前系统版本、适配程度，外加每一项的依据。
 *
 * 设计上的三条原则（2026-10-17 他让我自己设计，我按这三条定的）：
 *  1. **数字全部现读**，界面里不许写死"HyperOS 3 / Android 16"这种话 —— 我之前就是这么写错的，
 *     机型和系统一律走 [DeviceInfo] 从 `getprop` 取，SDK/版本取 `Build`；
 *  2. **适配程度不是一个凭感觉的百分比**，它是 [Compatibility.grade] 对三条硬项（机型 / 系统 /
 *     模块加载）的判定，每条后面都跟着它的证据，用户能自己核对；
 *  3. **环数据只当证据不当门槛** —— 他十分钟没转环，样本数停在老地方是正常的，
 *     真正的坏信号是"开机到现在一个有效样本都没进来"。
 *
 * 数据每 2 秒读一次，读的操作（包管理器签名是 binder + 摘要）搬到 IO 线程，
 * 免得把主线程拖成"按下去没反应"（这个坑首页那张卡踩过，口径保持一致）。
 */
@Composable
fun AdaptationCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<Compatibility.Report?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { gradeNow(context) }
            report = r
            delay(2000)
        }
    }

    val accent = MiuixTheme.colorScheme.primary
    val errorColor = MiuixTheme.colorScheme.error
    val gray = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val rep = report

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // 第一行：适配程度 + 通过几项
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(levelDot(rep?.level, accent, errorColor, gray), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(levelTitleOf(rep?.level)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (rep?.level == Compatibility.Level.FULL) MiuixTheme.colorScheme.onSurface
                    else levelDot(rep?.level, accent, errorColor, gray),
                    modifier = Modifier.weight(1f),
                )
                if (rep != null) {
                    Text(
                        text = stringResource(R.string.compat_score, rep.passed, rep.total),
                        fontSize = 12.sp,
                        color = gray,
                    )
                }
            }

            // 机型 / 系统：纯事实，现读
            InfoRow(stringResource(R.string.compat_check_device), DeviceInfo.modelLine)
            InfoRow(stringResource(R.string.compat_check_os), DeviceInfo.osLine)
            InfoRow(
                stringResource(R.string.compat_check_path),
                stringResource(R.string.compat_path_sensor),
            )

            // 后面两条带状态点，因为它们是会变的"当前状况"
            rep?.items?.firstOrNull { it.check == Compatibility.Check.LOADED }?.let { item ->
                InfoRow(
                    stringResource(R.string.compat_check_loaded),
                    stringResource(loadedTextOf(item.state)),
                    stateColor(item.state, accent, errorColor, gray),
                )
            }
            rep?.items?.firstOrNull { it.check == Compatibility.Check.RING }?.let { item ->
                val n = item.value.toLongOrNull() ?: 0L
                InfoRow(
                    stringResource(R.string.compat_check_ring),
                    if (n > 0L) stringResource(R.string.compat_ring_seen, n)
                    else stringResource(R.string.compat_ring_none),
                    stateColor(item.state, accent, errorColor, gray),
                )
            }
        }
    }
}

/** 读一遍真实状态并判定。放在这儿是为了让 IO 线程里做的事一眼可见。 */
private fun gradeNow(context: Context): Compatibility.Report {
    val sig = runCatching { ScopeReload.installedSignature(context) }.getOrNull()
    val verdict = StatusJudge.judge(
        heartbeat = Heartbeat.parse(
            runCatching { Settings.Global.getString(context.contentResolver, Heartbeat.KEY) }.getOrNull(),
        ),
        installedSig = sig,
        enabled = ConfigManager.fromContext(context).isEnabled,
    )
    val samples = verdict.snapshot?.samples ?: 0L
    return Compatibility.grade(
        supportedDevice = DeviceInfo.isSupportedDevice,
        modelText = DeviceInfo.modelLine,
        osText = DeviceInfo.osLine,
        sdk = DeviceInfo.sdkInt,
        verdict = verdict.verdict,
        ringSamples = samples,
    )
}

@Composable
private fun InfoRow(label: String, value: String, dot: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp)) {
            if (dot != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(dot, CircleShape),
                )
            }
        }
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (dot == null) 0.dp else 15.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun levelDot(level: Compatibility.Level?, accent: Color, error: Color, gray: Color): Color = when (level) {
    Compatibility.Level.FULL -> accent
    Compatibility.Level.SETUP -> error
    Compatibility.Level.PARTIAL -> gray
    Compatibility.Level.UNSUPPORTED -> error
    null -> gray
}

private fun stateColor(state: Compatibility.State?, accent: Color, error: Color, gray: Color): Color = when (state) {
    Compatibility.State.PASS -> accent
    Compatibility.State.FAIL -> error
    else -> gray
}

private fun levelTitleOf(level: Compatibility.Level?): Int = when (level) {
    Compatibility.Level.FULL -> R.string.compat_level_full
    Compatibility.Level.SETUP -> R.string.compat_level_setup
    Compatibility.Level.PARTIAL -> R.string.compat_level_partial
    Compatibility.Level.UNSUPPORTED -> R.string.compat_level_unsupported
    null -> R.string.compat_level_checking
}

private fun loadedTextOf(state: Compatibility.State?): Int = when (state) {
    Compatibility.State.PASS -> R.string.compat_loaded_ok
    Compatibility.State.WARN -> R.string.compat_loaded_pending
    else -> R.string.compat_loaded_no
}

/** 心跳里 lastSampleAt 用的是 elapsedRealtime，这里只是留着给以后显示"多久以前"用 */
@Suppress("unused")
private fun ageOf(lastSampleAt: Long): Long =
    ((SystemClock.elapsedRealtime() - lastSampleAt) / 1000L).coerceAtLeast(0L)
