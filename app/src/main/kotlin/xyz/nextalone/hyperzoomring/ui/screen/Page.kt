package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import xyz.nextalone.hyperzoomring.ui.LargeTitle
import xyz.nextalone.hyperzoomring.ui.LocalLargeTitle
import xyz.nextalone.hyperzoomring.ui.LocalPageActive
import xyz.nextalone.hyperzoomring.ui.LocalNavBarVisible
import xyz.nextalone.hyperzoomring.ui.LocalBackdropSink
import xyz.nextalone.hyperzoomring.ui.LocalGlassRecorderOff
import xyz.nextalone.hyperzoomring.ui.LocalPageChrome
import xyz.nextalone.hyperzoomring.ui.LocalPageSlot
import xyz.nextalone.hyperzoomring.ui.iosHeaderInset
import xyz.nextalone.hyperzoomring.ui.rememberPageBackdrop

/**
 * 页面通用内边距：底部留出玻璃条 + 手势条的高度。
 *
 * 二级页把玻璃条收起来了（见 MainActivity），这时还留 132dp 就是一大块无意义空白，
 * 所以留白跟着 LocalNavBarVisible 变。
 */
@Composable
fun pagePaddingDefault(): PaddingValues = PaddingValues(
    start = 12.dp,
    end = 12.dp,
    bottom = if (LocalNavBarVisible.current) 132.dp else 44.dp,
)

/** 交错节拍：每条晚起步 26ms，最多累到第 8 条，之后同时进场 */
private const val STAGGER_STEP_MS = 26L
private const val STAGGER_MAX_STEPS = 8

/**
 * 所有一级/二级页共用的骨架。
 *
 * 两件事：
 *  1. **大标题**：从 LocalLargeTitle 取当前页标题，作为列表第 0 项渲染，跟着内容一起滚，
 *     滚到顶就换成顶栏的小标题（见 ui/IosChrome.kt）。这样内容是从模糊带下面"穿过去"，
 *     不会再被顶栏切一刀。
 *  2. **条目交错浮入**：把业务传进来的 `content` 跑在一个代理 [LazyListScope] 上，
 *     它按调用顺序给每个条目编号，再把内容包一层 [Reveal] —— 九个页面一行都不用改。
 *     播过的编号记在 played 里：数据变化、滑出屏幕再滑回来都不重放。
 */
@Composable
fun Page(
    pagePadding: PaddingValues = pagePaddingDefault(),
    modifier: Modifier = Modifier,
    stagger: Boolean = true,
    largeTitle: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val slot = LocalPageSlot.current
    // 复用同一份滚动状态与"已播过"集合：从二级页返回时不重播、不回到顶部
    val state = slot.listState ?: rememberLazyListState().also { slot.listState = it }
    val played = slot.played ?: remember { mutableStateOf(emptySet<Int>()) }.also { slot.played = it }
    val risePx = with(LocalDensity.current) { 22.dp.toPx() }
    val chrome = LocalPageChrome.current
    val sink = LocalBackdropSink.current
    val active = LocalPageActive.current
    val title = if (largeTitle) LocalLargeTitle.current else ""
    val inset = iosHeaderInset()
    var titleHeightPx by remember { mutableFloatStateOf(1f) }

    // 这一页自己的采样层：底色用当前主题的 background 铺一层，再把页面内容画上去。
    // 生命周期跟着本 composition：离场时整份释放，不会把在场页面的坐标一起清掉。
    val recorderOff = LocalGlassRecorderOff.current
    val mine = rememberPageBackdrop(MiuixTheme.colorScheme.background)
    // 诊断：backdrop 的身份哈希换了没有 = 这个对象是不是每次重组都被重建。
    // cfg18 上机后一次受控上滑的位移从 255px 掉到 50px，第一嫌疑就是它在抖。
    androidx.compose.runtime.LaunchedEffect(mine) {
        android.util.Log.i(
            "HyperZoomRingUI",
            "backdrop 身份=${System.identityHashCode(mine)} 在场=$active 挂采样层=${!recorderOff}",
        )
    }
    // 只有"在场"的那一页有权把采样层交给栏类；SideEffect 在 composition 应用后执行，
    // 所以在场页第一帧画出来之前，栏类最多还引用着上一页（一帧内会被换掉）。
    // 交出去之前先让它画满两帧：栏类（右上角那颗玻璃钮、底栏）拿到一个"还没录过内容"
    // 的层时，vibrancy/lens 会渲出一块黑 —— 真机连拍量到均值从 750 掉到 442 并且停住。
    LaunchedEffect(active, mine) {
        if (!active) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (active && sink != null && sink.value !== mine) sink.value = mine
    }
    // 离场时如果栏类还指着我这一份，退回 null（纯色），不留一个已经释放的层给它采
    DisposableEffect(mine) {
        onDispose { if (sink?.value === mine) sink.value = null }
    }

    // 折叠度 = 大标题被滚出去的比例。只用 firstVisibleItem 的信息，
    // 不接嵌套滚动，也就不需要给每个页面传 scrollBehavior。
    LaunchedEffect(state, title, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            if (title.isEmpty()) 1f
            else if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / titleHeightPx.coerceAtLeast(1f))
        }.collect { chrome.progress = it.coerceIn(0f, 1f) }
    }

    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            // 采样层挂在滚动容器自己身上，而且**这一页一份**（见 ui/PageBackdrop.kt：
            // 共享一个的话，切页时离场页 onDetach 会把它的 layerCoordinates 清成 null，
            // 之后所有 drawBackdrop 采到空层 → 实时模糊全丢）
            .then(if (recorderOff) Modifier else Modifier.layerBackdrop(mine)),
        contentPadding = pagePadding,
        content = {
            if (title.isNotEmpty()) {
                item(key = "large_title") {
                    Box(
                        modifier = Modifier.onSizeChanged {
                            if (it.height > 0) titleHeightPx = it.height.toFloat()
                        }
                    ) {
                        LargeTitle(text = title, topInset = inset)
                    }
                }
            }
            if (stagger) {
                content(RevealScope(this, played, risePx))
            } else {
                content(this)
            }
        },
    )
}

