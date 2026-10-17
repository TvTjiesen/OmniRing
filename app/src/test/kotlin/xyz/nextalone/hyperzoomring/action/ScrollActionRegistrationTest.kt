package xyz.nextalone.hyperzoomring.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType

/**
 * 动作清单的可见性规约（用户反馈"手势里的功能太冗杂了"之后钉死）。
 *
 * 全局槽位的选择器只该出现"在全局有意义"的动作：滚动删掉定方向那两个、
 * 音视频进度类只在"播放中"场景可选 —— 它们和首页那个"播放时把滚动改成调进度"
 * 总开关是同一件事，两处都能定就必然出现"改哪边都不一定生效"。
 */
class ScrollActionRegistrationTest {

    private fun globalIds() = ActionRegistry.allForScope(ConfigScope.Global).map { it.id }
    private fun mediaIds() =
        ActionRegistry.allForScope(ConfigScope.Scene(SceneType.MEDIA_PLAYING)).map { it.id }

    @Test
    fun `滚动注册着，定方向那两个已经删掉`() {
        assertNotNull(ActionRegistry.get("scroll"))
        assertNull("scroll_up 应该已经不存在", ActionRegistry.get("scroll_up"))
        assertNull("scroll_down 应该已经不存在", ActionRegistry.get("scroll_down"))
        assertEquals("滚动（方向随转向、距离随幅度）", ActionRegistry.get("scroll")!!.displayName)
    }

    @Test
    fun `全局清单里只有一个滚动选项`() {
        val scrollish = globalIds().filter { it.startsWith("scroll") }
        assertEquals(listOf("scroll"), scrollish)
    }

    @Test
    fun `进度类动作不进全局清单`() {
        val ids = globalIds()
        assertTrue("video_seek 不该出现在全局槽位：和首页总开关冲突", "video_seek" !in ids)
        assertTrue("video_scrub_drag 不该出现在全局槽位", "video_scrub_drag" !in ids)
        assertTrue("media_seek_forward 本来就只属于场景", "media_seek_forward" !in ids)
    }

    @Test
    fun `进度类动作在播放中场景仍然可选`() {
        val ids = mediaIds()
        assertTrue("video_seek 必须在播放场景可选: $ids", "video_seek" in ids)
        assertTrue("video_scrub_drag 必须在播放场景可选: $ids", "video_scrub_drag" in ids)
    }
}
