package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.provider.Settings
import xyz.nextalone.hyperzoomring.action.Action

object BrightnessUpAction : Action {
    override val id = "brightness_up"
    override val displayName = "亮度增大"

    override fun execute(context: Context, intensity: Float) {
        adjustBrightness(context, intensity, up = true)
    }
}

object BrightnessDownAction : Action {
    override val id = "brightness_down"
    override val displayName = "亮度减小"

    override fun execute(context: Context, intensity: Float) {
        adjustBrightness(context, intensity, up = false)
    }
}

private fun adjustBrightness(context: Context, intensity: Float, up: Boolean) {
    val resolver = context.contentResolver
    Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )
    val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    val step = (intensity * 25).toInt().coerceAtLeast(5)
    val newBrightness = if (up) (current + step).coerceAtMost(255) else (current - step).coerceAtLeast(1)
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, newBrightness)
}
