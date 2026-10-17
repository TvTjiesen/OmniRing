package xyz.nextalone.hyperzoomring.ui.screen

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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.GestureType

/** 「应用」页：已配置的应用列表 + 添加入口 */
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
    onAppClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val pm = context.packageManager
    var configuredPackages by remember { mutableStateOf(config.getConfiguredPackages()) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AppPickerDialog(
            excludePackages = configuredPackages,
            onSelect = { pkg ->
                config.addConfiguredPackage(pkg)
                configuredPackages = config.getConfiguredPackages()
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.app_config), top = true) }
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.app_add),
                    summary = stringResource(R.string.app_add_hint),
                    onClick = { showPicker = true },
                )
            }
        }
        if (configuredPackages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.app_empty),
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        } else {
            item { SectionTitle(stringResource(R.string.app_configured)) }
            items(configuredPackages.sorted().toList(), key = { it }) { pkg ->
                val appLabel = remember(pkg) { appLabel(pm, pkg) }
                val configuredCount = GestureType.entries.count { gesture ->
                    config.getAppActionId(pkg, gesture) != null
                }
                Card(modifier = Modifier.padding(bottom = 8.dp)) {
                    ArrowPreference(
                        title = appLabel,
                        summary = stringResource(R.string.app_count_line, pkg, configuredCount),
                        onClick = { onAppClick(pkg) },
                    )
                }
            }
        }
    }
}

/** 取应用显示名，取不到就退回包名 */
internal fun appLabel(pm: android.content.pm.PackageManager, pkg: String): String = try {
    @Suppress("DEPRECATION")
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}
