// 主题包装层的写法取自 Miuix 官方示例 ui/Theme.kt（Apache-2.0，
// github.com/compose-miuix-ui/miuix）。本模块所有界面都从这里拿颜色，
// 所以深浅色 / Material You 取色只需要改 AppTheme 的入参。
package xyz.nextalone.hyperzoomring.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** 主题模式，和设置页里的单选项一一对应 */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
    const val MONET_SYSTEM = 3
    const val MONET_LIGHT = 4
    const val MONET_DARK = 5

    /** 模式总数：设置页按 0..<COUNT> 铺单选项 */
    const val COUNT = 6
    // 显示名在 Labels.kt 的 themeModeLabel()：这里不放字符串，切语言才跟着走
}

/**
 * 按 Material Design 深色规范重写色板。
 *
 * 规则来源（2026-10-13 查证）：
 *  - 背景不用纯黑，用 #121212（MD 推荐暗色表面；纯黑在 OLED 上有拖影、对比过强）
 *  - 层级越高表面越亮：elevation 叠加白色半透明层（1dp≈+5%、2dp≈+7%、3dp≈+8%…）
 *  - 文字不用纯白：高强调 87%、中 60%、禁用 38%
 *  - 不简单反转浅色颜色，专门做互补深色板
 *  - 深色表面 vs 白字对比度 ≥15.8:1，正文 ≥4.5:1
 *
 * 之前的问题：背景 #000000 + 卡片 #1C1C1E，层级差只有 4%，肉眼分不出卡片和背景。
 * 现在：背景 #121212 → 卡片 #1E1E1E → 高容器 #2A2A2A → 最高 #333333，
 * 每档差 8-12%，能清楚看出"卡片浮在页面上"。
 */
private fun mdDark(base: Colors): Colors = base.copy(
    // 背景层：MD 推荐 #121212 而不是纯黑
    background = Color(0xFF121212),
    // 一级容器（卡片）：elevation 1dp = 背景 + 5% 白 ≈ #1E1E1E
    surface = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceVariant = Color(0xFF333333),
    // 分隔线：12% 白，不抢内容
    dividerLine = Color(0x1FFFFFFF),
    // 文字：87% / 60% / 38%（MD 规范）
    onBackground = Color(0xDEFFFFFF),
    onSurface = Color(0xDEFFFFFF),
    onSurfaceContainer = Color(0xDEFFFFFF),
    onSurfaceContainerHigh = Color(0xDEFFFFFF),
    onSurfaceContainerHighest = Color(0xDEFFFFFF),
    onBackgroundVariant = Color(0x99FFFFFF),
    onSurfaceVariantSummary = Color(0x99FFFFFF),
    onSurfaceVariantActions = Color(0x99FFFFFF),
    onSurfaceContainerVariant = Color(0x99FFFFFF),
    // 主色在深色下稍微提亮保持对比度（Miuix 默认 primary 在暗色下已经调过，不动）
)

/**
 * 应用主题。
 *
 * @param colorMode 见 [ThemeMode]
 * @param keyColor 只在 Material You 三档下生效；null = 用壁纸/系统取色
 * @param colorSpec 色彩规格（2021 / 2025 两套调色算法）
 * @param paletteStyle 调色板风格
 */
@Composable
fun AppTheme(
    colorMode: Int = ThemeMode.SYSTEM,
    keyColor: Color? = null,
    colorSpec: Int = 0,
    paletteStyle: Int = 0,
    content: @Composable () -> Unit,
) {
    val spec = ThemeColorSpec.entries.getOrNull(colorSpec) ?: ThemeColorSpec.Spec2021
    val style = ThemePaletteStyle.entries.getOrNull(paletteStyle) ?: ThemePaletteStyle.Content
    // 深色层级只覆盖"纯深色"这一档：Material You（深）是从壁纸取色的，不该被我们写死
    val mdDarkColors = remember { mdDark(ThemeController(ColorSchemeMode.Dark).darkColors) }
    // ThemeController 内部要算一整套色板，参数没变就别重建
    val controller = remember(colorMode, keyColor, spec, style) {
        when (colorMode) {
            ThemeMode.LIGHT -> ThemeController(ColorSchemeMode.Light)
            ThemeMode.DARK -> ThemeController(ColorSchemeMode.Dark, darkColors = mdDarkColors)
            ThemeMode.MONET_SYSTEM -> ThemeController(ColorSchemeMode.MonetSystem, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            ThemeMode.MONET_LIGHT -> ThemeController(ColorSchemeMode.MonetLight, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            ThemeMode.MONET_DARK -> ThemeController(ColorSchemeMode.MonetDark, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    MiuixTheme(controller = controller, content = content)
}
