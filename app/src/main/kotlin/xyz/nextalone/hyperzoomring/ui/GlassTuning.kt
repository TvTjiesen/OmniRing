package xyz.nextalone.hyperzoomring.ui

import android.content.Context
import android.provider.Settings

/**
 * 玻璃参数的热调入口（真机排障用，不用重编）。
 *
 * 为什么要它：判断"实时模糊到底有没有生效"，唯一的办法是同一个位置改一个量再截图对比。
 * 每改一次就重装一次太慢，而且装机这条路本身会引入别的变量（LSPosed 缓存 dex 等）。
 * 应用侧读 Settings.Global 不需要任何权限，写由 adb 完成。
 *
 * adb shell settings put global hzr_glass_bar_blur 60     # 底栏/滑块的模糊 dp
 * adb shell settings put global hzr_glass_veil_blur 60    # 顶栏纱的模糊 dp
 * adb shell settings put global hzr_glass_lens 40         # 底栏透镜折射 dp
 * adb shell settings put global hzr_glass_bar_blur 4      # 回默认
 */
object GlassTuning {
    const val BAR_DEFAULT_DP = 4f
    const val VEIL_DEFAULT_DP = 30f
    const val LENS_DEFAULT_DP = 24f

    @Volatile
    var barBlurDp = BAR_DEFAULT_DP

    @Volatile
    var veilBlurDp = VEIL_DEFAULT_DP

    @Volatile
    var lensDp = LENS_DEFAULT_DP

    /** 1 = 把采样层也挂到根容器上（让顶栏/底栏成为它的子孙，能采到页面内容） */
    @Volatile
    var rootLayer = 0f

    /**
     * 1 = 干脆不挂采样层（底栏退化成纯色）。
     *
     * 这是测量用的开关，不是给用户看的设置：cfg18 把"每页一份 backdrop"上机之后，
     * 一次受控上滑的内容位移从 255px 掉到 50px，同时底栏带随内容变化的比例从 30.5%
     * 掉到 0%。两个数一起变说明问题不在"参数"而在"挂了这个节点本身"。
     * 关掉它再量一次，才能把"recorder 影响滚动"和"别的东西影响滚动"分开。
     */
    @Volatile
    var recorderOff = 0f

    /**
     * 1 = 允许 lens 用 depthEffect + chromaticAberration。
     *
     * 默认 0（关）。理由是真机连拍：快速点底栏时"跳出来的那个黑东西"就是选中胶囊那层
     * `lens(depthEffect = true, chromaticAberration = 0.5f)` —— 它没有 blur 垫着，
     * 采样层在被重录的那一帧是空的，AGSL 直接给出实心黑；而带 blur 的那几处只是变淡，
     * 不出黑。留这个键是为了能在不重编的前提下 A/B 对比，别当设置项给用户看。
     */
    @Volatile
    var depthLens = 0f

    /**
     * 滑块（选中胶囊）自己那层模糊，单位 dp。**默认 0 = 不糊**。
     *
     * 他要的是"玻璃滑块留着，但选中那个别糊"（2026-10-16 深夜）。cfg27 那版胶囊固定垫了
     * 6dp blur，静止时 `pressProgress = 0` → 折射量也是 0，于是胶囊里那格 tab 内容**只剩糊**，
     * 这就是他看到的"选中的那个直接模糊"。把 blur 归零后只剩按下去才起的折射，字就是清晰的。
     *
     * A/B（不用重装，2 秒生效）：
     *   adb shell settings put global hzr_glass_pill_blur 6   # 回到 cfg27 的糊法
     *   adb shell settings put global hzr_glass_pill_blur 0   # 去掉模糊（默认）
     * 黑块风险由 `backdropIsLive(tabsBackdrop)` 闸门兜住：镜像采样层空掉的那一帧不画玻璃。
     */
    @Volatile
    var pillBlurDp = 0f

    /**
     * 1 = 允许右上角那颗钮真的去采页面内容（liveBlur）。
     *
     * 默认 0（关）。这颗钮是 44dp 的小圆面，cfg26 实测一开就变实心黑圆盘，
     * 当时还没修"共享采样层被离场页清空"那件事。现在每页一份层 + backdropIsLive
     * 闸门都在了，留这个键在真机上做 A/B：能糊住就把它默认打开，糊不住就保留浅色实心，
     * 别再靠我猜。`adb shell settings put global hzr_glass_btn_live 1`
     */
    @Volatile
    var btnLive = 0f

