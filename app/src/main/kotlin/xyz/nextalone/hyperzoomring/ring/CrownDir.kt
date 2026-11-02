package xyz.nextalone.hyperzoomring.ring

/**
 * 表冠转向 —— 配置面的主键。
 *
 * 2026-11-02 由四槽位（CW_SLOW / CW_FAST / CCW_SLOW / CCW_FAST）塌成两槽位。原因不是"简化"，
 * 是一个可复现的病灶：`fast` 判据是"滑动窗口里的样本数 > speedThreshold"，转速在阈值上下
 * 抖动时相邻样本会落进不同槽位，于是两个动作交替执行 —— 业主报的"音量/滚动概率冲突、
 * 像被抢"就是它。转速不再决定执行哪个动作，它只经 [xyz.nextalone.hyperzoomring.hook.SensorRingDriver]
 * 的离散累计影响幅度（业主 2026-11-01 拍板：无方向动作直接不支持、转速只影响幅度）。
 *
 * 枚举的 `name` 就是配置键名（`gesture_${name}_action_id`）：**枚举值不许再改名**，
 * 改名等于改配置键。旧四槽位的键只出现在 `ConfigManager` 的读时回落链里，不迁移、不删除。
 *
 * 设计全文见 docs/CROWN-ACTION-V2-DESIGN.md。
 */
enum class CrownDir(val displayName: String) {
    PLUS("顺时针（＋）"),
    MINUS("逆时针（−）"),
}