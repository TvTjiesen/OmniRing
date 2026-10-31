package xyz.nextalone.hyperzoomring.hook

import android.content.Context
import android.provider.Settings
import android.util.Log
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.action.actions.MediaScrubAction
import xyz.nextalone.hyperzoomring.action.actions.ScrubDragAction
import xyz.nextalone.hyperzoomring.action.actions.TouchScrollInjector
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.CrownModel
import xyz.nextalone.hyperzoomring.ring.ActionRouter
import xyz.nextalone.hyperzoomring.ring.ForegroundGate
import xyz.nextalone.hyperzoomring.ring.RingSourceArbiter
import xyz.nextalone.hyperzoomring.ring.SeekGate
import xyz.nextalone.hyperzoomring.ring.ScrollModeModel
import xyz.nextalone.hyperzoomring.util.Heartbeat
import android.content.Intent
import xyz.nextalone.hyperzoomring.config.SceneType
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ring.ZoomRingDetector
import xyz.nextalone.hyperzoomring.ring.ZoomRingEvent

/**
 * 传感器驱动的环触发器。
 *
 * 算法本体在 CrownModel（纯计算、无 Android 依赖），可以在 PC 上用真机录制的样本
 * 回放并跑单元测试，因此手感调优不需要反复重启手机 —— 只有模型改动收敛后才上一版机。
 *
 * 为什么不用输入事件：HyperOS 4 把 InputFilterEnabled 强制关成 false，Android 不再调用
 * InputManagerService.filterInputEvent，那是上游模块唯一的硬件捕获点，于是 13 个动作全废。
 *
 * 为什么方向不完全取自古势槽位：四个槽位（顺慢/顺快/逆慢/逆快）只要残留别的映射，快慢就会
 * 落到不同槽位从而改变方向。所以滚动这条语义只认实际转向，累计量交给 ScrollModeModel 记，
 * 不看槽位名。（原来还有个"向上/向下滚"的定方向选项，用户说它和这个冲突，已删。）
 */
object SensorRingDriver {
    private const val TAG = "HyperZoomRing"
    private const val PARAM_TTL_MS = 400L

    /** 判定"快转"的速度阈值（单位/秒） */
    private const val BROADCAST_THROTTLE_MS = 200L
    /** 隔得比这更久的累计转幅直接扔掉，不要一次性倒出去（看起来像列表自己窜了） */
    private const val UNITS_STALE_MS = 1_000L
    private const val STAGE_BROADCAST_MS = 50L
    private const val FAST_UNITS_PER_SEC = 2200f
    /** 离散动作（音量/亮度等）每累计多少位置单位触发一次 */
    private const val DISCRETE_STEP_UNITS = 120f

    @Volatile
    private var lastBroadcastAt = 0L

    /**
     * 节流窗口里攒下但还没发出去的转幅（单位，带符号，正负互相抵消 = 净转动量）。
     * 2026-10-17：以前每次广播只带"最后一个样本的增量"，200ms 窗口里十个样本丢掉九个，
     * App 内因此"转三下才滑一下、不跟手"。改成累加后一次发全，和外面那条（每样本都执行）等价。
     */
    private var pendingUnits = 0f

    private val model = CrownModel()
    private val detector = ZoomRingDetector()

    /** 滚动里程表：表冠语义看带符号量，定方向语义看绝对量 */
    private val scrollMode = ScrollModeModel()

    private var discreteAccum = 0f
    private var lastActionAt = 0L

    @Volatile
    var enabled = true

    /** 调试日志开关（`settings put global hzr_debug 1`）。两条入口都要读，所以不能是 private */
    @Volatile internal var debugLog = false

    @Volatile private var paramAt = 0L

    /**
     * 传感器路上一次"收到有效样本"的时刻（uptimeMillis），0 = 从没来过。
     *
     * 输入侧的互斥租约就看这一个数（见 [RingSourceArbiter]）：它在我们这台 OS4 上会一直更新，
     * 于是 OS3 那种"两条路同时来事"的情况下，输入侧自动降级成只消费。
     */
    @Volatile var lastAcceptedAtMs = 0L

    /** 互斥租约（毫秒），0 = 关掉仲裁。热调：`settings put global hzr_ring_lease_ms 0` */
    @Volatile var leaseMs = RingSourceArbiter.DEFAULT_LEASE_MS

    /** 换应用后的观察窗（毫秒），0 = 关掉这条闸门。热调：`settings put global hzr_fg_stable_ms 0` */
    @Volatile var fgStableMs = ForegroundGate.DEFAULT_STABLE_MS

