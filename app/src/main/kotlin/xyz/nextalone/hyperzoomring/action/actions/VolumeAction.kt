package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.media.AudioManager
import xyz.nextalone.hyperzoomring.action.Action

object VolumeUpAction : Action {
    override val id = "volume_up"
    override val displayName = "音量增大"

    override fun execute(context: Context, intensity: Float) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }
}

object VolumeDownAction : Action {
    override val id = "volume_down"
    override val displayName = "音量减小"

    override fun execute(context: Context, intensity: Float) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }
}
