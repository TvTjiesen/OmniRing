package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nextalone.hyperzoomring.config.ConfigManager

/**
 * 竖屏冲突回归：把"竖屏刷列表 + 后台放歌"和"横屏看片"两种场景都跑一遍。
 */
class SeekGateTest {

    private val portrait = ConfigManager.DEFAULT_SEEK_PORTRAIT

    @Test
    fun `竖屏刷列表时后台放歌 不接管`() {
        assertFalse(
            SeekGate.shouldSeek(
                switchOn = true, actionId = "scroll", mediaPlaying = true,
                landscape = false, foregroundPkg = "com.miui.home", portraitAllowlist = portrait,
            )
        )
        // 音乐 App 自己在前台也不算"在看视频"
        assertFalse(
            SeekGate.shouldSeek(
                true, "scroll", true, false, "com.tencent.qqmusic", portrait,
            )
        )
        assertFalse(
            SeekGate.shouldSeek(true, "scroll", true, false, "com.miui.player", portrait)
        )
    }

    @Test
    fun `横屏播放时接管`() {
        assertTrue(
            SeekGate.shouldSeek(
                switchOn = true, actionId = "scroll", mediaPlaying = true,
                landscape = true, foregroundPkg = "tv.danmaku.bili", portraitAllowlist = portrait,
            )
        )
    }

    @Test
    fun `竖屏视频应用接管 音乐视频要分开`() {
        assertTrue(SeekGate.shouldSeek(true, "scroll", true, false, "tv.danmaku.bili", portrait))
        assertTrue(SeekGate.shouldSeek(true, "scroll", true, false, "com.ss.android.ugc.aweme", portrait))
        assertTrue(SeekGate.shouldSeek(true, "scroll", true, false, "com.google.android.youtube", portrait))
        assertFalse(SeekGate.shouldSeek(true, "scroll", true, false, "com.salt.music", portrait))
    }

    @Test
    fun `开关关掉就完全不接管 但显式映射仍然有效`() {
        assertFalse(SeekGate.shouldSeek(false, "scroll", true, true, "tv.danmaku.bili", portrait))
        assertTrue(SeekGate.shouldSeek(false, "video_seek", true, true, "tv.danmaku.bili", portrait))
    }

    @Test
    fun `非滚动动作一律放行`() {
        assertFalse(SeekGate.shouldSeek(true, "volume_up", true, true, "tv.danmaku.bili", portrait))
        assertFalse(SeekGate.shouldSeek(true, "media_next", true, true, "tv.danmaku.bili", portrait))
        // 竖屏 + 没在播放 = 不接管（横屏那条已经允许暂停，见上面的用例）
        assertFalse(SeekGate.shouldSeek(true, "scroll", false, false, "tv.danmaku.bili", portrait))
    }

    @Test
    fun `横屏暂停着也要接管 因为暂停时 isMusicActive 是假的`() {
        // 他报的"视频暂停的时候没反应"就是这个：旧规则要求 mediaPlaying，暂停直接不接管
        assertTrue(
            SeekGate.shouldSeek(
                switchOn = true, actionId = "scroll", mediaPlaying = false,
                landscape = true, foregroundPkg = "tv.danmaku.bili", portraitAllowlist = portrait,
            )
        )
        assertTrue(
            SeekGate.shouldSeek(
                true, "scroll", false, true, "com.ss.android.ugc.aweme", portrait
            )
        )
        // 但横屏+不在名单里+没在放，就别乱接管（可能只是在横屏浏览网页）
        assertFalse(
            SeekGate.shouldSeek(true, "scroll", false, true, "com.android.chrome", portrait)
        )
    }

    @Test
    fun `竖屏暂停仍然是滚动`() {
        assertFalse(SeekGate.shouldSeek(true, "scroll", false, false, "tv.danmaku.bili", portrait))
    }

    @Test
    fun `白名单里没有音乐类`() {
        val music = listOf(
            "com.tencent.qqmusic", "com.netease.cloudmusic", "com.miui.player", "com.spotify.music",
        )
        for (pkg in music) assertFalse("$pkg 不该在竖屏视频白名单里", pkg in portrait)
    }
}
