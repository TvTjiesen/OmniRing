package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ui.ThemeMode
import xyz.nextalone.hyperzoomring.ui.themeModeLabel

/** 预设主题色（只在 Material You 三档下生效）；0 表示"跟随系统壁纸取色" */
internal val KeySwatches = listOf(
    0, 0xFF3E7BFA.toInt(), 0xFF12B76A.toInt(), 0xFF7C4DFF.toInt(),
    0xFFF97316.toInt(), 0xFFEF4444.toInt(), 0xFF06B6D4.toInt(), 0xFFFACC15.toInt(),
)

/**
 * 主题二级页。
 *
 * 原来这些全堆在设置页第一屏：六种模式 + 八个色块 + 五档色彩算法，
 * 一屏根本看不完，把真正的开关都挤下去了。iOS 的做法就是把它单独一页。
 */
@Composable
fun ThemeScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onThemeChanged: () -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var themeMode by remember(refreshTrigger) { mutableStateOf(config.themeMode) }
    var keyColor by remember(refreshTrigger) { mutableStateOf(config.themeKeyColor) }
    var colorSpec by remember(refreshTrigger) { mutableStateOf(config.themeColorSpec) }
    var paletteStyle by remember(refreshTrigger) { mutableStateOf(config.themePaletteStyle) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.theme_mode_group), top = true) }
        item {
            Card {
                Column {
                    (0 until ThemeMode.COUNT).forEach { index ->
                        RadioButtonPreference(
                            title = themeModeLabel(index),
                            selected = themeMode == index,
                            onClick = {
                                themeMode = index
                                config.themeMode = index
                                onThemeChanged()
                            },
                        )
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_key_color)) }
        item {
            Card {
                Text(
                    text = stringResource(R.string.settings_key_color_hint),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeySwatches.forEach { value ->
                        val selected = keyColor == value
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .then(
                                    if (value == 0) {
                                        Modifier.background(
                                            MiuixTheme.colorScheme.surfaceContainerHighest,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier.background(Color(value), CircleShape)
                                    }
                                )
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = CircleShape,
                                )
                                .clickable {
                                    keyColor = value
                                    config.themeKeyColor = value
                                    onThemeChanged()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (value == 0) {
                                Text(
                                    text = "A",
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_advanced)) }
        item {
            Card {
                Column {
                    RadioButtonPreference(
                        title = stringResource(R.string.spec_2021),
                        selected = colorSpec == 0,
                        onClick = { colorSpec = 0; config.themeColorSpec = 0; onThemeChanged() },
                    )
                    RadioButtonPreference(
                        title = stringResource(R.string.spec_2025),
                        selected = colorSpec == 1,
                        onClick = { colorSpec = 1; config.themeColorSpec = 1; onThemeChanged() },
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.palette_group)) }
        item {
            Card {
                Column {
                    RadioButtonPreference(
                        title = stringResource(R.string.palette_content),
                        selected = paletteStyle == 0,
                        onClick = { paletteStyle = 0; config.themePaletteStyle = 0; onThemeChanged() },
                    )
                    RadioButtonPreference(
                        title = stringResource(R.string.palette_neutral),
                        selected = paletteStyle == 1,
                        onClick = { paletteStyle = 1; config.themePaletteStyle = 1; onThemeChanged() },
                    )
                    RadioButtonPreference(
                        title = stringResource(R.string.palette_vivid),
                        selected = paletteStyle == 2,
                        onClick = { paletteStyle = 2; config.themePaletteStyle = 2; onThemeChanged() },
                    )
                }
            }
        }
    }
}
