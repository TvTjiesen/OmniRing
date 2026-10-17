package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.media.MediaSessionBridge

// --- 上一曲 / 下一曲 ---

object MediaPreviousAction : Action {
    override val id = "media_previous"
    override val displayName = "上一曲"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}

object MediaNextAction : Action {
    override val id = "media_next"
    override val displayName = "下一曲"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

// --- 快进 / 快退 ---

object MediaFastForwardAction : Action {
    override val id = "media_fast_forward"
    override val displayName = "快进"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }
}

object MediaRewindAction : Action {
    override val id = "media_rewind"
    override val displayName = "快退"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_REWIND)
    }
}

// --- 前进 / 后退 10 秒 ---

object MediaSeekForwardAction : Action {
    override val id = "media_seek_forward"
    override val displayName = "前进 10 秒"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        if (!MediaHelper.seekByOffset(context, 10_000L)) {
            MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD)
        }
    }
}

object MediaSeekBackwardAction : Action {
    override val id = "media_seek_backward"
    override val displayName = "后退 10 秒"
    override val sceneOnly = SceneType.MEDIA_PLAYING

    override fun execute(context: Context, intensity: Float) {
        if (!MediaHelper.seekByOffset(context, -10_000L)) {
            MediaHelper.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD)
        }
    }
}

internal object MediaHelper {
    private const val TAG = "HyperZoomRing"

    /** 一次播放状态快照：位置 + 总时长 + 控制器归属，用于表冠求对的绝对定位 */
    class Snapshot(val positionMs: Long, val durationMs: Long)

    fun dispatchMediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * 相对 seek：先读当前位置，再走绝对定位。
     * 返回 false = 没有可控会话，调用方可以回落到媒体键/拖进度条。
     */
    fun seekByOffset(context: Context, offsetMs: Long): Boolean {
        val s = MediaSessionBridge.pick() ?: return false
        return MediaSessionBridge.seekTo(s, s.positionMs + offsetMs)
    }

    /** 表冠求对要用绝对位置：基准在会话开始时拍一次，之后只追绝对目标 */
    fun snapshot(context: Context): Snapshot? {
        val s = MediaSessionBridge.pick() ?: return null
        return Snapshot(s.positionMs, s.durationMs)
    }

    fun seekToAbsolute(context: Context, positionMs: Long): Boolean {
        val s = MediaSessionBridge.pick() ?: return false
        return MediaSessionBridge.seekTo(s, positionMs)
    }

    /** 探针用：说清楚我们到底看到了什么会话 */
    fun describeSession(context: Context): String = MediaSessionBridge.describe()
}
