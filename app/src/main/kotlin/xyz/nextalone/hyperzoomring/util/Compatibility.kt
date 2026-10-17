package xyz.nextalone.hyperzoomring.util

/**
 * 「适配程度」的判定 —— 纯函数，PC 上有单测，界面只负责把它画出来。
 *
 * 为什么要有这一层，而不是在界面上写一串 if：适配程度是给用户判断"我这台到底能不能用、
 * 还差哪一步"的，它必须**每一项都能指到证据**，不能是一个凭感觉的百分比。
 *
 * 分级口径（2026-10-17 定，机型/系统一律以设备现读为准，不再手填）：
 *
 *  - [Level.FULL]        机型对 + 系统在实测范围内 + 模块已加载 + 环数据在进 —— 全通。
 *  - [Level.SETUP]       机型和系统都对，但模块没加载 / 没重启 / 环还没数据 —— 差一步，可修。
 *  - [Level.PARTIAL]     机型对但系统低于实测范围 —— 大概率能用，没验证过。
 *  - [Level.UNSUPPORTED] 机型不对 —— 装了不会有反应，因为变焦环是这台独有的物理件。
 */
object Compatibility {

    enum class Level { FULL, SETUP, PARTIAL, UNSUPPORTED }

    /** 每一项检查 */
    enum class Check { DEVICE, OS, PATH, LOADED, RING }

    enum class State { PASS, WARN, FAIL, INFO }

    data class Item(val check: Check, val state: State, val value: String)

    data class Report(
        val level: Level,
        val items: List<Item>,
        /** 硬项通过数（INFO 那条不计：技术路径不是"通不通"的问题） */
        val passed: Int,
        val total: Int,
    )

    /** 实测过的最低 Android SDK：36（Android 16）；当前实测机是 Android 17 / SDK 37 */
    const val TESTED_MIN_SDK = 36

    fun grade(
        supportedDevice: Boolean,
        modelText: String,
        osText: String,
        sdk: Int,
        verdict: StatusJudge.Verdict?,
        ringSamples: Long,
    ): Report {
        val items = mutableListOf<Item>()

        items += Item(
            Check.DEVICE,
            if (supportedDevice) State.PASS else State.FAIL,
            modelText,
        )

        val osState = when {
            !supportedDevice -> State.INFO                 // 机型都不对，系统版本再新也没意义
            sdk >= TESTED_MIN_SDK -> State.PASS
            sdk >= 34 -> State.WARN                        // Android 14/15：没在它们上实测过
            else -> State.FAIL                             // 更老的系统，本模块压根不支持
        }
        items += Item(Check.OS, osState, osText)

        // 技术路径：这台 ROM 把输入事件过滤关了，所以我们读的是变焦环自己的光学传感器流。
        // 这不是"通不通"，是"走的哪条路"，所以永远标 INFO。
        items += Item(Check.PATH, State.INFO, "sensor")

        val loadedState = when (verdict) {
            null -> State.WARN
            StatusJudge.Verdict.OK -> State.PASS
            StatusJudge.Verdict.RELOAD_PENDING, StatusJudge.Verdict.DISABLED -> State.WARN
            else -> State.FAIL
        }
        items += Item(Check.LOADED, loadedState, verdict?.name ?: "UNKNOWN")

        // 环数据只当"证据"看，不当门槛：他十分钟没转环，样本数当然停在老地方，
        // 但那不代表没适配。真正的坏信号是**开机到现在一个有效样本都没进来**。
        val ringState = if (ringSamples > 0L) State.PASS else State.WARN
        items += Item(Check.RING, ringState, ringSamples.toString())

        // 硬项 = 能判"能不能用"的那三条。路径和环数据是补充说明，不计分。
        val hard = items.filter { it.check == Check.DEVICE || it.check == Check.OS || it.check == Check.LOADED }
        val passed = hard.count { it.state == State.PASS }

        val level = when {
            !supportedDevice -> Level.UNSUPPORTED
            sdk < TESTED_MIN_SDK -> Level.PARTIAL
            passed < hard.size -> Level.SETUP
            ringState != State.PASS -> Level.SETUP         // 模块在跑但环一个样本都没进来
            else -> Level.FULL
        }
        return Report(level, items, passed, hard.size)
    }
}
