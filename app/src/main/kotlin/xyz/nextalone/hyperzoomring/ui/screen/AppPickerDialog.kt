package xyz.nextalone.hyperzoomring.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AppInfo(val packageName: String, val label: String)

/** 选应用：搜索框 + 列表。排除掉已经配过的，避免重复添加 */
@Composable
fun AppPickerDialog(
    excludePackages: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val apps = remember {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg in excludePackages) return@mapNotNull null
                AppInfo(pkg, resolveInfo.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val filtered = remember(searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
        ) {
            Column(Modifier.padding(16.dp)) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.app_picker_search),
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered) { app ->
                        ArrowPreference(
                            title = app.label,
                            summary = app.packageName,
                            onClick = { onSelect(app.packageName) },
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.app_picker_empty),
                                modifier = Modifier.padding(20.dp),
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
