package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.InputEvent
import io.github.libxposed.api.XposedInterface
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.config.MemoryStore
import java.lang.reflect.Executable

/**
 * 102 入口真正装钩子的地方 —— 一行 YukiHook / `de.robv.*` 都不碰。
 *
 * 为什么必须自己写：LSPosed 判定"废弃模块"的根据是**加载方式**（APK 里只有
 * `assets/xposed_init` 就判 LEGACY），只要还想让警告消失，就必须走
 * `META-INF/xposed/java_init.list` + `XposedModule`；而 API 102 起框架不许模块再解析
 * legacy 类，所以"把参数交给 YukiHook 的 DSL"这条捷径（101 那次尝试的做法）到 102 就走不通了，
 * hook 这一层只能换成 `XposedInterface.hook(Executable).intercept { chain -> ... }`。
 *
 * 语义与旧路径逐条对齐（[HookTarget]），差别只有两处，都是往安全方向偏：
 *  1. 运行时还没起来（拿不到 Context）时**不吞事件**，让转环照旧走系统原生行为；
 *  2. 返回类型对不上就不吞（[CancelValue.Unsafe]），绝不往系统方法里塞错类型的值。
 */
object CrownHooks {

    private const val TAG = "HyperZoomRing"

    /** 装成功的钩点数 / 明确失败的钩点数（探针和界面都要看，别只写日志） */
    @Volatile var installed = 0; private set
    @Volatile var skipped = 0; private set

    @Volatile private var xposed: XposedInterface? = null

    /** 类名 → 找到的方法指纹，单测和诊断都用它，不依赖真机 */
    @Volatile
    var plan: Map<HookTarget, List<MethodSig>> = emptyMap()
        private set

    /**
     * 入口回调里第一件事就是装钩子：`onSystemServerStarting` 跑在 system_server 的
     * main() 之前，这时候 InputManagerService 还没开始分发事件，装在这里一次不漏。
     */
    fun install(x: XposedInterface, loader: ClassLoader) {
        xposed = x
        val found = LinkedHashMap<HookTarget, List<MethodSig>>()
        for (target in HookTarget.entries) {
            val cls = runCatching { Class.forName(target.owner, false, loader) }.getOrNull()
            if (cls == null) {
                Log.w(TAG, "MODERN ${target.name}: 类不存在（${target.owner}），这条通道没有")
                found[target] = emptyList()
                continue
            }
            val sigs = LinkedHashMap<MethodSig, Executable>()
            runCatching { cls.declaredMethods }.onFailure {
                Log.w(TAG, "MODERN ${target.name}: declaredMethods 失败", it)
            }
            for (m in cls.declaredMethods) {
                val sig = MethodSig.of(target.owner, m)
                if (!target.matches(sig)) continue
                sigs[sig] = m
            }
            if (sigs.isEmpty()) {
                Log.w(TAG, "MODERN ${HookTarget.describe(target, emptyList())}")
            }
            var ok = 0
            for ((sig, m) in sigs) {
                if (hookOne(target, m, sig)) ok++
            }
            found[target] = sigs.keys.toList()
            Log.i(TAG, "MODERN ${HookTarget.describe(target, sigs.keys.toList())} 装好 $ok/${sigs.size}")
        }
        plan = found
        installed = found.values.sumOf { it.size }
    }

    private fun hookOne(target: HookTarget, m: Executable, sig: MethodSig): Boolean {
        val x = xposed ?: return false
        return runCatching {
            x.hook(m).intercept { chain ->
                // interceptKeyBeforeQueueing 旧路径只做"记一笔最近转过环"，绝不改结果
                if (target == HookTarget.INTERCEPT_KEY) {
                    observeKeyEvent(chain)
                    return@intercept chain.proceed()
                }
                val event = chain.getArgs().firstOrNull() as? InputEvent
                val ctx: Context? = InputInterceptorHook.appContext
                // 运行时还没起来（ctx 为 null）就不吞：让转环照旧走系统原生行为，
                // 也绝不在没有配置的情况下瞎判定
                val consume = event != null && ctx != null &&
                    InputInterceptorHook.handleZoomRingEvent(event, ctx, canConsume = true)
                if (!consume) return@intercept chain.proceed()
                when (val cancel = target.cancelFor(sig, truthy = target == HookTarget.LAUNCH_CAMERA)) {
                    is CancelValue.Of -> cancel.value
                    CancelValue.None -> null
                    CancelValue.Unsafe -> chain.proceed() // 吞不起，交给原实现
                }
            }
            true
        }.onFailure { Log.w(TAG, "MODERN hook 失败 ${sig.name}(${sig.paramTypes})", it) }.getOrDefault(false)
    }