    /** 进度环：UI 总开关（可被 hzr_media_seek 强制覆盖）+ 竖屏白名单，缓存 0.4 秒 */
    @Volatile private var seekSwitch = false
    @Volatile private var seekPackages = emptySet<String>()

    /** 由 ZoomRingSensorListener 每个传感器样本调用一次 */
    fun onSensorSample(context: Context, config: ConfigManager, values: FloatArray) {
        if (!enabled || values.isEmpty()) return
        refreshParams(context, config)

        val tms = System.currentTimeMillis()
        val accepted = model.sample(tms, values[0])

        if (debugLog) {
            Log.i(
                TAG,
                "SMP pos=${values[0]} tgt=${"%.0f".format(model.targetUnits)} " +
                    "v=${"%.0f".format(model.velUnitsPerSec)} peak=${"%.0f".format(model.peakVel)} " +
                    "dir=${model.dirSign} acc=${if (accepted) "yes" else "no"}"
            )
        }
        if (!accepted) return

        // 盖"传感器这条路在干活"的戳（必须是 uptimeMillis，和读它那边同一个时钟）。
        // HyperOS 3 上输入侧也会收到同一次转动，它靠这个戳把自己降级成"只消费不执行"。
        lastAcceptedAtMs = android.os.SystemClock.uptimeMillis()

        // 里程表要在分发前记：定方向语义需要"累计转幅"，而累计量归谁管不能靠注入端猜
        scrollMode.onAccepted(model.lastDeltaUnits)
        // 节流窗口里没发出去的样本，转幅先累起来 —— 外面那条（ScrollAction）是**每个样本都执行**，
        // 所以不丢；里面这条以前每次广播只带"最后一个样本的增量"，50Hz 采样下 200ms 窗口里
        // 十个样本只到九个被扔掉，App 看到的就是只转了十分之一圈 → 他说的"不跟手"。
        pendingUnits += model.lastDeltaUnits
        deliver(context, config, tms)
    }

    /** 这一把算哪个手势槽位：转向 × 速度。正常分发和沉浸态静默都用它 */
    private fun currentGesture(): GestureType {
        val dir = if (model.dirSign >= 0) 1 else -1
        val fast = kotlin.math.abs(model.velUnitsPerSec) > FAST_UNITS_PER_SEC
        return when {
            dir > 0 && fast -> GestureType.CW_FAST
            dir > 0 -> GestureType.CW_SLOW
            fast -> GestureType.CCW_FAST
            else -> GestureType.CCW_SLOW
        }
    }

