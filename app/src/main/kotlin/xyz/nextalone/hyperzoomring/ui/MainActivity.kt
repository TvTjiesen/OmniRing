package xyz.nextalone.hyperzoomring.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.R
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.DefaultMapping
import xyz.nextalone.hyperzoomring.config.ConfigScope
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.util.ConfigPush
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ui.screen.AppGestureOverviewScreen
import xyz.nextalone.hyperzoomring.ui.screen.AppListScreen
import xyz.nextalone.hyperzoomring.ui.screen.CreditsScreen
import xyz.nextalone.hyperzoomring.ui.screen.DiagnosticScreen
import xyz.nextalone.hyperzoomring.ui.screen.GestureConfigScreen
import xyz.nextalone.hyperzoomring.ui.screen.GestureListScreen
import xyz.nextalone.hyperzoomring.ui.screen.HomeScreen
import xyz.nextalone.hyperzoomring.ui.screen.LanguageScreen
import xyz.nextalone.hyperzoomring.ui.screen.ModeConfigScreen
import xyz.nextalone.hyperzoomring.ui.screen.SceneGestureOverviewScreen
import xyz.nextalone.hyperzoomring.ui.screen.SceneListScreen
import xyz.nextalone.hyperzoomring.ui.LocalNavBarVisible
import xyz.nextalone.hyperzoomring.ui.LocalPageSlot
import xyz.nextalone.hyperzoomring.ui.screen.SettingsScreen
import xyz.nextalone.hyperzoomring.ui.screen.ThemeScreen
import xyz.nextalone.hyperzoomring.util.ScopeReload
import java.util.Locale

sealed class Screen {
    data object Home : Screen()
    data object GestureTab : Screen()
    data object AppTab : Screen()
    data object SceneTab : Screen()
    data object Settings : Screen()
    data object Theme : Screen()
    data object Credits : Screen()
    data object Diagnostic : Screen()
    data object ModeConfig : Screen()
    data object Language : Screen()
    data class GestureConfig(val gesture: GestureType, val scope: ConfigScope) : Screen()
    data class AppGestureOverview(val packageName: String) : Screen()
    data class SceneGestureOverview(val scene: SceneType) : Screen()
}

private var lastRingAt = 0L

class MainActivity : ComponentActivity() {

