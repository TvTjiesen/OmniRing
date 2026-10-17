package xyz.nextalone.hyperzoomring.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.TextButton
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.util.Heartbeat
import xyz.nextalone.hyperzoomring.util.ScopeReload
import xyz.nextalone.hyperzoomring.util.StatusJudge
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.hook.InputInterceptorHook
import xyz.nextalone.hyperzoomring.ring.ZoomRingEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_EVENTS = 200

/** 诊断日志的一行：一次"转环 → 分发"的结果 */
private data class DiagRow(val ts: Long, val gesture: String, val action: String, val ok: Boolean)

/**
 * 诊断页。注意：HyperOS 3 上事件总数不刷新（上游诊断页依赖的输入回调已经不触发），
 * 所以这里同时显示传感器侧的活跃度，别让他以为"没数字=没运行"。
 */
@Composable
fun DiagnosticScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
) {
    val events = remember { mutableStateListOf<ZoomRingEvent>() }
    // 诊断页真正要看的是"这一次转环被分给了哪个动作、注入成没成功"，
    // 光有事件计数说明不了问题出在哪一层
    val rows = remember { mutableStateListOf<DiagRow>() }
    var lastGesture by remember { mutableStateOf("—") }
    var lastIntensity by remember { mutableFloatStateOf(0f) }
    var lastAction by remember { mutableStateOf("—") }
    var heartbeat by remember { mutableStateOf<Heartbeat.Snapshot?>(null) }
    var lastOk by remember { mutableStateOf(true) }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                events.add(
                    ZoomRingEvent(
                        timestampMs = intent.getLongExtra(InputInterceptorHook.EXTRA_TIMESTAMP, 0),
                        value = intent.getIntExtra(InputInterceptorHook.EXTRA_VALUE, 0),
                        direction = intent.getIntExtra(InputInterceptorHook.EXTRA_DIRECTION, 0),
                    )
                )
                lastGesture = intent.getStringExtra(InputInterceptorHook.EXTRA_GESTURE) ?: ""
                lastIntensity = intent.getFloatExtra(InputInterceptorHook.EXTRA_INTENSITY, 0f)
                lastAction = intent.getStringExtra(InputInterceptorHook.EXTRA_ACTION_ID) ?: "—"
                lastOk = intent.getBooleanExtra(InputInterceptorHook.EXTRA_INJECT_OK, true)
                rows.add(DiagRow(System.currentTimeMillis(), lastGesture, lastAction, lastOk))
                while (events.size > MAX_EVENTS) events.removeAt(0)
                while (rows.size > MAX_EVENTS) rows.removeAt(0)
            }
        }
        // 广播来自 system_server（另一个进程），Android 14+ 必须显式声明 EXPORTED，
        // 否则 registerReceiver 直接抛 SecurityException
        context.registerReceiver(receiver, IntentFilter(InputInterceptorHook.ACTION_ZOOM_RING_EVENT), Context.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Page(pagePadding = pagePadding, modifier = modifier) {
        // 大标题已经是「诊断信息」，这里不再重复一个分组标题
        item {
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_count))
                    // 以前这里显示的是 events.size —— 那个列表只装"人站在诊断页上时
                    // 收到的广播"，一翻页就重建清空，所以他看到的一直是 0，
                    // 而 system_server 里其实已经分发过几千次（心跳的 ok/fail 就是证据）。
                    Text(
                        heartbeat?.let { (it.ok + it.fail).toString() }
                            ?: stringResource(R.string.diag_hb_none),
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_count_live))
                    Text("${events.size}", color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_last))
                    Text(
                        if (events.isNotEmpty()) "${events.last().value}" else "—",
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_gesture))
                    Text(lastGesture, color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_action))
                    Text(
                        lastAction,
                        color = if (lastOk) MiuixTheme.colorScheme.onBackgroundVariant
                        else MiuixTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diag_intensity))
                    Text("%.2f".format(lastIntensity), color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
            }
        }

        // 一条广播都没收到时，这一页也不能是空的：先给出"系统里到底有没有在跑"的证据
        item { SectionTitle(stringResource(R.string.diag_evidence)) }
        item {
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(tick) {
                while (true) {
                    heartbeat = Heartbeat.parse(
                        runCatching {
                            android.provider.Settings.Global.getString(
                                context.contentResolver, Heartbeat.KEY
                            )
                        }.getOrNull()
                    )
                    delay(2000)
                }
            }
            Card {
                Column {
                    val hb = heartbeat
                    InfoRow(
                        label = stringResource(R.string.diag_hb_samples),
                        value = hb?.samples?.toString() ?: stringResource(R.string.diag_hb_none),
                    )
                    InfoRow(
                        label = stringResource(R.string.diag_hb_gesture),
                        value = hb?.lastGesture?.ifEmpty { "—" } ?: "—",
                    )
                    InfoRow(
                        label = stringResource(R.string.diag_hb_action),
                        value = hb?.lastAction?.ifEmpty { "—" } ?: "—",
                    )
                    InfoRow(
                        label = stringResource(R.string.diag_hb_result),
                        value = if (hb == null) "—" else "${hb.ok} / ${hb.fail}",
                    )
                    InfoRow(
                        label = stringResource(R.string.diag_hb_age),
                        value = if (hb == null || hb.lastSampleAt == 0L) {
                            stringResource(R.string.status_line_age_never)
                        } else {
                            stringResource(
                                R.string.status_line_age,
                                StatusJudge.ageSeconds(hb.lastSampleAt, android.os.SystemClock.uptimeMillis()),
                            )
                        },
                    )
                    InfoRow(
                        label = stringResource(R.string.diag_hb_version),
                        value = stringResource(
                            if (hb != null && hb.signature == ScopeReload.installedSignature(context))
                                R.string.status_line_version_ok
                            else R.string.status_line_version_diff
                        ),
                        lastLine = true,
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.diag_selftest),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.diag_selftest_btn),
                        onClick = {
                            // 让 system_server 里的钩子自己滚一下：动了就说明注入链路是通的
                            runCatching {
                                android.provider.Settings.Global.putInt(
                                    context.contentResolver, "hzr_probe", 32
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.diag_log)) }
        items(rows.toList().reversed(), key = { it.ts.toString() + it.action }) { r ->
            val tf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
            Text(
                "${tf.format(Date(r.ts))} | ${r.gesture} -> ${r.action} | " +
                    stringResource(if (r.ok) R.string.diag_ok else R.string.diag_fail),
                fontSize = 12.sp,
                color = if (r.ok) MiuixTheme.colorScheme.onBackgroundVariant
                else MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
    }
}