    /** adb `settings put global hzr_debug 1`：界面侧自检要不要往 logcat 写 */
    @Volatile
    var debugLog = false

    /** 这两条计数是用来**证明观察器这条路在这台 ROM 上真的会响**的，不是装饰 */
    @Volatile
    var observerHits = 0L; private set

    @Volatile
    var pollHits = 0L; private set

    /** 有没有被热调过（用来决定要不要在界面上提示"参数已改"） */
    val isTuned: Boolean
        get() = barBlurDp != BAR_DEFAULT_DP || veilBlurDp != VEIL_DEFAULT_DP || lensDp != LENS_DEFAULT_DP

    /**
     * @return 这一次有没有真的改变。
     *
     * 必须返回它：调用方只在"变了"的时候才推一次重组。早先无条件每 400ms 自增一个
     * 状态位，等于整棵页面树每秒重画 2.5 次 —— 手指按下去正好赶上输入链被重建就会
     * 丢触控（他 2026-10-14 报的"app 内频繁丢触控，要点底栏才好"），而且玻璃的采样层
     * 会被反复重挂，实时折射也跟着失效。
     */
    fun refresh(context: Context): Boolean {
        var changed = false
        try {
            val r = context.contentResolver
            val bar = Settings.Global.getFloat(r, "hzr_glass_bar_blur", barBlurDp)
            val veil = Settings.Global.getFloat(r, "hzr_glass_veil_blur", veilBlurDp)
            val lens = Settings.Global.getFloat(r, "hzr_glass_lens", lensDp)
            val root = Settings.Global.getFloat(r, "hzr_glass_root", rootLayer)
            val off = Settings.Global.getFloat(r, "hzr_glass_off", recorderOff)
            val depth = Settings.Global.getFloat(r, "hzr_glass_depth", depthLens)
            val pill = Settings.Global.getFloat(r, "hzr_glass_pill_blur", pillBlurDp)
            val btn = Settings.Global.getFloat(r, "hzr_glass_btn_live", btnLive)
            val dbg = Settings.Global.getInt(r, "hzr_debug", 0) == 1
            changed = bar != barBlurDp || veil != veilBlurDp || lens != lensDp ||
                root != rootLayer || off != recorderOff || depth != depthLens ||
                pill != pillBlurDp ||
                btn != btnLive || dbg != debugLog
            barBlurDp = bar
            veilBlurDp = veil
            lensDp = lens
            rootLayer = root
            recorderOff = off
            depthLens = depth
            pillBlurDp = pill
            btnLive = btn
            debugLog = dbg
        } catch (e: Throwable) {
            // 读不到就用当前值，热调只是排障工具，不该影响界面
        }
        return changed
    }

    /**
     * 用 ContentObserver 取代"每 800ms 去 provider 里捞 7 个键"。
     *
     * 为什么要换（2026-10-16 收尾轮量出来的）：轮询版是 7 次 binder 往返 / 0.8 秒 ≈
     * **每分钟 525 次**，而我们只是"他偶尔用 adb 改一个调试参数"。事件驱动之后
     * 静置时一条 binder 都不发，改参数的响应还更快（provider 直接通知）。
     *
     * onChange 是在 binder 线程上回调的：读 provider 放这儿没问题（本来就不是主线程），
     * 但"推重组"这个动作必须回主线程，所以回调里显式 post 到 main looper。
     *
     * @return 用完必须 close()（调用方放 DisposableEffect 的 onDispose）
     */
    fun startObserving(context: Context, onChanged: () -> Unit): AutoCloseable {
        val resolver = context.contentResolver
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                if (refresh(context)) {
                    observerHits++
                    main.post(onChanged)
                }
            }
        }
        runCatching {
            resolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
        }.onFailure {
            android.util.Log.w("GlassTuning", "注册 Settings 观察器失败，只能退回轮询", it)
        }
        return AutoCloseable { runCatching { resolver.unregisterContentObserver(observer) } }
    }

    /** 兜底轮询用：调用方每跳一次计数，方便在 logcat 里看清到底是谁在驱动 */
    fun notePoll() {
        pollHits++
    }
}
