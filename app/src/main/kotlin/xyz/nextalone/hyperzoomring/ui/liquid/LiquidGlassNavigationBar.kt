// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package xyz.nextalone.hyperzoomring.ui.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import xyz.nextalone.hyperzoomring.ui.liquid.animation.DampedDragAnimation
import xyz.nextalone.hyperzoomring.ui.liquid.animation.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.Platform
import top.yukonga.miuix.kmp.utils.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import xyz.nextalone.hyperzoomring.ui.GlassTuning
import xyz.nextalone.hyperzoomring.ui.backdropIsLive

private val LocalIosTabScale = staticCompositionLocalOf { { 1f } }

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors HighlightStyle.kt's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

@Composable
internal fun IosLiquidGlassNavigationBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    isBlurActive: Boolean,
    blurDp: Float = GlassTuning.BAR_DEFAULT_DP,
    lensDp: Float = GlassTuning.LENS_DEFAULT_DP,
    modifier: Modifier = Modifier,
) {
    val isDark = isInDarkTheme()
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurActive) surfaceContainer.copy(alpha = 0.4f) else surfaceContainer

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    // 越界橡皮筋：4dp 被反馈"整条栏晃太多"，收到 2dp —— 只留一点"拽不动"的手感
    val rubberBandPx = with(density) { 2.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    class DampedDragHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragHolder() }

    val dampedDrag = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false
                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val pad = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    pad + indicatorX + offset.x
                } else {
                    totalWidthPx - pad - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                } else {
                    animateToValue(targetIndex.toFloat())
                }
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) currentIndex = selectedIndex
    }
    val onItemClickUpdated by rememberUpdatedState(onItemClick)
    LaunchedEffect(dampedDrag) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDrag.animateToValue(index.toFloat())
            onItemClickUpdated(index)
        }
    }

    val interactiveHighlight = remember(animationScope, isLtr) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { layerSize, _ ->
                Offset(
                    x = if (isLtr) {
                        (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    } else {
                        layerSize.width - (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    },
                    y = layerSize.height / 2f,
                )
            },
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)

    // 滑块自己的高光：把 spec 那条斜向镜面转 90°，跟着重力走，胶囊边缘才有一道"玻璃棱"。
    // 2026-10-16 我上一版把滑块拆成纯色胶囊时顺手删了它，这版滑块还回来，它也得回来。
    val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

    /**
     * 拖拽时整条底栏跟着形变的量，-1.4..1.4，符号 = 手指方向。算法在 [SmearMath]（有单测）。
     *
     * 两个来源叠起来：
     *  1) 手指累计位移（offsetAnimation 累的就是原始 dragAmount.x，松手会被 spring 拉回 0，
     *      所以形变也跟着自然回弹，不需要额外一份动画）；
     *  2) 滑块当前位置和它目标位置的差。滑块是欠阻尼弹过去追手指的，
     *      这个差值就是"滑块跑到容器前面去了"，点切页时它也非零，于是整条栏会朝新 tab 倾一下再落回。
     */
    fun smear(): Float = SmearMath.smear(
        rawDragPx = offsetAnimation.value,
        tabWidthPx = tabWidthPx,
        leadUnits = dampedDrag.targetValue - dampedDrag.value,
        tabsCount = tabsCount,
    )

    /**
     * 形变落到 graphicsLayer 上的那三个值。
     *
     * 2026-10-16：只有玻璃容器一处用。滑块自己**不**加这个形变——它原来是靠 spring 欠阻尼
     * 追手指的，来回过冲时叠上 scaleX/scaleY 就是他说的"奇怪的抖动"；滑块只平移。
     * 保留 [Modifier.deformed] 的写法是因为容器和它背后的内容必须同步。
     */
    fun Modifier.deformed(): Modifier = graphicsLayer {
        val s = smear()
        translationX = panelOffset
        transformOrigin = TransformOrigin(SmearMath.pivotX(s), 0.5f)
        scaleX = SmearMath.scaleX(s)
        scaleY = SmearMath.scaleY(s)
    }

    val combinedBackdrop = backdrop?.let { rememberCombinedBackdrop(it, tabsBackdrop) }

    val navBarBottomPadding = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val bottomPaddingValue = when (platform()) {
        Platform.IOS -> 20.dp

        else -> {
            if (navBarBottomPadding != 0.dp) 8.dp + navBarBottomPadding else 36.dp
        }
    }

    /**
     * 五个 tab 本体。选中态由滑块胶囊标出来，同时文字+图标也变 accent 色——
     * 因为胶囊是半透明玻璃，底下再糊一层自己的副本就会"看不清字"，所以内容只画一份。
     *
     * 权重、字号一律不随选中变化：加粗会让文字变宽，切页时整排字都会横向抖一下。
     */
    val tabsContent: @Composable RowScope.() -> Unit = {
        val tabScale = LocalIosTabScale.current
        items.forEachIndexed { index, item ->
            val on = index == currentIndex
            CompositionLocalProvider(
                LocalContentColor provides if (on) accentColor else tabContentColor
            ) {
                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { currentIndex = index },
                        )
                        .semantics { selected = on }
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val sc = tabScale()
                            scaleX = sc
                            scaleY = sc
                        },
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = CenterHorizontally,
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = item.icon,
                        // Decorative: the adjacent label names the item; avoids TalkBack double-read.
                        contentDescription = null,
                    )
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(bottom = bottomPaddingValue, start = 24.dp, end = 24.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            /*
             * 底栏 = 三层，顺序就是遮挡关系（2026-10-16 深夜按 cfg27 原样还原）：
             *   1) 主 Row：玻璃容器 + 五个 tab 的图标文字；
             *   2) 镜像行：同一份 tabsContent，alpha(0f) 不显示，只负责被 layerBackdrop 录进
             *      tabsBackdrop，给滑块当折射素材；
             *   3) 滑块：采 combinedBackdrop（页面 + 镜像行），所以胶囊里能看到被折射的 tab 内容，
             *      并按按压/速度形变 —— 这就是他要的"之前那样"的玻璃滑块。
             *
             * 我之前两轮把它改坏的方式记录一下，别再犯：
             *   a) 以为"选中项发糊"是滑块的错，直接把滑块砍成纯色胶囊 —— 他要的是修糊 + 文字变色，
             *      不是删功能；
             *   b) 又自作主张把滑块降到文字底下、拆成三层 —— 那样滑块就只是一块糊玻璃，
             *      玻璃滑块的折射效果等于没有。
             * 保留下来的只有他自己要的"选中项文字/图标变 accent 色"（见 tabsContent）。
             */

            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                Row(
                    modifier = Modifier
                        .selectableGroup()
                        .onSizeChanged { coords ->
                            totalWidthPx = coords.width.toFloat()
                            val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                            tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                        }
                        .deformed()
                        .dropShadow(
                            shape = pillShape,
                            // 原来是硬黑 0.2 / 半径 10dp：深色页上贴边一圈发暗，
                            // 就是他说的"点击边缘有奇怪的黑条"。压低浓度、放宽半径当柔影用。
                            shadow = Shadow(
                                radius = 16.dp,
                                color = Color.Black,
                                alpha = if (isDark) 0.35f else 0.10f,
                            ),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .then(
                            if (isBlurActive && backdrop != null) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { pillShape },
                                    effects = {
                                        vibrancy()
                                        blur(blurDp.dp.toPx(), blurDp.dp.toPx())
                                        lens(
                                            refractionHeight = lensDp.dp.toPx(),
                                            refractionAmount = lensDp.dp.toPx(),
                                        )
                                    },
                                    highlight = { baseHighlight.copy(alpha = 0.75f) },
                                    layerBlock = {
                                        val width = size.width.coerceAtLeast(1f)
                                        val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDrag.pressProgress)
                                        scaleX = s
                                        scaleY = s
                                    },
                                    onDrawSurface = { drawRect(containerColor) },
                                )
                            } else {
                                Modifier
                                    .background(containerColor, pillShape)
                            },
                        )
                        .then(if (isBlurActive) interactiveHighlight.modifier else Modifier)
                        .height(64.dp)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = tabsContent,
                )
            }

            if (isBlurActive && backdrop != null) {
                CompositionLocalProvider(
                    LocalIosTabScale provides { lerp(1f, 1.2f, dampedDrag.pressProgress) },
                    LocalContentColor provides accentColor,
                ) {
                    Row(
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            // 和容器同一套形变：滑块折射到的这层内容必须跟着玻璃一起被拽长，
                            // 否则 pill 里的图标和外面那条会不同步
                            .deformed()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    vibrancy()
                                    blur(blurDp.dp.toPx(), blurDp.dp.toPx())
                                    lens(
                                        refractionHeight = lensDp.dp.toPx(),
                                        refractionAmount = lensDp.dp.toPx(),
                                    )
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                            .then(interactiveHighlight.modifier)
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = tabsContent,
                    )
                }
            }

            if (tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                if (isBlurActive && combinedBackdrop != null && backdropIsLive(tabsBackdrop)) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .graphicsLayer {
                                val singleTabWidth = tabWidthPx
                                val progressOffset = dampedDrag.value * singleTabWidth
                                translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                            }
                            .then(interactiveHighlight.gestureModifier)
                            .then(dampedDrag.modifier)
                            .drawBackdrop(
                                backdrop = combinedBackdrop,
                                shape = { pillShape },
                                effects = {
                                    val progress = dampedDrag.pressProgress
                                    if (GlassTuning.depthLens >= 1f) {
                                        lens(
                                            refractionHeight = 10.dp.toPx() * progress,
                                            refractionAmount = 14.dp.toPx() * progress,
                                            depthEffect = true,
                                            chromaticAberration = 0.5f,
                                        )
                                    } else {
                                        // 2026-10-16 深夜：这里原来写死 blur(6dp)。静止时
                                        // pressProgress = 0 → 折射量也是 0，胶囊里那格 tab 只剩糊，
                                        // 就是他报的"选中的那个直接模糊"。改成热调值，默认 0（不糊），
                                        // 空样本画黑的风险由上面 backdropIsLive(tabsBackdrop) 闸门兜住。
                                        // A/B：adb shell settings put global hzr_glass_pill_blur 6
                                        val pillBlur = GlassTuning.pillBlurDp
                                        if (pillBlur > 0f) {
                                            blur(pillBlur.dp.toPx(), pillBlur.dp.toPx())
                                        }
                                        lens(
                                            refractionHeight = 10.dp.toPx() * progress,
                                            refractionAmount = 14.dp.toPx() * progress,
                                        )
                                    }
                                },
                                highlight = { pillHighlight.copy(alpha = dampedDrag.pressProgress) },
                                layerBlock = {
                                    scaleX = dampedDrag.scaleX
                                    scaleY = dampedDrag.scaleY
                                    val v = dampedDrag.velocity / 10f
                                    scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                                    scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                                },
                                onDrawSurface = {
                                    val progress = dampedDrag.pressProgress
                                    drawRect(
                                        color = if (!isDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                },
                            )
                            .innerShadow(shape = pillShape) {
                                // 按下时长出来的这圈原来是纯黑 0.15，浅色下就是边缘一道黑；
                                // 深色下改成内侧提亮（玻璃受光），浅色下压到几乎看不出。
                                InnerShadow(
                                    radius = 8.dp * dampedDrag.pressProgress,
                                    color = if (isDark) Color.White.copy(alpha = 0.12f)
                                    else Color.Black.copy(alpha = 0.06f),
                                    alpha = dampedDrag.pressProgress,
                                )
                            }
                            .height(56.dp)
                            .width(tabWidthDp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .graphicsLayer {
                                val progressOffset = dampedDrag.value * tabWidthPx
                                translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                            }
                            .then(dampedDrag.modifier)
                            .clip(pillShape)
                            .background(accentColor.copy(alpha = 0.15f), pillShape)
                            .height(56.dp)
                            .width(tabWidthDp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides accentColor) {
                            Row(
                                modifier = Modifier
                                    .clearAndSetSemantics {}
                                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                    .requiredWidth(with(density) { (totalWidthPx - 8.dp.toPx()).toDp() })
                                    .height(56.dp)
                                    .graphicsLayer {
                                        val progressOffset = dampedDrag.value * tabWidthPx
                                        translationX = if (isLtr) -progressOffset else progressOffset
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                content = tabsContent,
                            )
                        }
                    }
                }
            }        }
    }
}
