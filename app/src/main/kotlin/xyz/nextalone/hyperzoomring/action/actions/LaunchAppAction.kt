package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action

object LaunchAppAction : Action {
    override val id = "launch_app"
    override val displayName = "启动应用"

    /**
     * 无方向动作：表冠"往哪转"决定不了要启动哪个应用，所以它不能被绑到 PLUS/MINUS 任何一侧。
     * 动作本体连同 id 一起保留（16 个动作 id 的对外契约不动），只是不再出现在选择器里；
     * 旧存档里的 launch_app 映射由 `config.migrateActionId` 归一成 "scroll"。
     */
    override val directional = false
    var targetPackage: String? = null

    override fun execute(context: Context, intensity: Float) {
        val pkg = targetPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Log.w("HyperZoomRing", "No launch intent for package: $pkg"); return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
