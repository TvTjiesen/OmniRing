package xyz.nextalone.hyperzoomring.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.ui.liquid.alphaWindow
import xyz.nextalone.hyperzoomring.ui.liquid.lens
import xyz.nextalone.hyperzoomring.ui.liquid.vibrancy

/**
 * iOS 那套"大标题 + 悬浮玻璃"顶栏的公共外壳。
 *
 * 这块被推翻过两次，教训记在这里免得再犯：
 *  1. 第一版是一条**均匀模糊 + 不透明色渐变**的宽带 —— 把大标题和 logo 一起盖住了，
 *     下边缘还是一刀切（用户原话"雷霆大模糊"）。
 *  2. 第二版改成 4 段不同半径的矩形直接摞起来 —— 段与段之间是硬切，看得见分界线。
 * 现在这版：
 *  - 顶栏中间**不放任何东西**（标题胶囊按用户要求删掉，页面名由大标题负责）；
 *  - 背后是三段**互相重叠、各自带软边**的渐变纱，软边由 AGSL 竖向 alpha 窗口实现
 *    （见 ui/liquid/VeilWindow.kt），叠加后模糊强度连续变化，没有任何一条横线；
 *  - 出现曲线有死区：大标题刚起步时完全不糊，过了分界点才 smoothstep 渐进出现。
 */
class PageChrome {
    /** 0 = 大标题还在原位（顶栏不出现），1 = 完全折叠 */
    var progress by mutableFloatStateOf(0f)
}

val LocalPageChrome = staticCompositionLocalOf { PageChrome() }

/** 当前页的大标题文案，由 MainContent 提供，Page 自动渲染成列表首项 */
val LocalLargeTitle = compositionLocalOf { "" }

/**
 * 这一页是不是"活动页"。
 *
 * 换页动画期间离场页还在组合树里，它也会报告自己的滚动量。不加这个开关，
 * 两个页面会抢同一个 progress，顶栏会在切页那一瞬闪一下。
 */
val LocalPageActive = compositionLocalOf { true }

/** 底部玻璃栏此刻在不在。二级页要把它收起来，页面底部留白也得跟着变小 */
val LocalNavBarVisible = compositionLocalOf { true }

/**
 * 每一页自己的"活着的状态"：滚动位置 + 已经播过入场动画的条目。
 *
 * AnimatedContent 换 targetState 时目标页整棵子树是重建的，不缓存的话
 * 从二级页返回就会看到"整页刷新一遍"：滚回顶部 + 交错动画重播（用户明确否掉的行为）。
 * MainContent 按 Screen 存一份，返回时复用同一个 LazyListState 和 played 集合。
 */
class PageSlot {
    var listState: androidx.compose.foundation.lazy.LazyListState? = null
    var played: MutableState<Set<Int>>? = null
}

val LocalPageSlot = compositionLocalOf { PageSlot() }

/** 打开"表冠沉浸态"的入口，由 MainContent 提供，设置页头图点它 */
val LocalOpenCrownStage = compositionLocalOf<(() -> Unit)?> { null }

/** 顶栏栏身高度（不含状态栏） */
val IosBarHeight = 52.dp

/** 渐变纱往下多延伸的一段：模糊在这里彻底淡到没有 */
private val IosTailHeight = 44.dp

/*
 * 渐变纱：**只采样一次**。
 *
 * 上一版是"三段重叠软窗口"各画一次 drawBackdrop，真机截图证明那是错的：
 * 同一个 backdrop 在一帧里被三个节点先后采样，从第二段起就采到空层，
 * 而 drawBackdrop 采样为空时会退回"铺一层 surface 底色" —— 三段白膜叠起来
 * 就是用户骂了三次的"顶部雷霆大白块"，并且带的尽头是一条硬直线（分界线明显）。
 *
 * 现在：一次模糊 + AGSL 竖向软窗口收到带子底边以下（淡到零，没有尽头那条线），
 * 并且 onDrawSurface 什么都不画 —— 就算哪天采样又变空，最坏是"没有纱"，
 * 绝不会再糊出一块白。
 */
private const val VEIL_BLUR_DP = 30f
private const val VEIL_WIN_START = 0f
private const val VEIL_WIN_END = 1.12f

/**
 * 出现曲线：前 [VEIL_DEAD_ZONE] 完全不出现（用户要的"分界点"），
 * 之后 smoothstep 平滑升到满，不会"啪"地一下糊上来。
 */
private const val VEIL_DEAD_ZONE = 0.22f

