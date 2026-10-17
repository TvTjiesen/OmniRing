package xyz.nextalone.hyperzoomring.hook

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.IntType
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.ZoomRingConstants
import xyz.nextalone.hyperzoomring.ring.ZoomRingDetector
import xyz.nextalone.hyperzoomring.ring.ZoomRingEvent

@Suppress("DEPRECATION")
object InputInterceptorHook {
    private const val TAG = "HyperZoomRing"
    const val ACTION_ZOOM_RING_EVENT = "xyz.nextalone.hyperzoomring.ZOOM_RING_EVENT"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_VALUE = "value"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_GESTURE = "gesture"
    const val EXTRA_INTENSITY = "intensity"

    /** 传感器链路才有：这一次分发到了哪个动作、注入成没成功 */
    const val EXTRA_ACTION_ID = "action_id"
    const val EXTRA_INJECT_OK = "inject_ok"

    private const val FG_TTL_MS = 200L
    private val detector = ZoomRingDetector()
    private var sensorListener: ZoomRingSensorListener? = null

    /**
     * 钩子侧当前生效的配置。
     *
     * 必须可换：App 改完设置是通过广播推过来的（见 ConfigSync），换掉这个引用就全线生效。
     * 之前它是启动时一次性建好的快照，所以"改了全局手势没反应"。
     */
    @Volatile
    private lateinit var config: ConfigManager

    /** system_server 的 Context，配置请求要用它发广播 */
    var appContext: Context? = null
        private set

    /** hook 侧真正在用的配置对象。诊断探针靠它回答"我读到的是哪份配置" */
    @Volatile
    var currentConfig: ConfigManager? = null
        private set

    /** 换配置的入口由 InputInterceptorHook 提供 —— 只给它 setter */
    fun replaceConfig(next: ConfigManager) {
        config = next
        currentConfig = next
    }

