package xyz.nextalone.hyperzoomring.ui.screen

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ui.InteractiveCrown
import xyz.nextalone.hyperzoomring.ui.LocalOpenCrownStage
import xyz.nextalone.hyperzoomring.ui.Screen
import xyz.nextalone.hyperzoomring.ui.themeModeLabel
import xyz.nextalone.hyperzoomring.util.Links
import xyz.nextalone.hyperzoomring.util.openUrl

internal fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")

/**
 * 设置页。
 *
 * 顶部是一张"可交互的头图"（表冠能拧，拧完弹回），往下依次是：
 * 主题（二级页）、语言、本机与适配、关于（特点/技术路径/计划/链接）、致谢、诊断。
 * 外观那一堆单选从第一屏挪进「主题」页了 —— 它们只在装机那天调一次，
 * 不该把真正的开关挤下去；重启作用域也挪到了右上角悬浮按钮（见 RestartControl）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onNavigate: (Screen) -> Unit = {},
    onThemeChanged: () -> Unit = {},
    onLanguageChanged: () -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val openStage = LocalOpenCrownStage.current
    var language by remember(refreshTrigger) { mutableStateOf(config.language) }
    var showDisclaimer by remember { mutableStateOf(false) }

    if (showDisclaimer) {
        // 用本模块已经在用的 OverlayDialog，不引新组件：标题 + 全文 + 一个确定
        OverlayDialog(
        show = true,
        title = stringResource(R.string.disclaimer_title),
        summary = stringResource(R.string.disclaimer_body),
        onDismissRequest = { showDisclaimer = false },
        content = {
            TextButton(
            text = stringResource(R.string.confirm),
            onClick = { showDisclaimer = false },
            modifier = Modifier.fillMaxWidth(),
            )
        },
        )
    }

    Page(pagePadding = pagePadding, modifier = modifier, largeTitle = false) {
        // 头图：logo + 名字 + 版本 + 一句话说明
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 76.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InteractiveCrown(
                    onClick = { openStage?.invoke() },
                )
                Text(
                    text = stringResource(R.string.app_name_full),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                )
                Text(
                    text = stringResource(R.string.app_version_line, versionName(context)),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = stringResource(R.string.header_logo_hint),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_theme), top = true) }
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.settings_theme),
                    summary = themeModeLabel(config.themeMode),
                    onClick = { onNavigate(Screen.Theme) },
                )
            }
        }

        // 语言从"五个选项摊在首页"改成一行入口（他说太占地方）：
        // 首页要把重启作用域、诊断这些真正常用的东西留在第一屏。
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.settings_language),
                    summary = languageLabel(language),
                    onClick = { onNavigate(Screen.Language) },
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_tools)) }
        item {
            Card {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.settings_diagnostic),
                        summary = stringResource(R.string.settings_diagnostic_hint),
                        onClick = { onNavigate(Screen.Diagnostic) },
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.about_features)) }
        item {
            Card {
                Column(Modifier.padding(20.dp)) {
                    Bullet(stringResource(R.string.about_feature_scroll))
                    Bullet(stringResource(R.string.about_feature_seek))
                    Bullet(stringResource(R.string.about_feature_sensor))
                    Bullet(stringResource(R.string.about_feature_hot))
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_about)) }
        item {
            Card {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.app_github),
                        summary = Links.GITHUB_REPO.substringAfter("https://"),
                        onClick = { context.openUrl(Links.GITHUB_REPO) },
                    )
                    Hairline()
                    ArrowPreference(
                        title = stringResource(R.string.app_coolapk),
                        summary = Links.COOLAPK.substringAfter("https://"),
                        onClick = { context.openUrl(Links.COOLAPK) },
                    )
                    Hairline()
                    ArrowPreference(
                        title = stringResource(R.string.settings_credits),
                        summary = stringResource(R.string.settings_credits_hint),
                        onClick = { onNavigate(Screen.Credits) },
                    )
                }
            }
        }
        // 作者与免责：iOS 的页脚就是一行小灰字，不该再占一张大卡、更不该用红色标题
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_author),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(R.string.disclaimer_short),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 6.dp, start = 28.dp, end = 28.dp),
                )
                Text(
                    text = stringResource(R.string.disclaimer_more),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { showDisclaimer = true },
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = "· $text",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