private fun veilAlphaOf(progress: Float): Float {
    val t = ((progress - VEIL_DEAD_ZONE) / (1f - VEIL_DEAD_ZONE)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
fun statusBarTopPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** 顶栏那一条的高度（状态栏 + 栏身），页面内容要让出来的距离 */
@Composable
fun iosHeaderInset(): Dp = statusBarTopPadding() + IosBarHeight

/**
 * 一颗液态玻璃面：vibrancy + 模糊 + 透镜折射 + 高光边。
 *
 * backdrop 为 null（系统不支持 runtime shader）时退化成半透明底色，不崩。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    blurDp: Dp = 12.dp,
    refractionDp: Dp = 14.dp,
    tintAlpha: Float = 0.20f,
    baseAlpha: Float = 0.88f,
    /**
     * false = 这颗面不采页面内容，只画"浅色实心 + 描边高光"。
     *
     * 为什么给这个开关：真机连拍证明 Miuix 0.9.2 的 vibrancy/lens 在采样层为空/错位时
     * 会直接画出不透明黑（那颗按钮从 735 掉到 445 并停住），垫浅色底也盖不住 ——
     * 因为黑是画在垫底之上的。44dp 的小按钮实时折射本来就看不出来，先用确定的浅色，
     * 别再拿一个"糊不出来就变黑"的路径赌。
     */
    liveBlur: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = isSystemInDarkTheme()
    val highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    // 玻璃底下必须先垫一层不透光的浅色底：Miuix 的 vibrancy/lens 在"采样层是空的"
    // 时候不会退回纯色，而是把透明黑直接混出来 —— 真机连拍量到那颗按钮从 750 掉到 442
    // 并且停住（就是他说的"点底栏之后右上角变黑"）。垫底之后：采到内容就是玻璃，
    // 采空就是浅色实心，最坏是"不糊"，绝不会再出黑块。
    Box(
        modifier = modifier
            .background(surface.copy(alpha = baseAlpha), shape)
            .then(
            if (liveBlur && backdrop != null && backdropIsLive(backdrop)) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(blurDp.toPx(), blurDp.toPx())
                        // depthEffect/chromaticAberration 只在热调打开时才用：空采样帧上
                        // 这两个会画出不透明黑（就是那颗钮之前"变黑"的来路）
                        if (GlassTuning.depthLens >= 1f) {
                            lens(
                                refractionHeight = refractionDp.toPx(),
                                refractionAmount = refractionDp.toPx() * 0.7f,
                                depthEffect = true,
                                chromaticAberration = 0.35f,
                            )
                        } else {
                            lens(
                                refractionHeight = refractionDp.toPx(),
                                refractionAmount = refractionDp.toPx() * 0.7f,
                            )
                        }
                    },
                    highlight = { highlight },
                    onDrawSurface = { drawRect(surface.copy(alpha = tintAlpha)) },
                )
            } else {
                Modifier.background(surface.copy(alpha = 0.72f), shape)
            }
        ),
        content = content,
    )
}

/**
 * 折叠顶栏。
 *
 * @param title 目前不显示在顶栏（标题胶囊已删）。保留参数是因为调用方按页算它最方便，
 *              以后要加回来不用再改一遍路由
 * @param actions 右上角槽位（重启作用域那颗玻璃钮）
 */
@Composable
fun IosTopBar(
    title: String,
    chrome: PageChrome,
    canGoBack: Boolean,
    onBack: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    forceHidden: Boolean = false,
    veilBlurDp: Float = GlassTuning.VEIL_DEFAULT_DP,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val surface = MiuixTheme.colorScheme.surface
    val statusTop = statusBarTopPadding()
    val bandHeight = statusTop + IosBarHeight + IosTailHeight
    val progress by animateFloatAsState(chrome.progress, label = "barCollapse")
    // 死区 + smoothstep：大标题刚起步时完全不糊
    // 沉浸态（表冠放大居中）时顶栏纱必须完全消失，否则会和居中大表冠打架
    val veilAlpha = if (forceHidden) 0f else veilAlphaOf(progress)

    Box(modifier = modifier.fillMaxWidth().height(bandHeight)) {
        if (backdrop != null && veilAlpha > 0.001f) {
            // 渐变靠"离屏层 + DstIn 竖向渐变"擦出来，不再用 AGSL 软窗口：
            // 真机截图证明 alphaWindow 在这台机器上没生效（带的下边缘仍是一条硬直线），
            // 而这条硬线正是用户说的"分界线明显"。DstIn 是纯 Compose 能力，不挑渲染器。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = veilAlpha
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                0.42f to Color.Black.copy(alpha = 0.62f),
                                0.78f to Color.Black.copy(alpha = 0.16f),
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            // 18dp 太弱：滚上去的分组标题在纱后面还认得出字，就是他说的"糊成一块灰"。
                            // 提到 30dp，再铺一层随折叠进度加深的同色底，让它读起来是材质而不是脏影。
                            // 底色压在 0.45 以下：万一某一帧没采到内容，最多是一条淡带，
                            // 不会退回上次那块挡字的"雷霆大白块"。
                            effects = { blur(veilBlurDp.dp.toPx(), veilBlurDp.dp.toPx()) },
                            onDrawSurface = { drawRect(surface.copy(alpha = 0.18f + 0.27f * progress)) },
                        ),
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.height(statusTop))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IosBarHeight)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                } else {
                    Box(Modifier.height(IosBarHeight).width(48.dp))
                }
                // 中间不放标题胶囊（用户要求）：大标题本身就是页面名
                Box(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** 大标题：列表的第 0 项，跟着内容滚，滚上去之后顶栏才渐进出现 */
@Composable
fun LargeTitle(text: String, topInset: Dp) {
    Text(
        text = text,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = topInset, bottom = 8.dp),
    )
}
