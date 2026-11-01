package xyz.nextalone.hyperzoomring.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * 旧包名（上游那一个）是否还装在这台机上。
 *
 * 为什么必须让界面说出来（2026-10-31 改 applicationId 进 LSPosed 商店时定的）：
 * 换包名 = 一个新 App，`install -r` 覆盖不到老包。于是很多人会是"老的没删、新的装上"
 * 这种状态，而**两个模块可以在 LSPosed 里同时启用**，各自抓一次环：
 * 滚动一次走两下、动作触发两遍、心跳/快照被对面盖掉 —— 症状和"我们双倍执行的 bug"
 * 一模一样，而真正的原因只是"桌上有两份"。这种坑不能指望人自己想到，必须界面说。
 *
 * 判定故意抽成 [noticeNeeded]（纯函数，可单测）：查包那一步可能抛异常，
 * 查不到就当"没装"，绝不让首页因为一次 PackageManager 调用而崩。
 */
object LegacyPackage {

    /** 改包名之前的那个应用 ID。只在本文件里出现一次，别处不要再写字面量 */
    const val OLD_APPLICATION_ID = "xyz.nextalone.hyperzoomring"

    /** 当前这个包的安装名（= applicationId，由构建给） */
    fun current(context: Context): String = context.packageName

    /** 老包还在不在机器上（不管它在 LSPosed 里启用没启用） */
    fun installed(context: Context): Boolean = query(context.packageManager) && !isSamePackage(context)

    /**
     * 一次查询。`getPackageInfo(String, Long)` 在 compileSdk 37 上已经没了
     * （只剩 `PackageInfoFlags` 那个重载），所以这里统一走 flags 对象 ——
     * 顺手把"没找到"和"查不了"分开，见 [diagnose]。
     */
    private fun query(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo(OLD_APPLICATION_ID, PackageManager.PackageInfoFlags.of(0L)) != null
    }.getOrDefault(false)

    /**
     * 新老是不是同一个包 —— 防御性：万一哪天有人把 applicationId 改回上游那个，
     * "老包存在"就变成"自己存在"，提示就会永远挂在首页上。
     */
    private fun isSamePackage(context: Context): Boolean =
        current(context) == OLD_APPLICATION_ID

    /**
     * 该不该提示、提示成什么语气。抽出来是为了能在 PC 上判：
     * @return true = 首页要挂那条"请先卸载旧版"的警示
     */
    fun noticeNeeded(legacyInstalled: Boolean, samePackage: Boolean): Boolean =
        legacyInstalled && !samePackage

    /** 一站式判据（查包失败退化成"不提示"，不吓唬人） */
    fun shouldWarn(context: Context): Boolean {
        val legacy = installed(context)
        val same = isSamePackage(context)
        // 这条日志是给自己排障用的：警示卡不出现在界面上时，光看截图分不清
        // 是"判据算出来就不该提示"还是"根本没跑到这段"（Compose 的文字在
        // uiautomator 里读不出来，实测只能靠这一行）。
        val warn = noticeNeeded(legacy, same)
        android.util.Log.i(
            "HyperZoomRing",
            "LEGACY 旧包检查 installed=$legacy why=${diagnose(context)} same=$same current=${context.packageName} → 警示=$warn"
        )
        return warn
    }

    /**
     * 用 PackageManager 的异常类型区分"没装"和"查不了"。
     * 界面不显示这个，但诊断页要 —— 否则"没提示"到底是判据说不用提示、
     * 还是压根没查成功，就分不清了（这条链路我自己踩过两次）。
     */
    fun diagnose(context: Context, pm: PackageManager = context.packageManager): String {
        if (isSamePackage(context)) return "same-as-current"
        return runCatching {
            if (pm.getPackageInfo(OLD_APPLICATION_ID, PackageManager.PackageInfoFlags.of(0L)) != null) "installed" else "absent"
        }.getOrElse { e ->
            if (e is PackageManager.NameNotFoundException) "absent" else "query-failed:${e.javaClass.simpleName}"
        }
    }
}
