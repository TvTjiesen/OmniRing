package xyz.nextalone.hyperzoomring.ui.screen
import xyz.nextalone.hyperzoomring.ui.label

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import xyz.nextalone.hyperzoomring.R
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType

/** 单个场景下的四个手势 */
@Composable
fun SceneGestureOverviewScreen(
    scene: SceneType,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onGestureClick: (GestureType) -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.gesture_global), top = true) }
        if (scene == SceneType.FULLSCREEN) {
            item {
                Text(
                    stringResource(R.string.scene_fullscreen_hint),
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        items(GestureType.entries.toList(), key = { it.name }) { gesture ->
            val actionId = config.getSceneActionId(scene, gesture)
            val actionName = actionId?.let { ActionRegistry.get(it)?.label() } ?: stringResource(R.string.app_default_action)
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