    /**
     * 传感器链路 + 配置通道 + 场景检测：两条入口（legacy / 102）共用这一段。
     *
     * 从 [hook] 里抽出来不是因为想重构，而是 102 面**没有** YukiHook 那种"框架把
     * appContext 递给你"的时机：新入口只能自己等到能用的 Context 再启动这里
     * （见 [CrownHooks.Boot]）。抽出来能保证两条入口跑的是同一份初始化，
     * 不会出现"legacy 能收配置、modern 收不到"这种没法对比的状态。
     */
    fun startRuntime(ctx: Context?, initial: ConfigManager? = null) {
        if (ctx == null || runtimeStarted) return
        synchronized(this) {
            if (runtimeStarted) return
            runtimeStarted = true
        }
        initial?.let { replaceConfig(it) }
        appContext = ctx

        try {
            ConfigSync.bind(set = { replaceConfig(it) })
            ConfigSync.start(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "ConfigSync start failed（配置只能等下次开机生效）", e)
        }

        try {
            sensorListener = ZoomRingSensorListener(ctx).also { l ->
                l.onSample = { values ->
                    SensorRingDriver.onSensorSample(ctx, this@InputInterceptorHook.config, values)
                }
                l.start()
            }
            Log.i(TAG, "Optical tracking sensor listener started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sensor listener", e)
        }

        try {
            SceneDetector.init(ctx)
            Log.i(TAG, "SceneDetector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "SceneDetector init failed", e)
        }
    }

    @Volatile private var runtimeStarted = false

    private const val DIRECTION_STALE_MS = 500L

    @Volatile
    private var lastZoomRingEventMs: Long = 0
    private const val CAMERA_BLOCK_WINDOW_MS = 2000L

    /** Track which hook fired to avoid double-processing. */
    @Volatile
    private var lastProcessedEventTime: Long = 0

    fun hook(param: PackageParam, config: ConfigManager) = with(param) {
        this@InputInterceptorHook.config = config
        currentConfig = config
        Log.i(TAG, "Hooking InputManagerService + optical tracking sensor")

        // Start sensor listener（legacy 入口：Context 由 YukiHook 在 loadSystem 时递过来）
        startRuntime(appContext, null)

        // Block camera launch from zoom ring
        try {
            "com.miui.server.input.util.ShortCutActionsUtils".toClass().hook {
                injectMember {
                    allMethods("launchCamera")
                    beforeHook {
                        // 读**当前**配置，不是 hook() 调用时那个参数：App 推新配置进来要立刻生效
                        if (!this@InputInterceptorHook.config.isEnabled) return@beforeHook
                        val age = System.currentTimeMillis() - lastZoomRingEventMs
                        if (age < CAMERA_BLOCK_WINDOW_MS) {
                            Log.i(TAG, "Blocked zoom ring camera launch (${age}ms after ring event)")
                            resultTrue()
                        }
                    }
                }.result {
                    onHookingFailure { Log.w(TAG, "Failed to hook launchCamera", it) }
                }
            }
            Log.i(TAG, "Hooked ShortCutActionsUtils.launchCamera")
        } catch (e: Exception) {
            Log.w(TAG, "ShortCutActionsUtils not found", e)
        }

        val imsClass = "com.android.server.input.InputManagerService".toClass()

        // Hook 1: filterInputEvent (works when InputFilter is registered)
        try {
            imsClass.hook {
                injectMember {
                    method {
                        name = "filterInputEvent"
                        param(InputEvent::class.java, IntType)
                    }
                    beforeHook {
                        val event = args(0).cast<InputEvent>() ?: return@beforeHook
                        val ctx = this@InputInterceptorHook.appContext ?: return@beforeHook
                        if (handleZoomRingEvent(event, ctx, canConsume = true)) {
                            resultFalse()
                        }
                    }
                }.result {
                    onHookingFailure { Log.w(TAG, "filterInputEvent hook failed", it) }
                }
            }
            Log.i(TAG, "Hooked filterInputEvent")
        } catch (e: Exception) {
            Log.w(TAG, "filterInputEvent not available", e)
        }

        // Hook 2: injectInputEvent (fallback, always called)
        try {
            imsClass.hook {
                injectMember {
                    allMethods("injectInputEvent")
                    beforeHook {
                        val event = args.firstOrNull() as? InputEvent ?: return@beforeHook
                        val ctx = this@InputInterceptorHook.appContext ?: return@beforeHook
                        if (handleZoomRingEvent(event, ctx, canConsume = true)) {
                            resultFalse()
                        }
                    }
                }.result {
                    onHookingFailure { Log.w(TAG, "injectInputEvent hook failed", it) }
                }
            }
            Log.i(TAG, "Hooked injectInputEvent")
        } catch (e: Exception) {
            Log.w(TAG, "injectInputEvent not available", e)
        }

        // Hook 3: dispatchUnhandledKey won't help for MotionEvent, but let's hook
        // interceptKeyBeforeQueueing to catch any KeyEvent-based zoom ring events
        try {
            imsClass.hook {
                injectMember {
                    allMethods("interceptKeyBeforeQueueing")
                    beforeHook {
                        val event = args.firstOrNull() as? InputEvent ?: return@beforeHook
                        val device = event.device ?: return@beforeHook
                        if (isZoomRingDevice(device)) {
                            Log.i(TAG, "ZoomRing KeyEvent intercepted via interceptKeyBeforeQueueing")
                            lastZoomRingEventMs = System.currentTimeMillis()
                        }
                    }
                }.result {
                    onHookingFailure { /* expected if signature differs */ }
                }
            }
        } catch (_: Exception) {}

        // Fullscreen detection removed — hooking layout-pass methods
        // (beginPostLayoutPolicyLw / applyPostLayoutPolicyLw) causes system_server
        // to hang due to per-frame reflection overhead. Fullscreen scene is disabled
        // until a low-cost detection method is found.
    }

    /**
     * Core event handler. Returns true if event was consumed (caller should block it).
     *
     * internal：102 入口的 [CrownHooks] 也要调它，两条入口共用同一份判定，
     * 免得"legacy 好用、modern 少一条规则"这种分裂。
     */
    internal fun handleZoomRingEvent(event: InputEvent, context: Context, canConsume: Boolean): Boolean {
        if (event !is MotionEvent) return false
        val device = event.device ?: return false
        if (!isZoomRingDevice(device)) return false

        val eventTime = event.eventTime
        if (eventTime == lastProcessedEventTime) return canConsume
        lastProcessedEventTime = eventTime
        lastZoomRingEventMs = System.currentTimeMillis()

        val foregroundPkg = getForegroundPackage(context)

        // Camera override: overrideCamera=false means camera always gets passthrough
        if (isCameraPackage(foregroundPkg) && !config.overrideCamera) return false

        val direction = getDirection()
        val scrollValue = event.getAxisValue(MotionEvent.AXIS_SCROLL).toInt()
        val zoomEvent = ZoomRingEvent(
            timestampMs = eventTime,
            value = scrollValue,
            direction = direction,
        )

        val gesture = detector.onEvent(zoomEvent)
        val intensity = detector.currentIntensity

        // Broadcast diagnostic event
        try {
            val ctx = context
            val intent = Intent(ACTION_ZOOM_RING_EVENT).apply {
                setPackage("xyz.nextalone.hyperzoomring")
                putExtra(EXTRA_TIMESTAMP, zoomEvent.timestampMs)
                putExtra(EXTRA_VALUE, scrollValue)
                putExtra(EXTRA_DIRECTION, direction)
                putExtra(EXTRA_GESTURE, gesture.name)
                putExtra(EXTRA_INTENSITY, intensity)
            }
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send diagnostic broadcast", e)
        }

        if (!config.isEnabled) return canConsume

        // Scene detection
        val activeScene = SceneDetector.detectActiveScene(context)

        // Unified resolution
        val actionId = config.resolveActionId(gesture, foregroundPkg, activeScene) ?: return canConsume
        val action = ActionRegistry.get(actionId) ?: return canConsume

        if (action is LaunchAppAction) {
            LaunchAppAction.targetPackage = config.resolveActionConfig(gesture, foregroundPkg, activeScene)
        }

        val ctx = context
        // 我们自己的 App 在前台时不要往自己屏幕上注手指：那根假手指会落进我们自己的
        // 卡片/列表里，位移不够就被判成点击 —— 他报的"应用内一转环就误触打开东西"。
        // 环事件广播在上面已经发过了，应用内改由 MainActivity 直接滚当前页。
        if (action.injectsTouch && isOurApp(foregroundPkg)) {
            if (debugSkipLog()) {
                Log.i(TAG, "应用内跳过注入动作 ${action.id}（改由 App 自己滚）")
            }
            return canConsume
        }
        try {
            action.execute(ctx, intensity)
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed: ${action.id}", e)
        }

        return canConsume
    }

    private fun getDirection(): Int {
        val listener = sensorListener ?: return 0
        val age = System.currentTimeMillis() - listener.lastSensorTimestampMs
        return if (age < DIRECTION_STALE_MS) listener.lastDirection else 0
    }

    /**
     * 前台包名。
     *
     * 转环时传感器样本是几十毫秒一个，而每个样本都要判场景 → 原来每次都打一次
     * getRunningTasks，实测约 80ms，比样本周期还长，会把链路整个拖成"追帧"。
     * 这里加 200ms 的结果缓存：前台应用不可能在这点时间里换人，
     * 但一次旋转里的几十次查询就都只读一个 volatile 字段了。
     */
    @Volatile
    private var fgPkg: String? = null

    @Volatile
    private var fgAt = 0L

    internal fun getForegroundPackage(context: Context?): String? {
        val ctx = context ?: return null
        val now = android.os.SystemClock.uptimeMillis()
        if (now - fgAt < FG_TTL_MS) return fgPkg
        // 先占时间戳再查：并发样本不会一起挤去查同一份
        fgAt = now
        val pkg = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
        } catch (t: Throwable) {
            Log.w(TAG, "getForegroundPackage failed: ${t.message}")
            null
        }
        fgPkg = pkg
        return pkg
    }

    private var skipLogAt = 0L
    private fun debugSkipLog(): Boolean {
        val now = System.currentTimeMillis()
        if (now - skipLogAt < 2000L) return false
        skipLogAt = now
        return true
    }

    internal fun isOurApp(packageName: String?): Boolean =
        packageName == xyz.nextalone.hyperzoomring.util.ModulePath.PKG

    internal fun isCameraPackage(packageName: String?): Boolean =
        packageName == "com.android.camera" || packageName == "com.xiaomi.camera"

    internal fun isZoomRingDevice(device: InputDevice): Boolean {
        return device.name == ZoomRingConstants.DEVICE_NAME ||
            (device.vendorId == ZoomRingConstants.VENDOR_ID && device.productId == ZoomRingConstants.PRODUCT_ID)
    }

    /** 102 入口从 interceptKeyBeforeQueueing 认出表冠时盖的戳，语义与旧路径那条 beforeHook 一致 */
    internal fun noteRingEvent() {
        lastZoomRingEventMs = System.currentTimeMillis()
    }
}
