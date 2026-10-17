package xyz.nextalone.hyperzoomring.ui.screen
import xyz.nextalone.hyperzoomring.ui.label

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import xyz.nextalone.hyperzoomring.R
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.ring.GestureType

/**
 * 「手势」这一页：四个顺逆/快慢槽位的全局动作。
 * 以前这块埋在首页卡片里，现在底栏有独立入口，就不用再翻一层。
 */
@Composable
fun GestureListScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onGestureClick: (GestureType) -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val scope = remember { ConfigScope.Global }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.gesture_global), top = true) }
        items(GestureType.entries.toList(), key = { it.name }) { gesture ->
            val actionId = config.getActionId(gesture)
            val actionName = actionId?.let { ActionRegistry.get(it)?.label() } ?: stringResource(R.string.gesture_not_set)
            Card(modifier = Modifier.padding(bottom = 8.dp)) {
                ArrowPreference(
                    title = gesture.label(),
                    summary = stringResource(R.string.gesture_action_prefix, actionName),
                    onClick = { onGestureClick(gesture) },
                )
            }
        }
    }
}
