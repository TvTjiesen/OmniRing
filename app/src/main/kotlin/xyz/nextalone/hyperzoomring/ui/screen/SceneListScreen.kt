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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType

/** 「场景」页：媒体播放 / 手电筒 / 全屏 三个场景入口 */
@Composable
fun SceneListScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onSceneClick: (SceneType) -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.scene_config), top = true) }
        items(SceneType.entries.toList(), key = { it.name }) { scene ->
            val configuredCount = GestureType.entries.count { gesture ->
                config.getSceneActionId(scene, gesture) != null
            }
            Card(modifier = Modifier.padding(bottom = 8.dp)) {
                ArrowPreference(
                    title = scene.label(),
                    summary = stringResource(R.string.scene_gestures, configuredCount),
                    onClick = { onSceneClick(scene) },
                )
            }
        }
    }
}