    /**
     * 应用内语言：设置里选了就用选的，没选就跟系统。
     * 必须在 attachBaseContext 包一层，在 Compose 里改换不了资源语言。
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = runCatching { ConfigManager.fromContext(newBase).language }.getOrDefault("")
        super.attachBaseContext(if (lang.isBlank()) newBase else newBase.wrapLocale(lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 全新安装（或 App 数据被清）时四个槽位全空，钩子拿到空映射就是"转环没反应"，
        // 所以推送之前先种一次默认映射（只种一次，见 DefaultMapping）
        runCatching { DefaultMapping.seedIfNeeded(ConfigManager.fromContext(this)) }
        // 把配置推给 system_server：它读不到我们的私有 prefs 文件，
        // 不推的话改了手势要重启框架才生效（ConfigPush 里还有"监听写入即推"和"开机被问就答"两条）
        ConfigPush.attach(this)
        setContent {
            val context = LocalContext.current
            val config = remember { ConfigManager.fromContext(context) }
            // 改完主题把这几个值重读一遍，AppTheme 就会重建整套色板
            var mode by remember { mutableIntStateOf(config.themeMode) }
            var keyColor by remember { mutableIntStateOf(config.themeKeyColor) }
            var spec by remember { mutableIntStateOf(config.themeColorSpec) }
            var style by remember { mutableIntStateOf(config.themePaletteStyle) }
            AppTheme(
                colorMode = mode,
                keyColor = if (keyColor == 0) null else Color(keyColor),
                colorSpec = spec,
                paletteStyle = style,
            ) {
                MainContent(
                    onThemeChanged = {
                        mode = config.themeMode
                        keyColor = config.themeKeyColor
                        spec = config.themeColorSpec
                        style = config.themePaletteStyle
                    },
                    onRecreate = { recreate() },
                )
            }
        }
    }
}

private fun Context.wrapLocale(tag: String): Context {
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val cfg = android.content.res.Configuration(resources.configuration)
    cfg.setLocale(locale)
    return createConfigurationContext(cfg)
}

@Composable
private fun MainContent(
    onThemeChanged: () -> Unit = {},
    onRecreate: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    // 每个 tab 有自己的返回栈：切走再回来还在原来那一页，不会被甩回根页
    var stacks by remember { mutableStateOf(List(5) { emptyList<Screen>() }) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    // 页面切换方向：+1 新页从右边进来，-1 从左边，0 原地淡入淡出。
    // 换页前先把方向写好，transitionSpec 读它，动画就知道该往哪边滑。
    var navDir by remember { mutableIntStateOf(0) }

    val root = when (tab) {
        1 -> Screen.GestureTab
        2 -> Screen.AppTab
        3 -> Screen.SceneTab
        4 -> Screen.Settings
        else -> Screen.Home
    }
    val stack = stacks[tab]
    val current = stack.lastOrNull() ?: root

    fun push(screen: Screen) {
        navDir = 1
        stacks = stacks.toMutableList().also { it[tab] = it[tab] + screen }
    }
    fun pop() {
        navDir = -1
        stacks = stacks.toMutableList().also { it[tab] = it[tab].dropLast(1) }
        refreshTrigger++
    }

    // 深色下页面必须是纯黑：Miuix 的 background token 在深色下太亮（~#232326），
    // 和卡片揉到一起分不出边界。backdrop 先铺一层纯色底再把内容浮在上面。
    // 用 MiuixTheme 的 background 而不是 isSystemInDarkTheme()：后者读的是系统深色开关，
    // 但 App 里选了深色时系统可能还在浅色（ui_night_mode=3 跟随亮度，白天=浅）。
    // 玻璃热调探针：adb 改完 0.8 秒内生效，不用重编（见 GlassTuning）
    //
    // 必须在 IO 线程读：Settings.Global.getString 每次都是一趟到 SettingsProvider 的
    // binder 调用，而我们自己那条心跳/状态每 2 秒就在往这个 provider 写。
    // 之前这五个读是**在主线程**每 400ms 打一次的（LaunchedEffect 默认跟随 composition
    // 的 dispatcher），provider 锁一紧主线程就跟着卡 —— 他报的"app 内频繁丢触控，
    // 必须反复点击底栏才能好"里，"整个页面点不动、栏区还能响应"就是主线程被 binder
    // 拖住的典型形状。现在只换线程和节拍，逻辑一个字没动。
    var glassTick by remember { mutableIntStateOf(0) }
    val glassCtx = LocalContext.current
    // 事件驱动：adb 改一个 hzr_* 键，SettingsProvider 直接通知过来。
    // 之前是"每 800ms 去捞 7 个键"= 每分钟 525 次 binder 往返，而我们只是在调试。
    DisposableEffect(Unit) {
        val handle = GlassTuning.startObserving(glassCtx) { glassTick++ }
        // 起来先同步一次，别等兜底节拍
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            if (GlassTuning.refresh(glassCtx)) glassTick++
        }
        onDispose { handle.close() }
    }
    // 兜底节拍：观察器在这台 ROM 上到底响不响，用 obs= / poll= 两条计数在 logcat 里对，
    // 别凭感觉。15 秒一跳 = 每分钟 28 次读，比原来的 525 次少 95%。
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            GlassTuning.notePoll()
            val changed = withContext(Dispatchers.IO) { GlassTuning.refresh(glassCtx) }
            if (changed) glassTick++
        }
    }
    val glassBarBlur = remember(glassTick) { GlassTuning.barBlurDp }
    val glassLens = remember(glassTick) { GlassTuning.lensDp }
    // 右上角那颗钮要不要真采页面内容：必须在这里用 glassTick 算，
    // 传进子组件才会真的跟着热调重组（在子组件里直读 GlassTuning 是量不出来的）
    val glassBtnLive = remember(glassTick) { GlassTuning.btnLive >= 1f }
    val glassVeilBlur = remember(glassTick) { GlassTuning.veilBlurDp }
    val pageColor = MiuixTheme.colorScheme.background
    // 谁在场谁录内容：这个引用由在场页面贴进来（见 ui/PageBackdrop.kt 的字节码证据），
    // 顶栏/底栏只读它，不再和任何页面共享同一个采样层对象。
    val activeBackdrop = remember { mutableStateOf<top.yukonga.miuix.kmp.blur.LayerBackdrop?>(null) }
    // adb: settings put global hzr_debug 1 之后，每 2 秒回显一次
    // "栏类现在采的那个层还有没有坐标"。这是"实时模糊活着没"的直接判据，不用猜像素。
    // 注意这里**不再打 binder**：hzr_debug 由 refresh()（观察器 + 兜底节拍）读进
    // GlassTuning.debugLog，这一跳只看内存里的位。
    LaunchedEffect(Unit) {
        while (true) {
            if (GlassTuning.debugLog) {
                val line = "coords=" +
                    (if (backdropIsLive(activeBackdrop.value)) "ok" else "null") +
                    " sink=${System.identityHashCode(activeBackdrop.value)}" +
                    " off=${GlassTuning.recorderOff.toInt()}" +
                    " obs=${GlassTuning.observerHits} poll=${GlassTuning.pollHits}"
                // 普通 App 写 Settings.Global 会被拒（没有 WRITE_SECURE_SETTINGS），
                // 所以这条走 logcat：adb 侧 grep "GLASS" 就能读到，别指望 hzr_glass_state 有值。
                android.util.Log.i("HyperZoomRingUI", "GLASS $line")
                withContext(Dispatchers.IO) {
                    runCatching {
                        android.provider.Settings.Global.putString(glassCtx.contentResolver, "hzr_glass_state", line)
                    }.onFailure { android.util.Log.w("HyperZoomRingUI", "GLASS 写 global 被拒：${it.javaClass.simpleName}") }
                }
            }
            delay(2000)
        }
    }

    var showRestartSheet by remember { mutableStateOf(false) }
    // 表冠沉浸态：角度由"变焦环事件"驱动，收不到事件时降级成手指转
    var crownStage by remember { mutableStateOf(false) }
    var stageAngle by remember { mutableFloatStateOf(0f) }
    var ringAlive by remember { mutableStateOf(false) }

    if (crownStage) {
        // 沉浸态优先吃掉返回键，先飞回去再谈别的
        BackHandler(onBack = { crownStage = false })
    } else if (stack.isNotEmpty()) BackHandler(onBack = { pop() })

    // 顶栏折叠度由"当前这一页"报告；离场页不再写，否则切页那一瞬两边抢同一个值会闪
    val chrome = remember { PageChrome() }
    // 每页一份 PageSlot：返回时复用滚动位置和已播动画，不"整页刷新"
    val pageSlots = remember { mutableStateMapOf<Screen, PageSlot>() }
    // 每页一份 PageSlot，键是"这一块内容自己是哪个 Screen"（见下面 AnimatedContent 里的 pageSlot），
    // 不能用 current：切页那一瞬离场页也会用 current 去取槽位，于是离场那页把自己那份
    // LazyListState 写进"下一页"的槽位里，下一页复用到一根不属于它的滚动状态 ——
    // 真机实测：手势->设置 之后设置页 uiautomator 里 scrollable=true 的节点从 1 变成 0，
    // 整页滑不动但点击有效（因为列表自己认为"内容没超出可视区"）。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadState by remember { mutableStateOf<ScopeReload.State>(ScopeReload.State.Unknown) }
    var reloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadTick, current) { reloadState = ScopeReload.state(context) }

    // 左右滑动切功能区（他要的：现在只能点底栏，横滑也要能换）
    // 只在一级页生效：二级页里横滑换 tab 会把返回栈甩乱；沉浸态里也不抢。
    fun gotoTab(i: Int) {
        val t = i.coerceIn(0, 4)
        if (t == tab) return
        navDir = if (t > tab) 1 else -1
        tab = t
    }

    // 沉浸态期间订阅模块发的事件广播（200ms 节流那条）：一次转动 = 一格旋转。
    // 只在这个覆盖层可见时注册，平时一个 receiver 都不留。
    if (crownStage) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    val g = intent.getStringExtra(
                        xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.EXTRA_GESTURE
                    ) ?: return
                    // 优先按"这一拍真实转过的量"走，收不到 units 时才退回按手势给固定角度
                    val dir = intent.getIntExtra(
                        xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.EXTRA_DIRECTION, 0
                    )
                    val units = intent.getIntExtra(
                        xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.EXTRA_VALUE, 0
                    )
                    val d = if (dir != 0 && units != 0) {
                        stageAngleDeltaUnits(dir, units, stageDegPerUnit(ctx))
                    } else {
                        stageAngleDelta(g)
                    }
                    if (d != 0f) {
                        stageAngle += d
                        ringAlive = true
                        lastRingAt = SystemClock.elapsedRealtime()
                    }
                }
            }
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(
                    xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.ACTION_ZOOM_RING_EVENT
                ),
                android.content.Context.RECEIVER_EXPORTED,
            )
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
                // 租约清零：不发的话退出沉浸态后最长 3 秒表冠不干活
                xyz.nextalone.hyperzoomring.util.ConfigPush.sendStageLease(0L)
            }
        }
        LaunchedEffect(Unit) {
            // 每 1 秒续一次 3 秒的静默租约，让 system_server 这期间只发环事件、
            // 不执行映射（否则它注入到屏幕正中的那根手指会被表盘当成点击，一转就退出）
            xyz.nextalone.hyperzoomring.util.ConfigPush.sendStageLease(
                SystemClock.elapsedRealtime() + 3_000L
            )
            while (true) {
                delay(1000)
                xyz.nextalone.hyperzoomring.util.ConfigPush.sendStageLease(
                    SystemClock.elapsedRealtime() + 3_000L
                )
                ringAlive = SystemClock.elapsedRealtime() - lastRingAt < 5000
            }
        }
    }

    // 应用内的表冠：模块已经不再往我们自己的屏幕上注假手指了（那根手指会落进
    // 我们自己的卡片里，位移不够就被判成点击 = 误触），改成这里直接滚当前页。
    //
    // 2026-10-16 他报"在 app 里转变焦环顿顿的"：根因是环事件 200ms 一拍，而原来
    // 一拍就直接 scrollBy 一次 —— 滚动成了"停一下、窜一下"的台阶。现在每一拍只往
    // target 上累加像素，由下面的帧循环逐帧追平（临界阻尼、不过冲），台阶被摊成连续位移。
    // 手感热调（不用重装）：hzr_ring_px_per_unit（灵敏度）、hzr_ring_tau_ms（平滑度，0=旧行为）
    if (!crownStage) {
        val ringEase = remember { RingEaseState() }
        val ringKick = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
        DisposableEffect(Unit) {
            val ringReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val dir = i.getIntExtra(
                        xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.EXTRA_DIRECTION, 0
                    )
                    val units = i.getIntExtra(
                        xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.EXTRA_VALUE, 0
                    )
                    if (dir == 0 || units == 0) return
                    val px = dir * units * ringPxPerUnit(c)
                    ringEase.target += px
                    ringEase.ticks++
                    ringEase.demandedPx += kotlin.math.abs(px)
                    ringKick.trySend(Unit)
                }
            }
            context.registerReceiver(
                ringReceiver,
                android.content.IntentFilter(
                    xyz.nextalone.hyperzoomring.hook.InputInterceptorHook.ACTION_ZOOM_RING_EVENT
                ),
                android.content.Context.RECEIVER_EXPORTED,
            )
            onDispose { runCatching { context.unregisterReceiver(ringReceiver) } }
        }
        LaunchedEffect(current) {
            // 换页必须清零：不然上一页没追平的差额会在这一页开头凭空窜一下
            ringEase.reset()
            while (true) {
                ringKick.receive()
                var lastFrame = -1L
                var frames = 0
                var busy = 0
                var maxMove = 0f
                var moved = 0f
                var idleFrames = 0
                var noList = false
                var lastTau = 0f
                while (!RingEase.converged(ringEase.target, ringEase.applied)) {
                    val frame = androidx.compose.runtime.withFrameNanos { it }
                    val dt = if (lastFrame < 0L) 16f else (frame - lastFrame) / 1_000_000f
                    lastFrame = frame
                    val st = pageSlots[current]?.listState
                    if (st == null) {
                        // 这一页没有可滚列表（或还没组合出来）：最多试 2 秒就认输，
                        // 绝不逐帧空转，否则安静放着也一直在等 vsync
                        idleFrames++
                        if (idleFrames > 120) {
                            noList = true
                            ringEase.applied = ringEase.target
                        }
                        continue
                    }
                    val tau = ringTauMs(context)
                    val (move, applied) = RingEase.step(
                        ringEase.target, ringEase.applied, dt, tau, ringMaxStepPx(context),
                    )
                    ringEase.applied = applied
                    frames++
                    val a = kotlin.math.abs(move)
                    moved += a
                    if (a >= 1f) busy++
                    if (a > maxMove) maxMove = a
                    if (move != 0f) runCatching { st.scrollBy(move) }
                    if (frames == 1) lastTau = tau
                }
                // 每"一组连续转动"收敛后打一条手感证据（判据见 RingEaseState 注释）：
                // busy/frames 是"有货且在动的帧占比"，旧直驱只有 1 帧在动；maxFrame 接近
                // demand 就说明台阶没被摊开。
                android.util.Log.i(
                    "hzr-ring",
                    String.format(
                        java.util.Locale.US,
                        "RING_EASE ticks=%d demand=%.0fpx frames=%d busy=%d maxFrame=%.1fpx " +
                            "moved=%.0fpx tau=%.1fms noList=%b build=%s",
                        ringEase.ticks, ringEase.demandedPx, frames, busy, maxMove,
                        moved, lastTau, noList,
                        xyz.nextalone.hyperzoomring.hook.DebugProbe.BUILD_TAG
                    )
                )
                ringEase.ticks = 0
                ringEase.demandedPx = 0f
            }
        }
    }

    // 根容器必须主动铺主题背景色：窗口主题是 Theme.Material.Light（白底），
    // 不铺的话深色模式下卡片之间、页面边缘全露出白底，就是他看到的"大面积白色"。
    //
    // 这里**不再**挂 layerBackdrop（原来由 hzr_glass_root 控制，2026-10-15 撤掉）：
    // 挂在外层会和 AnimatedContent 自己的 graphicsLayer 叠成两层，真机实测
    // "点击有效、整 App 一律滚不动"；采样层一律挂在滚动容器自己身上。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor)
            // 横向拖：越过 110px 就换功能区。只吃横向，纵向滚动和卡片点击都在子节点先拿到。
            .pointerInput(stack.isEmpty(), crownStage) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total < -110f -> gotoTab(tab + 1)
                            total > 110f -> gotoTab(tab - 1)
                        }
                        total = 0f
                    },
                    onHorizontalDrag = { _, d -> total += d },
                )
            }
            // 只观察、一个事件都不 consume：这条的作用仅仅是"告诉钩子用户现在正用手"。
            // 为什么必须告诉它 —— 实测（2026-10-16）我们注入的那根手指压在屏上时，
            // 真实那一滑只推动 57px（单独打能推 ~1480px），我们在和他抢 pointerId=0
            // 这一根手指，列表把他的触摸当成第二根手指直接忽略 = 他报的"app 内丢触控"。
            // 租约每 ~200ms 续一次、600ms 到期：广播万一丢了，最多 600ms 表冠就恢复。
            .pointerInput(Unit) {
                var lastSent = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var id = down.id
                    var pressed = true
                    while (pressed) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSent > 200L) {
                            lastSent = now
                            xyz.nextalone.hyperzoomring.util.ConfigPush.sendTouchLease(now + 600L)
                        }
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        pressed = change.pressed
                        id = change.id
                    }
                    xyz.nextalone.hyperzoomring.util.ConfigPush.sendTouchLease(
                        SystemClock.elapsedRealtime() + 400L
                    )
                }
            }
    ) {
        // 换页动画：方向由 navDir 决定（前进从右、后退从左、切 tab 同向），
        // 进出各带一点缩放，看起来像整页玻璃被推过去而不是硬切。
        AnimatedContent(
            targetState = current,
            // 采样层挂在页面自己的滚动容器上（见 ui/PageBackdrop.kt）：
            // 顶栏和底栏都在它外面，否则模糊层会采样到自己，滚起来是一团递归的糊
            modifier = Modifier
                .fillMaxSize(),
            transitionSpec = {
                val d = navDir
                val slide = tween<IntOffset>(280, easing = FastOutSlowInEasing)
                val fade = tween<Float>(200, easing = FastOutSlowInEasing)
                if (d == 0) {
                    (fadeIn(fade) + scaleIn(fade, initialScale = 0.985f))
                        .togetherWith(fadeOut(fade) + scaleOut(fade, targetScale = 0.99f))
                } else {
                    (
                        fadeIn(fade) +
                            slideInHorizontally(slide) { it / 5 * d } +
                            scaleIn(fade, initialScale = 0.97f)
                        )
                        .togetherWith(
                            fadeOut(tween(160)) +
                                slideOutHorizontally(slide) { -it / 9 * d } +
                                scaleOut(fade, targetScale = 0.98f)
                        )
                }
            },
        ) { s ->
            val mod = Modifier.fillMaxSize()
            val pageSlot = remember(s) { pageSlots.getOrPut(s) { PageSlot() } }
            CompositionLocalProvider(
                LocalPageChrome provides chrome,
                LocalLargeTitle provides titleOf(s),
                LocalPageActive provides (s == current),
                LocalNavBarVisible provides stack.isEmpty(),
                LocalPageSlot provides pageSlot,
                LocalBackdropSink provides activeBackdrop,
                LocalOpenCrownStage provides { crownStage = true },
                // 测量开关：读一次就把它变成 compositionLocal，页面才会跟着重组
                LocalGlassRecorderOff provides (GlassTuning.recorderOff >= 1f),
            ) {
                when (s) {
                    Screen.Home -> HomeScreen(
                        modifier = mod,
                        onNavigate = { push(it) },
                        refreshTrigger = refreshTrigger,
                    )
                    Screen.GestureTab -> GestureListScreen(
                        modifier = mod,
                        onGestureClick = { push(Screen.GestureConfig(it, ConfigScope.Global)) },
                    )
                    Screen.AppTab -> AppListScreen(
                        modifier = mod,
                        onAppClick = { push(Screen.AppGestureOverview(it)) },
                    )
                    Screen.SceneTab -> SceneListScreen(
                        modifier = mod,
                        onSceneClick = { push(Screen.SceneGestureOverview(it)) },
                    )
                    Screen.Settings -> SettingsScreen(
                        modifier = mod,
                        onNavigate = { push(it) },
                        onThemeChanged = onThemeChanged,
                        onLanguageChanged = onRecreate,
                        refreshTrigger = refreshTrigger,
                    )
                    Screen.Theme -> ThemeScreen(
                        modifier = mod,
                        onThemeChanged = onThemeChanged,
                        refreshTrigger = refreshTrigger,
                    )
                    Screen.Credits -> CreditsScreen(modifier = mod)
                    Screen.Diagnostic -> DiagnosticScreen(modifier = mod)
                    Screen.ModeConfig -> ModeConfigScreen(modifier = mod)
                    Screen.Language -> LanguageScreen(
                        modifier = mod,
                        onLanguageChanged = onRecreate,
                        refreshTrigger = refreshTrigger,
                    )
                    is Screen.GestureConfig -> GestureConfigScreen(
                        gestureType = s.gesture,
                        scope = s.scope,
                        modifier = mod,
                    )
                    is Screen.AppGestureOverview -> AppGestureOverviewScreen(
                        packageName = s.packageName,
                        onGestureClick = { push(Screen.GestureConfig(it, ConfigScope.App(s.packageName))) },
                    )
                    is Screen.SceneGestureOverview -> SceneGestureOverviewScreen(
                        scene = s.scene,
                        onGestureClick = { push(Screen.GestureConfig(it, ConfigScope.Scene(s.scene))) },
                    )
                }
            }
        }

        // 顶栏浮在内容之上：内容从它的模糊带下面穿过去，文字是被"化开"而不是被切断
        IosTopBar(
            title = titleOf(current),
            chrome = chrome,
            canGoBack = stack.isNotEmpty(),
            onBack = { pop() },
            backdrop = activeBackdrop.value,
            forceHidden = crownStage,
            veilBlurDp = glassVeilBlur,
            actions = {
                // 设置页顶部是那张可交互 logo 头图，再挂一颗按钮就挤了（用户要求只在设置页去掉）
                if (current != Screen.Settings) RestartScopeButton(
                    pending = reloadState == ScopeReload.State.ReloadPending,
                    onClick = { showRestartSheet = true },
                    backdrop = activeBackdrop.value,
                    liveBlur = glassBtnLive,
                )
            },
        )

        // 底栏是"一级 tab 之间的导航"，进了二级页还留着就是逻辑混乱（用户明确指出）。
        // 收起来用向下滑出 + 淡出，返回时滑入；页面底部留白由 LocalNavBarVisible 跟着变。
        AnimatedVisibility(
            visible = stack.isEmpty(),
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
                initialOffsetY = { it },
            ) + fadeIn(tween(160)),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 1.0f, stiffness = 900f),
                targetOffsetY = { it },
            ) + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            GlassNavBar(
                items = rememberTabs(),
                selected = tab,
                onSelect = {
                    navDir = if (it > tab) 1 else if (it < tab) -1 else 0
                    tab = it
                },
                backdrop = activeBackdrop.value,
                blurDp = glassBarBlur,
                lensDp = glassLens,
            )
        }

        CrownStage(
            visible = crownStage,
            targetAngle = stageAngle,
            ringAlive = ringAlive,
            backdrop = activeBackdrop.value,
            onDismiss = { crownStage = false },
            onDragAngle = { stageAngle += it },
        )

        RestartScopeSheet(
            show = showRestartSheet,
            backdrop = activeBackdrop.value,
            onDismiss = { showRestartSheet = false },
            onConfirm = {
                showRestartSheet = false
                val doneText = context.getString(R.string.settings_restart_done)
                val noRootText = context.getString(R.string.settings_restart_denied)
                val failText = context.getString(R.string.settings_restart_failed)
                scope.launch(Dispatchers.IO) {
                    val result = ScopeReload.restartFramework()
                    val msg = when (result) {
                        ScopeReload.RestartResult.Started -> doneText
                        ScopeReload.RestartResult.NoRoot -> noRootText
                        else -> failText
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        reloadTick++
                    }
                }
            },
        )
    }
}

@Composable
private fun titleOf(screen: Screen): String = when (screen) {
    Screen.Home -> stringResource(R.string.app_name)
    Screen.GestureTab -> stringResource(R.string.tab_gesture)
    Screen.AppTab -> stringResource(R.string.tab_app)
    Screen.SceneTab -> stringResource(R.string.tab_scene)
    Screen.Settings -> stringResource(R.string.tab_settings)
    Screen.Diagnostic -> stringResource(R.string.diag_title)
    Screen.Theme -> stringResource(R.string.settings_theme)
    Screen.Credits -> stringResource(R.string.settings_credits)
    Screen.ModeConfig -> stringResource(R.string.home_dispatch)
    Screen.Language -> stringResource(R.string.settings_language)
    is Screen.GestureConfig -> screen.gesture.label()
    is Screen.AppGestureOverview -> stringResource(R.string.app_config)
    is Screen.SceneGestureOverview -> screen.scene.label()
}

