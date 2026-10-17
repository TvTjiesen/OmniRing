package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action

object LaunchAppAction : Action {
    override val id = "launch_app"
    override val displayName = "启动应用"
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
