package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager

/** 语言代码 -> 界面标签。设置页那一行要显示"当前是哪一个"，所以抽在这里两边共用。 */
@Composable
fun languageOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.lang_system),
    "zh" to "简体中文",
    "zh-Hant" to "繁體中文",
    "en" to "English",
    "ru" to "Русский",
)

@Composable
fun languageLabel(code: String): String =
    languageOptions().firstOrNull { it.first == code }?.second ?: stringResource(R.string.lang_system)

/**
 * 「语言」二级页。
 *
 * 为什么从设置首页挪出来（他 2026-10-16 提的）：五个选项各占一行，直接把"重启作用域、
 * 诊断、致谢"这些真正要用的东西挤到第二屏去了；语言是装机那天调一次的就该收起来。
 * 首页只留一行"语言 / 当前值"，点进来才铺开。
 */
@Composable
fun LanguageScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onLanguageChanged: () -> Unit = {},
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var language by remember(refreshTrigger) { mutableStateOf(config.language) }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item {
            Card {
                Column {
                    languageOptions().forEach { (code, label) ->
                        RadioButtonPreference(
                            title = label,
                            selected = language == code,
                            onClick = {
                                language = code
                                config.language = code
                                // 资源语言是在 attachBaseContext 里定的，只能重建 Activity
                                onLanguageChanged()
                            },
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.lang_note),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
    }
}
