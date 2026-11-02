package xyz.nextalone.hyperzoomring.action

import xyz.nextalone.hyperzoomring.action.actions.BrightnessDownAction
import xyz.nextalone.hyperzoomring.action.actions.BrightnessUpAction
import xyz.nextalone.hyperzoomring.action.actions.FlashlightBrightnessDownAction
import xyz.nextalone.hyperzoomring.action.actions.FlashlightBrightnessUpAction
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.action.actions.MediaFastForwardAction
import xyz.nextalone.hyperzoomring.action.actions.MediaNextAction
import xyz.nextalone.hyperzoomring.action.actions.MediaScrubAction
import xyz.nextalone.hyperzoomring.action.actions.MediaPreviousAction
import xyz.nextalone.hyperzoomring.action.actions.MediaRewindAction
import xyz.nextalone.hyperzoomring.action.actions.MediaSeekBackwardAction
import xyz.nextalone.hyperzoomring.action.actions.MediaSeekForwardAction
import xyz.nextalone.hyperzoomring.action.actions.ScrollCrownAction
import xyz.nextalone.hyperzoomring.action.actions.ScrubDragAction
import xyz.nextalone.hyperzoomring.action.actions.VolumeDownAction
import xyz.nextalone.hyperzoomring.action.actions.VolumeUpAction
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType

object ActionRegistry {
    private val actions = mutableMapOf<String, Action>()

    init {
        register(VolumeUpAction)
        register(VolumeDownAction)
        register(BrightnessUpAction)
        register(BrightnessDownAction)
        register(LaunchAppAction)
        register(FlashlightBrightnessUpAction)
        register(FlashlightBrightnessDownAction)
        register(MediaPreviousAction)
        register(MediaNextAction)
        register(MediaFastForwardAction)
        register(MediaRewindAction)
        register(MediaSeekForwardAction)
        register(MediaSeekBackwardAction)
        register(ScrollCrownAction)
        register(MediaScrubAction)
        register(ScrubDragAction)
    }

    private fun register(action: Action) { actions[action.id] = action }
    fun get(id: String): Action? = actions[id]
    fun all(): List<Action> = actions.values.toList()

    /**
     * 某个作用域下**可以选**的动作。
     *
     * 无方向动作（`directional == false`，目前只有"启动应用"）在这里被滤掉：业主 2026-11-01
     * 拍板"无方向动作直接不支持"。过滤放在注册表而不是界面，是因为这里是唯一的取用点
     * （`ui/screen/GestureConfigScreen.kt:57`）—— 界面因此一行都不用改，别的入口也漏不掉。
     *
     * `all()` 保持 16 个不动：动作 id 是对外契约，删一个 id 就会让老存档"查不到动作"。
     */
    fun allForScope(scope: ConfigScope): List<Action> = actions.values.filter { action ->
        if (!action.directional) return@filter false
        val sceneOnly = action.sceneOnly ?: return@filter true
        scope is ConfigScope.Scene && scope.scene == sceneOnly
    }
}
