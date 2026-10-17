package xyz.nextalone.hyperzoomring.ui.screen
import xyz.nextalone.hyperzoomring.ui.label

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
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
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.DispatchMode

/** 分发模式：全局 / 按应用 / 按场景 */
@Composable
fun ModeConfigScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var selected by remember { mutableStateOf(config.dispatchMode) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.mode_select), top = true) }
        item {
            Card {
                DispatchMode.entries.forEach { mode ->
                    RadioButtonPreference(
                        title = mode.label(),
                        summary = when (mode) {
                            DispatchMode.GLOBAL -> stringResource(R.string.mode_global_hint)
                            DispatchMode.PER_APP -> stringResource(R.string.mode_app_hint)
                            DispatchMode.PER_SCENE -> stringResource(R.string.mode_scene_hint)
                        },
                        selected = selected == mode,
                        onClick = {
                            selected = mode
                            config.dispatchMode = mode
                        },
                    )
                }
            }
        }
    }
}
