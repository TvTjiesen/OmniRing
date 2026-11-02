package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import xyz.nextalone.hyperzoomring.R

/**
 * 一次性提示：四槽位时代（快转/慢转分开配）的老配置已合并成"按转向分配"。
 *
 * 只提示、**不改配置**——旧键原样留着，读取时由 ConfigManager 的回落链兜住
 * （同一方向的快/慢两条不同时，取"慢转"那条）。详见
 * docs/CROWN-ACTION-V2-DESIGN.md §3；关掉时由调用方把 legacy_notice_shown 置位。
 */
@Composable
fun LegacyMergeDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.legacy_merge_title))
                Text(stringResource(R.string.legacy_merge_message))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = stringResource(R.string.legacy_merge_ok),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}