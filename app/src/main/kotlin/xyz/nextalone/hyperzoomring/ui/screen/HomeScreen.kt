package xyz.nextalone.hyperzoomring.ui.screen
import xyz.nextalone.hyperzoomring.ui.label

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import xyz.nextalone.hyperzoomring.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.DispatchMode
import xyz.nextalone.hyperzoomring.ui.Screen

/**
 * 首页：模块总开关、分发模式、相机覆盖、进度环三件套、速度阈值。
 * 手势配置已经挪到底栏「手势」那一页了，这里不再重复一份。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onNavigate: (Screen) -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var isEnabled by remember(refreshTrigger) { mutableStateOf(config.isEnabled) }
    var dispatchMode by remember(refreshTrigger) { mutableStateOf(config.dispatchMode) }
    var overrideCamera by remember(refreshTrigger) { mutableStateOf(config.overrideCamera) }
    var mediaSeek by remember(refreshTrigger) { mutableStateOf(config.mediaSeekEnabled) }
    var seekHud by remember(refreshTrigger) { mutableStateOf(config.seekHudEnabled) }
    var seekMsPerUnit by remember(refreshTrigger) { mutableStateOf(config.seekMsPerUnit.toFloat()) }
    var speedThreshold by remember(refreshTrigger) { mutableStateOf(config.speedThreshold.toFloat()) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        // 第一屏第一张卡就是"到底生效了没有"：
        // 装完模块最常见的困惑是"转了没反应"，而原因有五六种，不该让人去猜
        item { SectionTitle(stringResource(R.string.status_title), top = true) }
        item {
            StatusCard(
                refreshTrigger = refreshTrigger,
                onClick = { onNavigate(Screen.Diagnostic) },
            )
        }

        item { SectionTitle(stringResource(R.string.home_module_state)) }
        item {
            Card {
                SwitchPreference(
                    checked = isEnabled,
                    onCheckedChange = { isEnabled = it; config.isEnabled = it },
                    title = stringResource(R.string.home_enable),
                    summary = stringResource(if (isEnabled) R.string.home_enable_on else R.string.home_enable_off),
                )
            }
        }

        item { SectionTitle(stringResource(R.string.home_mode)) }
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.home_dispatch),
                    summary = dispatchMode.label(),
                    onClick = { onNavigate(Screen.ModeConfig) },
                )
            }
        }

        item { SectionTitle(stringResource(R.string.home_camera)) }
        item {
            Card {
                SwitchPreference(
                    checked = overrideCamera,
                    onCheckedChange = { overrideCamera = it; config.overrideCamera = it },
                    title = stringResource(R.string.home_override_camera),
                    summary = stringResource(if (overrideCamera) R.string.home_override_on else R.string.home_override_off),
                )
            }
        }

        item { SectionTitle(stringResource(R.string.home_seek)) }
        item {
            Card {
                SwitchPreference(
                    checked = mediaSeek,
                    onCheckedChange = { mediaSeek = it; config.mediaSeekEnabled = it },
                    title = stringResource(R.string.home_seek_switch),
                    summary = if (mediaSeek)
                        stringResource(R.string.home_seek_switch_on)
                    else
                        stringResource(R.string.home_seek_switch_off),
                )
                SwitchPreference(
                    checked = seekHud,
                    onCheckedChange = { seekHud = it; config.seekHudEnabled = it },
                    title = stringResource(R.string.home_seek_hud),
                    summary = if (seekHud) stringResource(R.string.home_seek_hud_on) else stringResource(R.string.home_seek_hud_off),
                )
                SliderPreference(
                    value = seekMsPerUnit,
                    onValueChange = {
                        seekMsPerUnit = it
                        config.seekMsPerUnit = it.toInt()
                    },
                    title = stringResource(R.string.home_seek_sensitivity),
                    summary = stringResource(R.string.home_seek_sensitivity_hint),
                    valueText = "${seekMsPerUnit.toInt()}ms",
                    valueRange = 2f..60f,
                    steps = 57,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.home_threshold)) }
        item {
            Card {
                SliderPreference(
                    value = speedThreshold,
                    onValueChange = {
                        speedThreshold = it
                        config.speedThreshold = it.toInt()
                    },
                    title = stringResource(R.string.home_threshold_title),
                    summary = stringResource(R.string.home_threshold_hint),
                    valueText = "${speedThreshold.toInt()}",
                    valueRange = 2f..15f,
                    steps = 12,
                )
            }
        }
    }
}
