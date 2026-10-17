package xyz.nextalone.hyperzoomring.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.ui.liquid.IosLiquidGlassNavigationBar

/**
 * 底栏：直接接 Miuix 官方示例那份 iOS 液态玻璃导航条（出处见 ui/liquid 里的 SPDX 说明）。
 *
 * 我先前自己写的那版只有"半透明 + 背景模糊"，缺了液态玻璃最关键的两件事：
 * 选中项那块**会滑过去的玻璃滑块**（带阻尼跟随、松手回弹、越界橡皮筋），以及滑块的
 * **透镜折射**（把滑块边缘背后的内容往外弯）。这两样才是"和 lsposed 开了
 * 悬浮底栏 + 液态玻璃"一样的效果，所以直接上官方实现，不自己造轮子。
 */
data class GlassNavItem(val label: String, val icon: ImageVector)

@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    blurDp: Float = GlassTuning.BAR_DEFAULT_DP,
    lensDp: Float = GlassTuning.LENS_DEFAULT_DP,
    modifier: Modifier = Modifier,
) {
    val navItems = items.map { NavigationItem(it.label, it.icon) }
    // 只有采样层"真的有内容坐标"时才走玻璃路径。Miuix 0.9.2 对空样本（切页瞬间、
    // 快速点底栏时页面层被重录掉的那一帧）不是"没模糊"，而是直接画实心黑——
    // 这就是他说的"快速点击底栏会有黑色的东西跳出来"。判据在 backdropIsLive。
    val live = backdrop != null && backdropIsLive(backdrop)
    IosLiquidGlassNavigationBar(
        items = navItems,
        selectedIndex = selected,
        onItemClick = onSelect,
        backdrop = backdrop,
        isBlurActive = live,
        blurDp = blurDp,
        lensDp = lensDp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 26.dp, vertical = 12.dp)
            .fillMaxWidth(),
    )
}

/** 五个一级页：图标用语义明确的那几个，别五个都长一样 */
@Composable
fun rememberTabs(): List<GlassNavItem> = listOf(
    GlassNavItem(stringResource(R.string.tab_home), MiuixIcons.All),
    GlassNavItem(stringResource(R.string.tab_gesture), MiuixIcons.Tune),
    GlassNavItem(stringResource(R.string.tab_app), MiuixIcons.GridView),
    GlassNavItem(stringResource(R.string.tab_scene), MiuixIcons.Layers),
    GlassNavItem(stringResource(R.string.tab_settings), MiuixIcons.Settings),
)