/**
 * 代理 LazyListScope：给每个条目按声明顺序编号，并包上入场动画。
 *
 * key / contentType 一律透传业务给的（业务用 `key = { it.name }` 这种稳定 key，
 * 换成序号会让滚动位置和动画状态在数据变动时错位）。
 */
private class RevealScope(
    private val delegate: LazyListScope,
    private val played: MutableState<Set<Int>>,
    private val risePx: Float,
) : LazyListScope {

    private var next = 0

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val index = next++
        // 先把属性取成局部量：@Composable lambda 里直接读外部类的 private 属性，
        // Kotlin 会报"cannot be called with an implicit receiver"
        val p = played
        val r = risePx
        delegate.item(key, contentType) {
            // 这里接收者是 LazyItemScope，content 要在这个接收者下调用
            Reveal(index, p, r) { content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val base = next
        next += count
        val p = played
        val r = risePx
        delegate.items(
            count = count,
            key = key,
            contentType = contentType,
        ) { local ->
            Reveal(base + local, p, r) { itemContent(local) }
        }
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val index = next++
        val p = played
        val r = risePx
        delegate.stickyHeader(key, contentType) {
            Reveal(index, p, r) { content() }
        }
    }
}

/**
 * 单条目的入场：淡入 + 上浮，序号越大起步越晚。
 *
 * 只管前 [STAGGER_MAX_STEPS] 条：一是"页面刚进来时那一下"要的就是这个，
 * 二是 played 这个集合会被每个条目在组合期读一次，长列表（应用页上百条）
 * 如果每条都登记，进页时就是 O(n²) 次重组合。后面的条目直接画出来。
 */
@Composable
private fun Reveal(
    index: Int,
    played: MutableState<Set<Int>>,
    risePx: Float,
    content: @Composable () -> Unit,
) {
    if (index > STAGGER_MAX_STEPS) {
        content()
        return
    }
    val seen = index in played.value
    val anim = remember { Animatable(if (seen) 1f else 0f) }
    LaunchedEffect(index) {
        if (index !in played.value) {
            played.value = played.value + index
            delay(index * STAGGER_STEP_MS)
            anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * risePx
        },
    ) {
        content()
    }
}

/** 分组标题（带左右缩进，和卡片对齐） */
@Composable
fun SectionTitle(text: String, top: Boolean = false) {
    SmallTitle(
        text = text,
        modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = if (top) 4.dp else 18.dp, bottom = 4.dp),
    )
}

/**
 * iOS 设置里那种"左边名称、右边取值"的一行。
 * 取值统一右对齐、次要色，长值换行时靠右，和系统设置一致。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    lastLine: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = label,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                fontSize = 17.sp,
                color = valueColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1.2f),
            )
        }
        if (!lastLine) {
            Hairline()
        }
    }
}

/** 分隔线：iOS 的是从文字起始处开始的细线，不顶到卡片左边缘 */
@Composable
fun Hairline(indent: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .height(0.6.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)),
    )
}
