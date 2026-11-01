package xyz.nextalone.hyperzoomring.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "旧包名还装着"那条提示的判据（纯函数部分）。
 *
 * 为什么值得单独测：换 applicationId 之后最坏的用户状态是"新老两个模块都启用"，
 * 表现和我们刚修完的双倍执行 bug 一模一样（滚一下走两下）。界面上那条警示
 * 是唯一的止血手段，而它依赖两个布尔值的组合 —— 组合错了就是"该提醒时不提醒"，
 * 或者更糟："把自己当成旧版，永远挂着一条假警示"。
 */
class LegacyPackageTest {

    @Test
    fun `旧包名和当前安装包名必须真的不一样`() {
        // 这条是防"有人把 applicationId 改回上游那个"：一旦相同，
        // installed() 会把"自己"判成旧版，警示会永久挂在首页上。
        assertNotEquals(LegacyPackage.OLD_APPLICATION_ID, xyz.nextalone.hyperzoomring.BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `只有旧版真的装着才提示`() {
        assertTrue(LegacyPackage.noticeNeeded(legacyInstalled = true, samePackage = false))
        assertFalse("查不到就当没装，不许吓唬人", LegacyPackage.noticeNeeded(legacyInstalled = false, samePackage = false))
        assertFalse("自己就是旧包名时不许提示（那是配置错了，不是用户装了两份）",
            LegacyPackage.noticeNeeded(legacyInstalled = true, samePackage = true))
    }

    @Test
    fun `旧包名常量和商店那条规则对得上`() {
        // 应用 ID 必须属于提交者：新值必须是 io.github.<用户名>.*（商店免域名通道）
        assertTrue(xyz.nextalone.hyperzoomring.BuildConfig.APPLICATION_ID.startsWith("io.github.tvtjiesen."))
        assertEquals("xyz.nextalone.hyperzoomring", LegacyPackage.OLD_APPLICATION_ID)
    }
}
