package xyz.nextalone.hyperzoomring.config

import xyz.nextalone.hyperzoomring.ring.CrownDir

/**
 * "两个槽位都没配"时种一份默认映射。
 *
 * 为什么需要：钩子拿到空映射就是"转环什么都不做"。真机踩过一次 ——
 * App 数据目录不见了（HyperOS 侧的原因没查全），四个 gesture 键全空，
 * 而新加的推送通道会把这个"全空"整份推给 system_server，
 * 于是原本还能靠启动快照工作的环当场失灵。全新安装的用户也会撞同一面墙。
 *
 * 只种一次：种过就打标记，之后他主动把所有槽位清空，不会被我们偷偷填回去。
 */
object DefaultMapping {

    /**
     * 两个转向统一给"滚动"：滚动方向本来就随表冠实际转向（顺时针往下、逆时针往上），
     * 不需要再区分定方向动作 —— 那两个早被删除（用户反馈它们和"滚动"冲突）。
     */
    private val MAP = linkedMapOf(
        CrownDir.PLUS to "scroll",
        CrownDir.MINUS to "scroll",
    )

    /**
     * 判据：PLUS 与 MINUS **按读时回落链**都解析不出东西，才算"没配过"。
     *
     * 陷阱（设计 §3.2 第 1 条）：`existing` 必须来自 `ConfigManager.getActionId` 那条
     * 带旧键回落的链。如果这里只看新键，四槽位时代配过自定义映射的老用户会被判成
     * "全空"，于是被重新种上 scroll —— 他辛苦配的东西当场被冲掉，而且界面不会告诉他。
     */
    fun shouldSeed(existing: (CrownDir) -> String?): Boolean =
        CrownDir.entries.all { existing(it) == null }

    fun seed(set: (CrownDir, String) -> Unit) {
        MAP.forEach { (dir, id) -> set(dir, id) }
    }

    /** @return 真的种了才 true */
    fun seedIfNeeded(config: ConfigManager): Boolean {
        if (config.defaultsSeeded) return false
        config.defaultsSeeded = true
        if (!shouldSeed { config.getActionId(it) }) return false
        seed { dir, id -> config.setActionId(dir, id) }
        return true
    }
}