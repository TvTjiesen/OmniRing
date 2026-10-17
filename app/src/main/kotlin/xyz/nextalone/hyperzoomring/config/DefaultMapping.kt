package xyz.nextalone.hyperzoomring.config

import xyz.nextalone.hyperzoomring.ring.GestureType

/**
 * "四个槽位都没配"时种一份默认映射。
 *
 * 为什么需要：钩子拿到空映射就是"转环什么都不做"。今晚真机踩过一次 ——
 * App 数据目录不见了（HyperOS 侧的原因没查全），四个 gesture 键全空，
 * 而新加的推送通道会把这个"全空"整份推给 system_server，
 * 于是原本还能靠启动快照工作的环当场失灵。全新安装的用户也会撞同一面墙。
 *
 * 只种一次：种过就打标记，之后他主动把所有槽位清空，不会被我们偷偷填回去。
 */
object DefaultMapping {

    /**
     * 四个槽位统一给"滚动"：方向本来就跟表冠实际转向走（顺时针往下、逆时针往上），
     * 不需要再区分定方向动作 —— 那两个已被删除（用户反馈它们和"滚动"冲突）。
     */
    private val MAP = linkedMapOf(
        GestureType.CW_SLOW to "scroll",
        GestureType.CW_FAST to "scroll",
        GestureType.CCW_SLOW to "scroll",
        GestureType.CCW_FAST to "scroll",
    )

    fun shouldSeed(existing: (GestureType) -> String?): Boolean =
        GestureType.entries.all { existing(it) == null }

    fun seed(set: (GestureType, String) -> Unit) {
        MAP.forEach { (g, id) -> set(g, id) }
    }

    /** @return 真的种了才 true */
    fun seedIfNeeded(config: ConfigManager): Boolean {
        if (config.defaultsSeeded) return false
        config.defaultsSeeded = true
        if (!shouldSeed { config.getActionId(it) }) return false
        seed { g, id -> config.setActionId(g, id) }
        return true
    }
}
