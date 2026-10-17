package xyz.nextalone.hyperzoomring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.DispatchMode
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.ring.GestureType

/**
 * 枚举的显示名一律走资源。
 *
 * 枚举本身（GestureType / DispatchMode / SceneType）被 hook 侧和配置键名共用，
 * 里面那个 displayName 是硬编码中文 —— 界面如果直接用它，切英文就会漏翻
 * （装机截图就抓到了 "全局模式" 混在英文界面里）。所以界面统一从这里取。
 */
@Composable
fun GestureType.label(): String = stringResource(
    when (this) {
        GestureType.CW_SLOW -> R.string.gesture_cw_slow
        GestureType.CW_FAST -> R.string.gesture_cw_fast
        GestureType.CCW_SLOW -> R.string.gesture_ccw_slow
        GestureType.CCW_FAST -> R.string.gesture_ccw_fast
    }
)

@Composable
fun DispatchMode.label(): String = stringResource(
    when (this) {
        DispatchMode.GLOBAL -> R.string.mode_global
        DispatchMode.PER_APP -> R.string.mode_per_app
        DispatchMode.PER_SCENE -> R.string.mode_per_scene
    }
)

@Composable
fun SceneType.label(): String = stringResource(    when (this) {
        SceneType.MEDIA_PLAYING -> R.string.scene_media
        SceneType.FLASHLIGHT_ON -> R.string.scene_flashlight
        SceneType.FULLSCREEN -> R.string.scene_fullscreen
    }
)

/**
 * 动作名。未知 id 直接原样显示（配置里可能残留已被删除的动作 id，
 * 翻译反而会把人误导），所以 else 分支不能进 stringResource。
 */
/**
 * 主题模式名。[ThemeMode] 里那份 labels 已删：它是硬编码中文，
 * 界面切英文时会整条漏翻。
 */
@Composable
fun themeModeLabel(mode: Int): String = stringResource(
    when (mode) {
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.MONET_SYSTEM -> R.string.theme_monet_system
        ThemeMode.MONET_LIGHT -> R.string.theme_monet_light
        ThemeMode.MONET_DARK -> R.string.theme_monet_dark
        else -> R.string.theme_system
    }
)

/**
 * 动作的一行说明。只给"光看标题猜不到行为"的那些：滚动跟着表冠转向走、
 * 进度类要播放器配合，不写清楚用户就只能靠猜。
 */
@Composable
fun Action.labelSummary(): String? {
    val res = when (id) {
        "scroll" -> R.string.act_scroll_sum
        "video_seek" -> R.string.act_video_seek_sum
        "video_scrub_drag" -> R.string.act_scrub_drag_sum
        else -> null
    }
    return res?.let { stringResource(it) }
}

@Composable
fun Action.label(): String {
    val res = when (id) {
        "volume_up" -> R.string.act_volume_up
        "volume_down" -> R.string.act_volume_down
        "brightness_up" -> R.string.act_brightness_up
        "brightness_down" -> R.string.act_brightness_down
        "launch_app" -> R.string.act_launch_app
        "flashlight_brightness_up" -> R.string.act_flashlight_up
        "flashlight_brightness_down" -> R.string.act_flashlight_down
        "media_previous" -> R.string.act_media_previous
        "media_next" -> R.string.act_media_next
        "media_fast_forward" -> R.string.act_media_ff
        "media_rewind" -> R.string.act_media_rew
        "media_seek_forward" -> R.string.act_media_seek_fwd
        "media_seek_backward" -> R.string.act_media_seek_back
        "scroll" -> R.string.act_scroll
        "video_seek" -> R.string.act_video_seek
        "video_scrub_drag" -> R.string.act_scrub_drag
        else -> null
    }
    return res?.let { stringResource(it) } ?: id
}
