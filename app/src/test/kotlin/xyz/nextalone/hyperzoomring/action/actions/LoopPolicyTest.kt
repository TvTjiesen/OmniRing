package xyz.nextalone.hyperzoomring.action.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾不许停 —— 这条规则漏掉的后果是"我们那根合成手指被留在屏幕上"，
 * 用户看到的是整页滑不动 + 变焦环失效（pointerId=0 和真实手指撞车，之后每次触摸都成双指手势）。
 * 真机上这个现象极难归因，所以规则本身必须有一条不依赖 Android 的单测钉住。
 */
class LoopPolicyTest {

    private fun needs(
        stop: Boolean,
        down: Boolean = false,
        scrubDown: Boolean = false,
        scrubActive: Boolean = false,
        tries: Int = 0,
        max: Int = 60,
    ) = LoopPolicy.needsReleaseRetry(stop, down, scrubDown, scrubActive, tries, max)

    @Test
    fun `会话要停但手指还按着时必须继续跑去做收尾`() {
        // 这就是 2026-10-15 漏掉的那一帧：模型判完 + 抬手注入失败
        assertTrue(needs(stop = true, down = true))
        assertTrue(needs(stop = true, scrubDown = true))
        assertTrue(needs(stop = true, scrubActive = true))
    }

    @Test
    fun `什么都没压着就该真的停下来，不许空转`() {
        assertFalse(needs(stop = true))
    }

    @Test
    fun `会话没结束就照常推进，不走收尾分支`() {
        assertFalse("会话还在推进时不该算收尾重试", needs(stop = false, down = true))
        assertTrue(
            LoopPolicy.keepRunning(
                sessionWantsToStop = false, fingerDown = true, scrubDown = false,
                scrubActive = false, tries = 0, maxTries = 60,
            ),
        )
    }

    @Test
    fun `收尾有上限，试满就停手并留下证据而不是无限自旋`() {
        assertTrue(needs(stop = true, down = true, tries = 59))
        assertFalse(needs(stop = true, down = true, tries = 60))
        assertFalse(needs(stop = true, down = true, tries = 999))
        assertFalse(LoopPolicy.keepRunning(
            sessionWantsToStop = true, fingerDown = true, scrubDown = false,
            scrubActive = false, tries = 60, maxTries = 60,
        ))
    }
}
