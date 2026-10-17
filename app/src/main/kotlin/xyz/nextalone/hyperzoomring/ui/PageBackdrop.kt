package xyz.nextalone.hyperzoomring.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 页面内容自己的采样层：由"当前这一页"创建，进场时贴给栏类读。
 *
 * 为什么不再由 MainActivity 建一个大家共用（这是 2026-10-15 定位到的根因）：
 * Miuix 0.9.2 的 `LayerBackdropNode.onDetach()` 会把 `backdrop.layerCoordinates`
 * 直接置成 null（反编译 miuix-blur-android-0.9.2 的字节码得到的死证据），而
 * `onGloballyPositioned` 只在真的重新布局时才把坐标补回来。我们原先所有页面在
 * AnimatedContent 里共享同一个对象 → **切页时离场页一 detach 就把坐标清了**，
 * 之后每个 `drawBackdrop` 都采到空层，退化成"半透明但没模糊"。
 * 这就是他报的"只有首次打开正常，之后都不正常"：首次打开还没切过页，坐标仍在。
 *
 * 也不许把 `layerBackdrop` 挂回包着整页的外层：外层再叠 AnimatedContent 自己的
 * graphicsLayer 会让拖拽坐标算歪，真机实测"点击有效、整 App 一律滚不动"
 * （摘掉外层 layerBackdrop 后同一次滑动能改 80 万像素，挂着就是 0）。
 *
 * 生命周期跟着页面 composition 走：AnimatedContent 同时最多存在两份页面，
 * 所以离屏 layer 的峰值就是 2 层，不会随访问过的页面数一直涨。
 *
 * @param recordColor 采样层里先铺的页面底色（透明区域不铺底的话模糊出来是糊成一片而不是"化开"）
 */
@Composable
fun rememberPageBackdrop(recordColor: Color): LayerBackdrop = rememberLayerBackdrop {
    drawRect(recordColor)
    drawContent()
}

/**
 * 写入口：在场页面把自己的采样层贴进来，顶栏/底栏读它的 value。
 *
 * 用 `MutableState` 而不是直接给值：换页时这个引用会变，栏类必须因此重新组合，
 * 否则它们会一直画第一次那一页的层。null = 现在没有任何页面在录内容（刚进场/正在退出）。
 */
val LocalBackdropSink = compositionLocalOf<MutableState<LayerBackdrop?>?> { null }

/**
 * 测量开关（adb `settings put global hzr_glass_off 1`）：true = 页面不挂采样层。
 *
 * 走 CompositionLocal 而不是让页面直接读 GlassTuning：读一个全局对象不会触发重组，
 * 他改完开关界面不动，量出来的数就是假的。
 */
val LocalGlassRecorderOff = compositionLocalOf { false }

/**
 * 自检用：现在这个采样层到底有没有坐标（coordinates == null 就是"实时模糊已经死了"）。
 *
 * 为什么不能只靠截图比像素：浅色主题下页面本身接近纯白，"糊没糊"在像素差上几乎看不出来，
 * 我上一轮就是被这个骗过一次（同一次滑动的位移用图像互相关量出来 255px 和 50px，
 * 换成 uiautomator 里"同一行文字的 y 差"之后两版都是 ~1489px，说明那个图像指标根本不可信）。
 * 坐标这个量 Miuix 只在 internal 面上给，所以反射拿它的方法名（带 Kotlin 修饰后缀）。
 */
fun backdropIsLive(backdrop: Any?): Boolean = backdropHasCoordinates(backdrop as? LayerBackdrop)

private fun backdropHasCoordinates(backdrop: LayerBackdrop?): Boolean {
    if (backdrop == null) return false
    return runCatching {
        val m = backdrop.javaClass.methods.firstOrNull { it.name.startsWith("getLayerCoordinates") }
            ?: return false
        m.invoke(backdrop) != null
    }.getOrDefault(false)
}
