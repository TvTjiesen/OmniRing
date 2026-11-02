package xyz.nextalone.hyperzoomring.action

import android.content.Context
import xyz.nextalone.hyperzoomring.config.SceneType

interface Action {
    val id: String
    val displayName: String
    val sceneOnly: SceneType? get() = null

    /**
     * 这个动作会不会往屏幕上合成一根手指。
     *
     * 必须标出来：我们自己的 App 在前台时，那根假手指会落进我们自己的列表和卡片里 ——
     * 位移不够就被判成点击，于是"应用内一转环就误触打开东西"（他 2026-10-16 报的）。
     * 应用内改成直接滚当前页（见 MainActivity 的环事件接收器），不再注手指。
     */
    val injectsTouch: Boolean get() = false

    /**
     * 这个动作能不能被绑到表冠的某一侧（"往哪转"对它有没有意义）。
     *
     * false = 无方向动作。业主 2026-11-01 拍板：这类动作直接不支持 —— 它不出现在选择器里
     * （过滤在 `ActionRegistry.allForScope`，那是唯一的取用点），旧配置指向它时由
     * `config.migrateActionId` 归一成 "scroll"。少了这道归一，界面会显示"已配置"、
     * 转环却什么都不做，那是最难查的一类失效。
     */
    val directional: Boolean get() = true

    fun execute(context: Context, intensity: Float)
}