    private fun deliver(context: Context, config: ConfigManager, tms: Long) {
        if (!config.isEnabled) return

        // 沉浸态静默：设置页那颗放大居中的表冠正在用环事件转表盘。
        // 这时候绝不能再去执行映射（滚动类会往屏幕正中合成一根真手指，
        // 落点正好压在表盘上，被沉浸态当成点击 → 他说的"一转就退出"）。
        //
        // 同一条闸门现在还管"用户正用手指在我们 App 里滑"：实测我们那根注入手指压在
        // 屏上时，真实那一滑只推动 57px（单独打能推 ~1480px）—— 我们在和他抢
        // pointerId=0 这根手指，列表把他的触摸当成第二根手指忽略了。规则：**手在屏上时用户赢**。
        if (ConfigSync.paused()) {
            sendRingEvent(
                context,
                currentGesture(),
                detector.currentIntensity,
                if (ConfigSync.stagePaused()) "stage" else "touch",
                ok = true,
            )
            return
        }

        val foregroundPkg = InputInterceptorHook.getForegroundPackage(context)
        val isCamera = InputInterceptorHook.isCameraPackage(foregroundPkg)
        // 相机前台且未开启覆盖 -> 让系统自己处理变焦
        if (isCamera && !config.overrideCamera) return

        val gesture = currentGesture()

        detector.onEvent(
            ZoomRingEvent(
                timestampMs = tms,
                value = kotlin.math.abs(model.velUnitsPerSec).toInt() / 40,
                direction = model.dirSign,
            )
        )
        Heartbeat.onGesture(gesture.name)
        val intensity = detector.currentIntensity

        val activeScene = SceneDetector.detectActiveScene(context)
        val actionId = config.resolveActionId(gesture, foregroundPkg, activeScene) ?: return
        val action = ActionRegistry.get(actionId) ?: return

        // 我们自己的 App 在前台时，任何"要往屏幕合成手指"的动作都不许执行。
        //
        // 为什么现在才补：这道闸门原来**只**写在 InputInterceptorHook.handleZoomRingEvent
        // （硬件 MotionEvent 那条路）里。但这台 ROM 上 HyperOS 把 InputFilterEnabled 关了，
        // 真正在干活的是**这条传感器路**，于是它就漏了出去 —— 实测证据：00:14~00:16 那次抓取里
        // 126 条 `MIUIInput: Input motion event injection`，其中一批 DOWN→UP 只隔 17~20ms
        // （没有位移 = 一次点击），而前台正是 xyz.nextalone.hyperzoomring。
        // 他报的两件事因此是同一个根：那根假手指落进我们自己的卡片 → "一转就触发点击"；
        // 它和 App 侧 scrollBy 抢同一根 pointerId=0（实测压着时真滑动只推得动 57px）→ "转三下才滑一下"。
        //
        // 环事件广播照发（dispatched 里就带），应用内改由 MainActivity 自己滚当前页。
        // 相机里一票否决合成手指（**与 override_camera 无关**）。
        // 真机证据：`执行注入 scroll fg=com.android.camera cam=true 切换后 8015ms` —— 包名、前台、
        // 转场窗口全都判对了，它还是执行了，只因旧放行是"相机且未开覆盖"。判据见 ForegroundGate。
        if (!ForegroundGate.allowsInjectedAction(isCamera, action.injectsTouch)) {
            if (debugLog) Log.i(TAG, "相机内否决注手指动作 ${action.id}（override_camera 不再能放行它）")
            dispatched(context, gesture, intensity, actionId, ok = false)
            return
        }

        if (action.injectsTouch && InputInterceptorHook.isOurApp(foregroundPkg)) {
            dispatched(context, gesture, intensity, actionId, ok = true)
            return
        }
        // 「相机界面有概率触发滑动」的正解就在这一段（这条才是本机真正在干活的路）。
        //
        // 转场那段时间 `getRunningTasks` 给的还是上一个应用、甚至直接读空（这台 ROM 的抓包里
        // 见过 `mCurrentFocus=null` 而焦点仍在原应用），于是相机放行闸门判 false，
        // 一根手指就注到刚打开的相机上 —— 他看到的"有概率"就是这么来的。
        // 规则：**刚换应用的一个窗口内，要往屏幕注手指的动作先不执行**（音量/亮度这类不注手指的照旧）。
        // 判据是纯函数 ForegroundGate，PC 上有单测；关它：`adb shell settings put global hzr_fg_stable_ms 0`
        if (action.injectsTouch) {
            val nowUp = android.os.SystemClock.uptimeMillis()
            val sinceSwitch = nowUp - InputInterceptorHook.fgChangedAtMs
            if (ForegroundGate.deferTouchInject(nowUp, InputInterceptorHook.fgChangedAtMs, fgStableMs)) {
                if (debugLog) {
                    Log.i(TAG, "转场窗口内跳过 ${action.id} fg=$foregroundPkg cam=$isCamera 切换后 ${sinceSwitch}ms")
                }
                dispatched(context, gesture, intensity, actionId, ok = false)
                return
            }
            if (debugLog) {
                // 真执行了也留一条：万一还是误触发，一眼分得清是"包名不在白名单"还是"读到空"
                Log.i(TAG, "执行注入 ${action.id} fg=$foregroundPkg cam=$isCamera 切换后 ${sinceSwitch}ms")
            }
        }

        // 进度环：判定规则抽在 SeekGate（纯函数，PC 上有单测覆盖竖屏/横屏两种场景）。
        // 竖屏默认不接管，就是为了避免"竖屏上下滑动和快进冲突"：
        // 后台放歌时 isMusicActive 也为真，按它接管就把滚动抢走了。
        val landscape = SceneDetector.isLandscape(context)
        val asSeek = SeekGate.shouldSeek(
            switchOn = seekSwitch,
            actionId = actionId,
            mediaPlaying = activeScene == SceneType.MEDIA_PLAYING,
            landscape = landscape,
            foregroundPkg = foregroundPkg,
            portraitAllowlist = seekPackages,
        )
        // 走哪条通道由 ActionRouter 决定 —— 它是纯函数，18 个动作的路由在 PC 上逐个断言过
        // （ActionRouterTest）。原来这段是埋在下面的 if 链里的，贴着 Context 没法测，
        // 而"某个动作悄悄走进死路"恰恰是最难查的失效。
        when (ActionRouter.route(actionId, asSeek)) {
            ActionRouter.Branch.DRAG -> {
                if (ScrubDragAction.onDelta(context, model.lastDeltaUnits, landscape)) {
                    dispatched(context, gesture, intensity, actionId, ok = true)
                    return
                }
            }

            ActionRouter.Branch.SEEK -> {
                // 两条通道：① 有 MediaSession 就走绝对目标 seek（准，且能显示"要跳到哪"）；
                // ② 没有会话（小米自带播放器实测就是 0 个会话）就横向拖播放器自己的进度条，
                //    拖动时播放器自己会弹预览，可视化是白送的。
                if (MediaScrubAction.onDelta(context, model.lastDeltaUnits)) {
                    dispatched(context, gesture, intensity, actionId, ok = true)
                    return
                }
                if (ScrubDragAction.onDelta(context, model.lastDeltaUnits, landscape)) {
                    dispatched(context, gesture, intensity, actionId, ok = true)
                    return
                }
                // 两条都控制不了就继续当滚动用，别让他转了半天什么都没发生
                if (scrollToTarget(context, actionId, gesture, intensity)) return
            }

            ActionRouter.Branch.SCROLL -> if (scrollToTarget(context, actionId, gesture, intensity)) return

            ActionRouter.Branch.DISCRETE -> Unit   // 落到下面的离散累计
        }

        if (action is LaunchAppAction) {
            action.targetPackage = config.resolveActionConfig(gesture, foregroundPkg, activeScene)
        }

        // 离散动作：按转动量累计到阈值才触发一次，避免一格一动过于频繁
        discreteAccum += kotlin.math.abs(model.velUnitsPerSec) / 60f
        if (discreteAccum < DISCRETE_STEP_UNITS) return
        discreteAccum = 0f
        if (tms - lastActionAt < 60L) return
        lastActionAt = tms

        try {
            action.execute(context, intensity)
            dispatched(context, gesture, intensity, actionId, ok = true)
        } catch (e: Throwable) {
            dispatched(context, gesture, intensity, actionId, ok = false)
            Log.e(TAG, "sensor-driven action failed: $actionId", e)
        }
    }

