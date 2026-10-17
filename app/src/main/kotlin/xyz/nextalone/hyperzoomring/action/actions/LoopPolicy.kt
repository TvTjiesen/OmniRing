package xyz.nextalone.hyperzoomring.action.actions

/**
 * 注入循环的收尾判据（纯函数，单测直接钉）。
 *
 * 为什么单独拎出来：这条规则是"我们那根合成手指会不会被留在屏幕上"的唯一防线，
 * 而它一旦漏掉，用户看到的现象是整页滑不动、变焦环也跟着失效 —— 在真机上极难归因，
 * 只能靠这一条规则 + 一个不依赖 Android 的单测来保证它不再被改回去。
 *
 * 历史（2026-10-15）：`frame` 原来只按 `step()` 的返回值决定要不要续帧。某一帧
 * "模型判定结束"和"抬手注入失败"同时发生时，循环就带着还 press 着的手指退场了。
 * 我们注入用的是 pointerId=0，和用户真实手指的 id 撞车，于是之后每次真触摸都变成
 * 双指手势 —— 就是他说的"点了底栏切到设置就滑不动了，反复点底栏才好"。
 */
object LoopPolicy {

    /** 这一帧之后循环还要不要继续跑（正常推进 or 只为收尾抬手） */
    fun keepRunning(
        sessionWantsToStop: Boolean,
        fingerDown: Boolean,
        scrubDown: Boolean,
        scrubActive: Boolean,
        tries: Int,
        maxTries: Int,
    ): Boolean = !sessionWantsToStop || needsReleaseRetry(
        sessionWantsToStop, fingerDown, scrubDown, scrubActive, tries, maxTries
    )

    /** 会话已经不想再跑了，但屏幕上还压着我们的手指 -> 必须继续跑去做收尾 */
    fun needsReleaseRetry(
        sessionWantsToStop: Boolean,
        fingerDown: Boolean,
        scrubDown: Boolean,
        scrubActive: Boolean,
        tries: Int,
        maxTries: Int,
    ): Boolean = sessionWantsToStop && (fingerDown || scrubDown || scrubActive) && tries < maxTries
}
