package xyz.nextalone.hyperzoomring.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 项目对外链接。仓库地址写在这里，App 内和 README 都用这一份，
 * 换名字/换 owner 只改一处。
 */
object Links {
    const val GITHUB_REPO = "https://github.com/TvTjiesen/OmniRing"
    const val GITHUB_OWNER = "TvTjiesen"
    const val COOLAPK = "https://www.coolapk.com/u/22174009"

    const val UPSTREAM = "https://github.com/NextAlone/HyperZoomRing"
    const val MIUIX = "https://github.com/compose-miuix-ui/miuix"
    const val LIQUID_GLASS = "https://github.com/Kyant0/AndroidLiquidGlass"
    const val YUKIHOOK = "https://github.com/HighCapable/YukiHookAPI"
    const val KAVAREF = "https://github.com/HighCapable/KavaRef"
    const val COMPOSE = "https://developer.android.com/jetpack/compose"
    const val LSPOSED = "https://github.com/LSPosed/LSPosed"
}

/** 打开外链。没有浏览器（比如某些精简系统）时不要崩，返回 false 让界面提示。 */
fun Context.openUrl(url: String): Boolean = runCatching {
    startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrElse {
    if (it is ActivityNotFoundException) false else false
}