    /**
     * 滚动类：目标 = 里程表累计转幅 × 增益，方向跟表冠实际转向（来回转就来回滚）。
     * 旧映射里残留的 scroll_up / scroll_down 也走这里，行为等同"滚动"，不会变成失灵。
     */
    private fun scrollToTarget(
        context: Context,
        actionId: String,
        gesture: GestureType,
        intensity: Float,
    ): Boolean {
        val gain = TouchScrollInjector.pxPerUnit()
        if (!TouchScrollInjector.followTarget(context, scrollMode.targetPx(actionId, gain))) return false
        dispatched(context, gesture, intensity, actionId, ok = true)
        return true
    }

    /**
     * 一次分发的记账：心跳 + 诊断广播都从这里出去。
     *
     * 广播是诊断页的数据来源。上游那条只在输入事件路径上发，而 HyperOS 4 根本不走那条，
     * 所以诊断页永远 0 条 —— 用户说"诊断功能一直没生效"就是这个。
     * 这里 200ms 节流一次：转环时一秒最多发 5 条，不白烧 system_server 的 binder。
     */
    private fun dispatched(
        context: Context,
        gesture: GestureType,
        intensity: Float,
        actionId: String,
        ok: Boolean,
    ) {
        Heartbeat.onDispatch(actionId, ok)
        sendRingEvent(context, gesture, intensity, actionId, ok)
    }