    /** 与旧路径的 interceptKeyBeforeQueueing beforeHook 同一件事：认出表冠设备就盖时间戳 */
    private fun observeKeyEvent(chain: XposedInterface.Chain) {
        val event = chain.getArgs().firstOrNull() as? InputEvent ?: return
        val device = runCatching { event.device }.getOrNull() ?: return
        if (!InputInterceptorHook.isZoomRingDevice(device)) return
        InputInterceptorHook.noteRingEvent()
        Log.i(TAG, "MODERN 从 interceptKeyBeforeQueueing 认出表冠 KeyEvent")
    }

    /**
     * system_server 的 Context 不在任何回调参数里，只能自己等。
     *
     * `onSystemServerStarting` 时 `ActivityThread.currentActivityThread()` 还是 null，
     * 而 ConfigSync（收配置推送）、传感器监听、SceneDetector 都要一个能用的 Context。
     * 旧路径靠 YukiHook 在它自己的 loadSystem 时机把 appContext 递过来；102 面没有这个便利，
     * 所以起一条后台线程按节拍轮询 [SystemContext.receiverContext]，拿到就启动运行时。
     * 拿不到就一直试（不占主线程、不打 log 刷屏），运行时只启动一次。
     */
    object Boot {
        private const val INTERVAL_MS = 1_500L
        private const val MAX_TRIES = 240 // 6 分钟：够慢启动的 ROM 喝完咖啡了

        /** 运行时起来之后的节拍：和 ConfigSync 内部自己的 3s/5s 节流配合，够快也不吵 */
        private const val BEAT_MS = 2_000L

        @Volatile private var handler: Handler? = null
        @Volatile private var tries = 0
        @Volatile private var started = false

        /** 节拍用的 context（起来之后 SystemContext 也可能给 null，所以要留一份） */
        @Volatile private var beatCtx: Context? = null

        /** 诊断用：运行时到底起来了没、等了几次 */
        @Volatile var contextWaitMs = -1L; private set

        private var beginAt = 0L

        fun schedule() {
            if (handler != null) return
            val ht = HandlerThread("ZoomRingBoot").apply { start() }
            handler = Handler(ht.looper)
            beginAt = android.os.SystemClock.elapsedRealtime()
            handler?.post(this::tick)
        }

        private fun tick() {
            val ctx = SystemContext.receiverContext(null)
            if (ctx != null) {
                if (!started) {
                    contextWaitMs = android.os.SystemClock.elapsedRealtime() - beginAt
                    started = true
                    beatCtx = ctx
                    Log.i(TAG, "MODERN 运行时启动：拿到 system context（等了 ${contextWaitMs}ms / $tries 次）")
                    runCatching {
                        InputInterceptorHook.startRuntime(
                            ctx, ConfigManager.fromStore(MemoryStore(emptyMap()))
                        )
                    }.onFailure { Log.e(TAG, "MODERN 运行时启动失败（表冠只能等下次开机/推送）", it) }
                }
                // 运行时起来之后这条节拍**不能停**：ConfigSync 的接收器注册重试、开机快照
                // 恢复、状态回写全都挂在 tick 上。之前这个重试只有传感器样本在推
                // （ZoomRingSensorListener.onSample 里调 tick），于是"不转环就永远注册不上"——
                // cfg28 刚软重启完实测 hzr_cfg 里 reg=NullPointerException、pushed=0，
                // 整条配置通道是断的（他报的"改了不生效/生效状态时好时坏"的根因之一）。
                runCatching { ConfigSync.tick(ctx) }
                    .onFailure { Log.w(TAG, "ConfigSync.tick 抛了（注册只能等下次样本）", it) }
                handler?.postDelayed(this::tick, BEAT_MS)
                return
            }
            tries++
            if (tries >= MAX_TRIES) {
                Log.e(TAG, "MODERN 等不到 system context（$tries 次），放弃：注入链路不可用")
                return
            }
            handler?.postDelayed(this::tick, INTERVAL_MS)
        }

        /** 单测用：把状态清回去，别让用户测试之间互相污染 */
        internal fun resetForTest() {
            handler = null; tries = 0; started = false; contextWaitMs = -1L; beginAt = 0L; beatCtx = null
        }
    }

    /** 供探针回显：现在到底是哪条入口、装了几个钩点 */
    fun diagnostics(): String =
        "installed=$installed " + plan.entries.joinToString(" ") { (t, s) ->
            "${t.name}=${s.size}"
        }
}

/** 主线程判定用（system_server 的 main looper 就是它的服务端线程） */
internal fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
