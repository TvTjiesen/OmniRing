package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nextalone.hyperzoomring.action.ActionRegistry

/**
 * #60「其他功能我怀疑有的也失效了，你先在你那里模拟」。
 *
 * 分发路由原来埋在 SensorRingDriver 的 if 链里，贴着 Android 的 Context，
 * 所以在 PC 上一条都断言不了 —— 抽成纯函数 [ActionRouter] 之后，
 * "每个动作走哪条通道、有没有动作走进死路"就能全量钉住。
 */
class ActionRouterTest {

    private fun route(id: String, seek: Boolean = false) =
        ActionRouter.route(actionId = id, asSeek = seek)

    /**
     * scroll_up / scroll_down 这两个动作已经删了（用户反馈定方向和表冠转向冲突）。
     * 但他手机上和老用户身上的存档还写着这两个 id，所以路由必须继续把它们兜到
     * 滚动通道 —— 兜不住的话 SensorRingDriver 查不到动作就直接 return，
     * 表现在他那边就是"升级完表冠突然没反应了"。
     */
    @Test
    fun `滚动和已删除的定方向旧 id 都走连续滚动通道`() {
        assertEquals(ActionRouter.Branch.SCROLL, route("scroll"))
        assertEquals(ActionRouter.Branch.SCROLL, route("scroll_up"))
        assertEquals(ActionRouter.Branch.SCROLL, route("scroll_down"))
    }

    @Test
    fun `音量亮度这类走离散触发`() {
        assertEquals(ActionRouter.Branch.DISCRETE, route("volume_up"))
        assertEquals(ActionRouter.Branch.DISCRETE, route("brightness_down"))
        assertEquals(ActionRouter.Branch.DISCRETE, route("media_next"))
        assertEquals(ActionRouter.Branch.DISCRETE, route("launch_app"))
    }

    @Test
    fun `进度环只在判定接管时才抢滚动`() {
        // 没接管：滚动就是滚动
        assertEquals(ActionRouter.Branch.SCROLL, route("scroll", seek = false))
        // 接管了：改走进度（会话优先，没有会话再拖播放器自己的进度条）
        assertEquals(ActionRouter.Branch.SEEK, route("scroll", seek = true))
        assertEquals(ActionRouter.Branch.SEEK, route("scroll_down", seek = true))
    }

    @Test
    fun `拖进度条动作本身不经过会话通道`() {
        // id 直接写死，别去引用 ScrubDragAction：那是个带 Android 依赖的 object，
        // 单元测试里加载它会先炸在类加载上，测不到路由本身
        assertEquals(ActionRouter.Branch.DRAG, route("video_scrub_drag"))
        // 就算进度环接管了，它也还是"拖"，不去碰 MediaSession（它存在的理由就是没有会话）
        assertEquals(ActionRouter.Branch.DRAG, route("video_scrub_drag", seek = true))
    }

    /**
     * 全量体检：注册表里每个动作都必须落到"它本该落到的那条通道"。
     *
     * 这条才是 #60 要的"其他功能有没有失效"的机器可查版本 ——
     * 任何一条 if 链改错、任何一个新动作忘了接，这里就会点名。
     * 期望值按 id 逐个列，不用前缀猜：`video_seek` 是"每次转够量发一次相对 seek"（离散），
     * 而 `video_scrub_drag` 是连续拖，两者前缀一样、通道完全不同。
     */
    @Test
    fun `注册表里每个动作都落到符合语义的通道`() {
        val expected = mapOf(
            "scroll" to ActionRouter.Branch.SCROLL,
            "video_scrub_drag" to ActionRouter.Branch.DRAG,
            "video_seek" to ActionRouter.Branch.DISCRETE,
        )
        val wrong = ActionRegistry.all().mapNotNull { action ->
            val want = expected[action.id] ?: ActionRouter.Branch.DISCRETE
            val got = route(action.id)
            if (got == want) null else "${action.id}: 期望 $want 实际 $got"
        }
        assertTrue("这些动作的分发通道不对：\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun `注册表本身自洽：id 唯一且都有显示名`() {
        val ids = ActionRegistry.all().map { it.id }
        assertEquals("动作 id 不能重复", ids.size, ids.toSet().size)
        assertTrue("动作数量对不上", ids.size >= 16)
        // 定方向那两个是删掉的，不是"暂时没注册"——别哪天又冒回来
        assertTrue("scroll_up 应该已删除", "scroll_up" !in ids)
        assertTrue("scroll_down 应该已删除", "scroll_down" !in ids)
        ids.forEach { assertNotNull(it, ActionRegistry.get(it)) }
    }
}