    /**
     * 只发诊断/沉浸态用的环事件广播，不记分发账。
     * 沉浸态静默期间要靠它让表盘跟着转，但那一次并没有真的执行动作，
     * 混进心跳的话诊断页的"成功/失败"次数就虚高了。
     */
    private fun sendRingEvent(
        context: Context,
        gesture: GestureType,
        intensity: Float,
        actionId: String,
        ok: Boolean,
    ) {
        val now = System.currentTimeMillis()
        // 沉浸态里表盘完全靠这条广播转，200ms 一次会明显跟不上手 -> 收紧到 50ms。
        // 平时（第三方 App 里）保持 200ms，不白烧 binder。
        val limit = if (ConfigSync.stagePaused()) STAGE_BROADCAST_MS else BROADCAST_THROTTLE_MS
        if (now - lastBroadcastAt < limit) return
        // 隔太久没发（在相机里、动作没解析出来那段时间），累计的陈年转幅直接扔掉，
        // 不然第一次广播会一口气倒出几百像素，看起来像"手没动列表自己窜了"。
        val gap = now - lastBroadcastAt
        val carry = if (gap > UNITS_STALE_MS) 0f else pendingUnits
        pendingUnits = 0f
        lastBroadcastAt = now
        val units = if (carry == 0f) model.lastDeltaUnits else carry
        runCatching {
            context.sendBroadcast(
                android.content.Intent(InputInterceptorHook.ACTION_ZOOM_RING_EVENT).apply {
                    setPackage("xyz.nextalone.hyperzoomring")
                    putExtra(InputInterceptorHook.EXTRA_TIMESTAMP, now)
                    putExtra(InputInterceptorHook.EXTRA_VALUE, kotlin.math.abs(units).toInt())
                    putExtra(InputInterceptorHook.EXTRA_DIRECTION, model.dirSign)
                    putExtra(InputInterceptorHook.EXTRA_GESTURE, gesture.name)
                    putExtra(InputInterceptorHook.EXTRA_INTENSITY, intensity)
                    putExtra(InputInterceptorHook.EXTRA_ACTION_ID, actionId)
                    putExtra(InputInterceptorHook.EXTRA_INJECT_OK, ok)
                }
            )
        }.onFailure { Log.w(TAG, "diagnostic broadcast failed: ${it.message}") }
    }

    /** 热调：手感参数全走 Settings.Global，改完约 0.4 秒生效，不用重编译也不用重启框架 */
    private fun refreshParams(context: Context, config: ConfigManager) {
        val now = System.currentTimeMillis()
        if (now - paramAt < PARAM_TTL_MS) return
        paramAt = now
        var hudAllowed = true
        try {
            val r = context.contentResolver
            model.deadband = Settings.Global.getFloat(r, "hzr_deadband", model.deadband)
            model.backlash = Settings.Global.getFloat(r, "hzr_backlash", model.backlash)
            model.backlashSpeed =
                Settings.Global.getFloat(r, "hzr_backlash_k", model.backlashSpeed * 1000f) / 1000f
            model.backlashCap = Settings.Global.getFloat(r, "hzr_backlash_cap", model.backlashCap)
            model.maxStepUnits = Settings.Global.getFloat(r, "hzr_max_jump", model.maxStepUnits)
            // 换向至少要几个连续反向样本：1 = 退回"单帧尖峰也能翻方向"的旧行为
            model.reverseMinSamples =
                Settings.Global.getInt(r, "hzr_reverse_samples", model.reverseMinSamples)
            // 换向时是否补记回差里攒下的位移（0=旧写法，会"有时不动、有时轻"）
            model.reverseCredit = Settings.Global.getInt(r, "hzr_backlash_credit", 1) == 1
            model.unlockVel = Settings.Global.getFloat(r, "hzr_unlock_vel", model.unlockVel)
            debugLog = Settings.Global.getInt(r, "hzr_debug", 0) == 1
            // 双入口互斥租约、换应用观察窗：都是给真机远程 A/B 用的（见 RingSourceArbiter / ForegroundGate）
            leaseMs = Settings.Global.getLong(r, "hzr_ring_lease_ms", leaseMs)
            fgStableMs = Settings.Global.getLong(r, "hzr_fg_stable_ms", fgStableMs)
            // hzr_media_seek: 未设置(-1)=听设置界面的开关，0=强制关，1=强制开
            val forced = Settings.Global.getInt(r, "hzr_media_seek", -1)
            seekSwitch = when (forced) {
                0 -> false
                1 -> true
                else -> runCatching { config.mediaSeekEnabled }.getOrDefault(false)
            }
            hudAllowed = Settings.Global.getInt(r, "hzr_hud", 1) == 1
        } catch (e: Throwable) {
            Log.d(TAG, "params refresh failed", e)
        }
        seekPackages = runCatching { config.seekPortraitPackages }.getOrDefault(emptySet())
        MediaScrubAction.applyHud(context, hudAllowed && runCatching { config.seekHudEnabled }.getOrDefault(true))
        // 灵敏度也来自设置界面，热参优先
        val uiMs = runCatching { config.seekMsPerUnit }.getOrDefault(12)
        MediaScrubAction.applyMsPerUnit(uiMs.toFloat())
    }
}
