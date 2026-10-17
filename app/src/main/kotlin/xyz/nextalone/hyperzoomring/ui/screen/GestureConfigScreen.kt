package xyz.nextalone.hyperzoomring.ui.screen
import xyz.nextalone.hyperzoomring.ui.label
import xyz.nextalone.hyperzoomring.ui.labelSummary

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
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.ring.GestureType

/** 某个手势槽位选动作 */
@Composable
fun GestureConfigScreen(
    gestureType: GestureType,
    scope: ConfigScope = ConfigScope.Global,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = pagePaddingDefault(),
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    val pm = context.packageManager

    var selectedActionId by remember {
        mutableStateOf(
            when (scope) {
                is ConfigScope.Global -> config.getActionId(gestureType)
                is ConfigScope.App -> config.getAppActionId(scope.packageName, gestureType)
                is ConfigScope.Scene -> config.getSceneActionId(scope.scene, gestureType)
            }
        )
    }
    var launchAppPackage by remember {
        mutableStateOf(
            when (scope) {
                is ConfigScope.Global -> config.getActionConfig(gestureType)
                is ConfigScope.App -> config.getAppActionConfig(scope.packageName, gestureType)
                is ConfigScope.Scene -> config.getSceneActionConfig(scope.scene, gestureType)
            }
        )
    }
    var showAppPicker by remember { mutableStateOf(false) }
    val actions = remember { ActionRegistry.allForScope(scope) }
    // "无动作"排在最前面，和动作一起按每 5 行切成多张卡（见下面 Page 里的注释）。
    // 必须在 Page 外面算：content lambda 的接收者是 LazyListScope，不是 @Composable 上下文。
    val allRows = remember(actions) { listOf<Action?>(null) + actions }

    fun saveAction(actionId: String?) {
        when (scope) {
            is ConfigScope.Global -> config.setActionId(gestureType, actionId)
            is ConfigScope.App -> config.setAppActionId(scope.packageName, gestureType, actionId)
            is ConfigScope.Scene -> config.setSceneActionId(scope.scene, gestureType, actionId)
        }
        selectedActionId = actionId
    }

    fun saveConfig(configValue: String?) {
        when (scope) {
            is ConfigScope.Global -> config.setActionConfig(gestureType, configValue)
            is ConfigScope.App -> config.setAppActionConfig(scope.packageName, gestureType, configValue)
            is ConfigScope.Scene -> config.setSceneActionConfig(scope.scene, gestureType, configValue)
        }
        launchAppPackage = configValue
    }

    if (showAppPicker) {
        AppPickerDialog(
            excludePackages = emptySet(),
            onSelect = { pkg ->
                saveAction(LaunchAppAction.id)
                saveConfig(pkg)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }

    Page(pagePadding = pagePadding, modifier = modifier) {
        item { SectionTitle(stringResource(R.string.gesture_pick_section), top = true) }
        // 分成多张卡，每张最多 ROWS_PER_CARD 行。
        // 原来 18 行塞在一张卡里 = 一个比屏幕还高的 LazyColumn 条目，
        // 而单个条目超出视口时列表**没有滚动区间**，整页就滚不动（真机实测），
        // 下面的"向上/向下滚动""视频进度"全都够不着。
        allRows.chunked(ROWS_PER_CARD).forEachIndexed { group, chunk ->
            item(key = "action_group_$group") {
                Card {
                    chunk.forEach { action ->
                        if (action == null) {
                            RadioButtonPreference(
                                title = stringResource(R.string.gesture_none),
                                selected = selectedActionId == null,
                                onClick = { saveAction(null); saveConfig(null) },
                            )
                        } else if (action is LaunchAppAction) {
                            val label = launchAppPackage?.let { pkg -> appLabel(pm, pkg) }
                            RadioButtonPreference(
                                title = action.label(),
                                summary = if (selectedActionId == action.id && label != null) {
                                    label
                                } else {
                                    stringResource(R.string.app_add_hint)
                                },
                                selected = selectedActionId == action.id,
                                onClick = { showAppPicker = true },
                            )
                        } else {
                            RadioButtonPreference(
                                title = action.label(),
                                summary = action.labelSummary(),
                                selected = selectedActionId == action.id,
                                onClick = { saveAction(action.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一张卡最多几行：保证任何一张卡都在一屏之内，列表才有滚动区间 */
private const val ROWS_PER_CARD = 5
