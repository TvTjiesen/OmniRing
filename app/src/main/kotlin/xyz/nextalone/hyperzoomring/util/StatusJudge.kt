package xyz.nextalone.hyperzoomring.util

/**
 * 「到底生效了没有」的分层判定。
 *
 * 抽成纯函数是因为这条链路上有五种"看起来没反应"其实原因完全不同，
 * 判错一次就会让用户去做无用操作。纯函数 + 单测能把它钉死。
 *
 * 判定顺序就是排查顺序，从最底层往上：
 *   没进系统框架 → 进去了但不是新代码 → 代码是新的但总开关关着 →
 *   环根本没数据进来 → 有数据但每次都注入失败 → 一切正常
 */
object StatusJudge {

    enum class Verdict {
        /** 全链路正常 */
        OK,

        /** 完全没听到心跳：模块没被加载（LSPosed 没启用 / 作用域没勾系统框架 / 没重启过） */
        NOT_LOADED,

        /** 有心跳，但签名和现在装的 APK 不一致：装完没重启作用域 */
        RELOAD_PENDING,

        /** 代码在跑，但模块总开关是关的 */
        DISABLED,

        /** 代码在跑、开关也开着，但传感器一个有效样本都没来过（多半不是这台机型） */
        NO_SAMPLES,

        /** 有样本也有分发，但每次都失败 */
        ALL_FAILED,
    }

    data class Result(val verdict: Verdict, val snapshot: Heartbeat.Snapshot?)

    /**
     * @param heartbeat   Settings.Global 里的心跳，null = 从没写过
     * @param installedSig 当前已安装 APK 的指纹
     * @param enabled     模块总开关
     */
    fun judge(
        heartbeat: Heartbeat.Snapshot?,
        installedSig: String?,
        enabled: Boolean,
    ): Result {
        if (heartbeat == null) return Result(Verdict.NOT_LOADED, null)
        // 签名比对：只有两边都拿到值才敢下"要重启"的结论，否则宁可放过
        if (!installedSig.isNullOrEmpty() &&
            heartbeat.signature.isNotEmpty() &&
            heartbeat.signature != installedSig
        ) {
            return Result(Verdict.RELOAD_PENDING, heartbeat)
        }
        if (!enabled) return Result(Verdict.DISABLED, heartbeat)
        if (heartbeat.samples <= 0L) return Result(Verdict.NO_SAMPLES, heartbeat)
        if (heartbeat.ok == 0 && heartbeat.fail > 0) return Result(Verdict.ALL_FAILED, heartbeat)
        return Result(Verdict.OK, heartbeat)
    }

    /**
     * 显示用的迟滞：连续 [STABLE_TICKS] 次读到同一个判定才改口。
     *
     * 用户反馈"同样环境下有时说生效有时说不生效"。判定本身没错，错在**直接显示瞬时值**：
     * 卡片 2 秒轮询、心跳 2 秒落盘，两者错拍时偶尔会读到半新半旧的组合，
     * 于是一行字在"已生效/未生效"之间来回跳。这种抖动必须靠迟滞压掉，
     * 而不是让界面看起来像坏了。
     */
    data class FilterState(
        val shown: Verdict? = null,
        val candidate: Verdict? = null,
        val streak: Int = 0,
    )

    const val STABLE_TICKS = 2

    fun step(state: FilterState, next: Verdict, stableTicks: Int = STABLE_TICKS): FilterState {
        val shown = state.shown
        if (shown == null) return FilterState(shown = next, candidate = null, streak = 0)
        if (next == shown) return FilterState(shown = shown, candidate = null, streak = 0)
        return if (state.candidate == next && state.streak + 1 >= stableTicks) {
            FilterState(shown = next, candidate = null, streak = 0)
        } else {
            FilterState(shown = shown, candidate = next, streak = state.streak + 1)
        }
    }

    /** 心跳离现在多久（秒）。system_server 和 App 读的是同一个 uptime，可以直接比 */
    fun ageSeconds(lastSampleAt: Long, nowUptime: Long): Long =
        ((nowUptime - lastSampleAt) / 1000L).coerceAtLeast(0L)

    /**
     * 配置同步状态：钩子发布的 `hzr_cfg_state = "推送次数;键数;时间戳"`。
     *
     * 为什么界面要显示这个：system_server 读不到 App 的私有 prefs（实测 exists=false），
     * 配置只能靠 App 推。推没推到位，光看"设置里改了但行为没变"是分不出来的 ——
     * 有了这一行，就能区分"改动没送到系统"和"送到了但这个动作本来就这样"。
     */
    data class CfgState(val pushed: Int, val keys: Int, val at: Long)

    fun parseCfgState(raw: String?): CfgState? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 2) return null
        val pushed = parts[0].toIntOrNull() ?: return null
        val keys = parts[1].toIntOrNull() ?: return null
        return CfgState(pushed, keys, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /**
     * 是不是我们实测过的那台机器。
     *
     * `Build.MODEL` 在这台 17 Ultra 徕卡版上给的是型号代码 `25128PNA1C`，
     * 市场名才是 "Xiaomi 17 Ultra by Leica" —— 两种都认，别只比一个就下结论。
     * 判错会让"没收到环数据"的提示在真机上也说"属正常"，那就是把 bug 说成特性。
     */
    fun isSupportedModel(model: String): Boolean =
        model.contains("25128PNA1C", ignoreCase = true) ||
            model.contains("17 Ultra", ignoreCase = true)

    private const val SEPARATOR = ";"
}
