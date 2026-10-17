package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.hardware.display.DisplayManager
import xyz.nextalone.hyperzoomring.action.Action
import xyz.nextalone.hyperzoomring.ring.ScrollTracker
import kotlin.math.abs

/**
 * 滚动动作：方向跟随表冠实际转向、距离跟随幅度。
 *
 * 方向口径：目标像素为正 = 内容上移 = 向下滚（顺时针=往下滚，和真相机环的直觉一致）。
 * 这里以前还并列着 scroll_up / scroll_down 两个"定方向"动作，用户反馈它们和"滚动"本身
 * 冲突、清单也太冗杂，按他的原话"修不好就删掉吧"删掉了。旧映射里残留的这两个 id 由
 * ActionRouter 的 startsWith("scroll") 兜到同一条连续通道，行为就是"滚动"，不会失灵。
 */
object ScrollCrownAction : Action {
    override val id = "scroll"
    override val injectsTouch = true
    override val displayName = "滚动（方向随转向、距离随幅度）"
    override fun execute(context: Context, intensity: Float) {
        TouchScrollInjector.nudge(context, TouchScrollInjector.discreteStepPx(intensity))
    }
}

/**
 * 表冠滚动的触摸注入。
 *
 * 为什么不用滚动轴：实测 HyperOS 3 的列表不响应 AXIS_SCROLL / AXIS_VSCROLL
 * （各注入 12 次，截图像素零变化，而同环境手指式 swipe 有效），只认真实触摸序列。
 *
 * 运动模型：数值部分在 [ScrollTracker]（纯类，能在 PC 上用真机样本回放、有单元测试）。
 * 这里只负责把它给出的 Down/Move/Up 指令变成 MotionEvent，并管住线程与屏幕几何。
 *
 * 历史教训（别再走回头路）：
 *   - "甩一把手指然后让控件自己飞"是开环的：飞多远我们不知道，记账必然失真，
 *     来回转动时的"乱跳"就来自这个失真（旧版还得在甩动结束时硬把已滚量抹成目标）；
 *   - 允许已滚动量越过目标 -> 误差符号翻转 -> 控制器反向追 -> 来回振荡；
 *   - 给滚动加"减速时间常数/滑行尾巴"会直接牺牲跟手，而跟手才是表冠的核心；
 *   - 落指位移小于 touch slop 会被判成点击（真机复现过：手没碰屏幕却打开了应用）；
 *   - 增益过高会让手指频繁撞边界，重抓本身就是顿挫源。
 *
 * 热调（约 0.4 秒生效，不用重编译/重启）：
 *   hzr_scroll_gain      每单位位置=多少像素（默认 1.20，想滚得远就调大）
 *   hzr_scroll_ease      每帧花掉误差的比例（默认 0.55，越小收尾越长）
 *   hzr_scroll_max_step  单帧最大像素（默认 170，决定最高速度）
 *   hzr_scroll_hold      追平后手指原地待命的帧数（默认 10，16ms/帧；
 *                       调成 1 就退回旧版"追平即抬手"，抽搐会立刻回来）
 *   hzr_scroll_dwell     每次抬手前的零速帧数（默认 8；短于控件 VelocityTracker
 *                       的 ~100ms 窗口就压不住 fling，只用在"真停手"那一路）
 *   hzr_scroll_edge      撞边换指方式（默认 1=就地抬手下一帧重抓；
 *                       0=换之前也压 dwell 帧零速。真机回放：1 的"卡手帧"=84，0=274，
 *                       旧版"追平即抬手"=178 —— 觉得卡手就把这个开关拨到 0 对照）
 *   hzr_drag_top / hzr_drag_bottom  手指落指带（默认 0.30 / 0.72 屏高；
 *                       某块控件吃掉竖向手势时，把这条带挪到能滚的那一段）
 */
internal object TouchScrollInjector {
    private const val TAG = "HyperZoomRing"

    private const val DEF_GAIN = 1.20f
    private const val DEF_EASE = 0.55f
    private const val DEF_MAX_STEP = 170f

    private const val FRAME_MS = 16L
    /**
     * 手指拖拽带。刻意收在屏幕中段：
     *  - 太靠上会落在 B站/短视频页的播放器画面上，那一块的手势被播放器自己吃掉（亮度/音量），
     *    真机实测就是"往上滚没反应、往下滚正常"这种单向失效；
     *  - 太靠下会撞状态栏/导航手势区。
     * 带子变短只会多换几次手指，而换指是看不见的（内容不会跳），比落在错的控件上划算。
     */
    private const val DRAG_TOP = 0.30f
    private const val DRAG_BOTTOM = 0.72f
    private const val PARAM_TTL_MS = 400L
    private const val GEOMETRY_TTL_MS = 500L
    /** 短于此横向距离的拖拽没意义（而且容易被当点击） */
    private const val MIN_SCRUB_PX = 60f

    /**
     * 注入闸门：只数连续失败，冷却后自动恢复。
     * 旧版是终身累计的 failures>=8 就永久停用，而他手机上表冠"用着用着就死"
     * 就是这么来的（system_server 活几天，攒够 8 次只是时间问题）。
     */
    private val budget = xyz.nextalone.hyperzoomring.action.InjectionBudget()

    private val worker: Handler by lazy {
        HandlerThread("HyperZoomRing-Injector").apply { start() }.looper.let { Handler(it) }
    }

    private val tracker = ScrollTracker(ease = DEF_EASE, maxStepPx = DEF_MAX_STEP)

    // 仅 worker 线程访问
    private var running = false
    private var fingerDown = false

    /** 抬手期间禁止再触发一次"连续失败上限"的递归收尾 */
    private var releasing = false
    private var downTime = 0L
    private var curX = 0f
    private var lastW = 0
    private var lastH = 0
    private var geometryAt = 0L
    private var ctx: Context? = null

    /** 进度拖拽会话：落指一次、按住推进、停手超时才抬手 */
    private var scrubActive = false
    private var scrubDown = false
    private var scrubX0 = 0f
    private var scrubY = 0f
    private var scrubMinX = 0f
    private var scrubMaxX = 0f
    private var scrubTargetX = 0f
    private var scrubLastX = 0f
    private var scrubMovedTotal = 0f
    private var scrubLastMoveAt = 0L
    private var scrubHoldMs = 700L

    /** 接力时下一把的起手比例（只生效一次），null = 画面中心 */
    @Volatile private var nextStartXFrac: Float? = null

    // 热调参数
    @Volatile private var paramAt = 0L
    @Volatile private var gain = DEF_GAIN
    @Volatile private var dragTop = DRAG_TOP
    @Volatile private var dragBottom = DRAG_BOTTOM
    @Volatile private var debugLog = false

    private val frame = object : Runnable {
        override fun run() {
            val keep = try {
                step()
            } catch (e: Throwable) {
                Log.w(TAG, "frame failed", e)
                // 循环要停了，但手指可能还压在屏幕上：这一帧不抬手，之后所有真实触摸
                // 都会被系统当成第二根手指（双指手势），界面看起来就是"触摸死了"。
                trippedOnFailure(SystemClock.uptimeMillis())
                false
            }
            // 收尾不许停：只要记账里还有手指在屏上（滚动的那根、拖进度那根），就必须继续跑，
            // 直到 UP 真的注进去为止。以前这里只看 step() 的返回值 —— 那一帧如果正好
            // "模型判完 + 抬手注入失败"，循环就带着 press 着的手指退场了：
            // 用户表现为"点了底栏切到设置，整页滑不动、变焦环也不干活，反复点底栏才恢复"
            // （我们注入的是 pointerId=0，和他真实手指的 id 撞车，系统要等下一次真实 DOWN
            //  才把僵住的那次手势收尾，所以"再点一下"看起来像修好了）。
            val keepForRelease = LoopPolicy.needsReleaseRetry(
                sessionWantsToStop = !keep,
                fingerDown = fingerDown,
                scrubDown = scrubDown,
                scrubActive = scrubActive,
                tries = releaseTries,
                maxTries = RELEASE_MAX_TRIES,
            )
            if (keep || keepForRelease) {
                if (!keep) releaseTries++
                worker.postDelayed(this, if (keep) FRAME_MS else RELEASE_RETRY_MS)
                running = true
            } else {
                running = false
                if (releaseTries > 0) {
                    if (fingerDown || scrubDown) {
                        Log.e(TAG, "抬手重试 $releaseTries 次仍失败：屏幕上可能还压着我们那根手指 " +
                            "down=$fingerDown scrub=$scrubDown")
                        stuckSinceMs = SystemClock.uptimeMillis()
                    }
                    releaseTries = 0
                }
            }
        }
    }

    /** 抬手收尾最多再试多久：60 次 × 50ms = 3 秒，之后停手并留下证据，不许空转 */
    private const val RELEASE_MAX_TRIES = 60
    private const val RELEASE_RETRY_MS = 50L

    @Volatile private var releaseTries = 0

    /** 最后一次确认"手指还压在屏幕上但已经不再重试"的时刻，诊断要看（0 = 没有卡过） */
    @Volatile var stuckSinceMs = 0L; private set


    /** 记一次失败；打到冷却就尽力把手指抬起来 */
    private fun trippedOnFailure(now: Long) {
        if (budget.onFailure(now)) {
            Log.w(TAG, "注入连续失败，冷却 ${"%.1f".format(5f)} 秒，先强制抬手")
            worker.post { releaseFinger(now) }
        }
    }

    /**
     * 无条件收尾：把我们注入的那根手指（滚动用的、拖进度用的）从屏幕上抬起来。
     * 抬手是尽力而为，失败也不再重试 —— 闸门已经在冷却了。
     */
    private fun releaseFinger(now: Long) {
        if (fingerDown) {
            val y = tracker.fingerY
            if (send(now, now, MotionEvent.ACTION_UP, curX, y)) fingerDown = false
            if (debugLog) Log.i(TAG, "FORCE 抬手 scroll y=${"%.0f".format(y)}")
        }
        if (scrubDown) {
            if (send(now, now, MotionEvent.ACTION_UP, scrubLastX, scrubY)) scrubDown = false
            scrubActive = false
            if (debugLog) Log.i(TAG, "FORCE 抬手 scrub x=${"%.0f".format(scrubLastX)}")
        }
        tracker.abort()
    }

    fun pxPerUnit(): Float {
        refreshParams()
        return gain
    }

    fun discreteStepPx(intensity: Float): Float = 70f * (1f + intensity)

    /** 给出绝对目标（像素），本类负责把画面追上去 */
    fun followTarget(context: Context, newTargetPx: Float): Boolean {
        if (!budget.allow(SystemClock.uptimeMillis())) {
            Log.d(TAG, "followTarget 冷却中，暂不注入（连续失败 ${budget.consecutiveFailures}）")
            return false
        }
        ctx = context
        refreshParams()
        val t = newTargetPx
        worker.post {
            try {
                tracker.targetPx = t
                updateGeometry(context)
                ensureLoop()
            } catch (e: Throwable) {
                Log.w(TAG, "followTarget failed", e)
            }
        }
        return true
    }

    /** 离散触发：把目标往前推一段 */
    fun feed(context: Context, px: Float): Boolean {
        if (!budget.allow(SystemClock.uptimeMillis())) {
            Log.d(TAG, "feed 冷却中，暂不注入")
            return false
        }
        ctx = context
        refreshParams()
        worker.post {
            try {
                tracker.targetPx += px
                updateGeometry(context)
                ensureLoop()
            } catch (e: Throwable) {
                Log.w(TAG, "feed failed", e)
            }
        }
        return true
    }

    fun nudge(context: Context, px: Float) {
        feed(context, px)
    }

    /**
     * 探针用（settings put global hzr_probe 2048）：一行说清注入器现在到底活不活。
     * "有概率丢失触控"这类问题在界面上看不出来，必须先分清是闸门在冷却、
     * 还是手指卡在屏幕上没抬、还是注入通道本身返回了假。
     */
    fun diagnostics(): String =
        "run=$running down=$fingerDown trackerDown=${tracker.fingerDown} " +
            "releaseTries=$releaseTries stuckSince=$stuckSinceMs " +
            "rel=${tracker.releases} hold=${tracker.holdFrames}/${tracker.dwellFrames} " +
            "edge=${tracker.edgeInstant} " +
            "scrub=$scrubActive/$scrubDown ok=${budget.totalSuccesses} fail=${budget.totalFailures} " +
            "consec=${budget.consecutiveFailures} cool=${budget.coolingAt(SystemClock.uptimeMillis())} " +
            "bridge=${InjectBridge.diagnostics()}"

    private fun ensureLoop() {
        if (!running) {
            running = true
            worker.post(frame)
        }
    }

    /**
     * 进度拖拽：落指一次、之后只推进偏移、停手 [holdMs] 才抬手（真手指就是这么干的）。
     * 这样播放器的进度预览会一直挂着，不会像旧版那样 96ms 拖完就抬手、预览一闪就没。
     * 返回 false = 注入器不可用，调用方可以回落到别的通道。
     */
    fun scrub(context: Context, offsetPx: Float, yFrac: Float, holdMs: Long): Boolean {
        if (!budget.allow(SystemClock.uptimeMillis())) return false
        ctx = context
        refreshParams()
        worker.post {
            try {
                if (fingerDown || tracker.fingerDown) return@post  // 不和滚动抢同一根手指
                if (!scrubActive) {
                    if (!prepareScrub(context, yFrac)) return@post
                    scrubActive = true
                    scrubHoldMs = holdMs
                }
                scrubTargetX = (scrubX0 + offsetPx).coerceIn(scrubMinX, scrubMaxX)
                scrubLastMoveAt = SystemClock.uptimeMillis()
                ensureLoop()
            } catch (e: Throwable) {
                Log.w(TAG, "scrub failed", e)
            }
        }
        return true
    }

    private fun prepareScrub(context: Context, yFrac: Float): Boolean {
        refreshGeometryNow(context)
        val w = lastW
        val h = lastH
        if (w <= 0 || h <= 0) return false
        // 不贴左右边：贴边会撞系统侧滑返回手势
        scrubMinX = w * 0.08f
        scrubMaxX = w * 0.92f
        // 接力起的新一把从"推送方向的另一端"起手，把整屏宽度都用来快进；
        // 没有接力意图时仍是画面中心（竖屏拖进度条必须从当前位置附近，落点即跳转）
        val frac = nextStartXFrac ?: 0.5f
        nextStartXFrac = null
        scrubX0 = (w * frac).coerceIn(scrubMinX, scrubMaxX)
        scrubY = (h * yFrac).coerceIn(h * 0.08f, h * 0.94f)
        scrubDown = false
        scrubLastX = scrubX0
        scrubMovedTotal = 0f
        return true
    }

    /** 下一把拖拽的起手位置（屏幕宽度比例），只生效一次 */
    fun scrubNextStartAt(frac: Float) {
        nextStartXFrac = frac
    }

    /**
     * 落定这一把拖拽（抬手），把这次 seek 交给播放器自己收尾；[scrubNextStartAt] 决定下一把从哪起。
     * 只在已经拖出 touch slop 之后调用 —— 位移不足的抬手会被播放器当成点击（暂停）。
     */
    fun scrubEndPass(context: Context): Boolean {
        ctx = context
        if (!scrubDown && !scrubActive) return false
        if (scrubMovedTotal < MIN_SCRUB_PX) return false
        worker.post {
            val now = SystemClock.uptimeMillis()
            if (scrubDown) {
                if (send(now, now, MotionEvent.ACTION_UP, scrubLastX, scrubY)) {
                    scrubDown = false
                    if (debugLog) {
                        Log.i(TAG, "SCRUB 接力抬手 x=${"%.0f".format(scrubLastX)} moved=${"%.0f".format(scrubMovedTotal)}")
                    }
                }
            }
            scrubActive = false
        }
        return true
    }

    /** 进度拖拽会话的一帧。返回 false 表示循环可以停下 */
    private fun scrubFrame(now: Long): Boolean {
        if (!scrubDown) {
            scrubDown = true
            downTime = now
            send(now, now, MotionEvent.ACTION_DOWN, scrubX0, scrubY)
            return true
        }
        if (abs(scrubTargetX - scrubLastX) > 0.5f) {
            send(downTime, now, MotionEvent.ACTION_MOVE, scrubTargetX, scrubY)
            scrubMovedTotal += abs(scrubTargetX - scrubLastX)
            scrubLastX = scrubTargetX
            return true
        }
        if (now - scrubLastMoveAt < scrubHoldMs) return true   // 按住不放，让预览挂着

        // 该抬手了：但位移不足 touch slop 会被人判成点击，先补一段真移动
        if (scrubMovedTotal < MIN_SCRUB_PX) {
            val dir = if (scrubLastX >= scrubX0) 1f else -1f
            val nx = (scrubLastX + (MIN_SCRUB_PX - scrubMovedTotal) * dir).coerceIn(scrubMinX, scrubMaxX)
            if (nx != scrubLastX) {
                send(downTime, now, MotionEvent.ACTION_MOVE, nx, scrubY)
                scrubMovedTotal += abs(nx - scrubLastX)
                scrubLastX = nx
                return true
            }
        }
        if (!send(downTime, now, MotionEvent.ACTION_UP, scrubLastX, scrubY)) return true
        scrubDown = false
        scrubActive = false
        if (debugLog) Log.i(TAG, "SCRUB 结束 moved=${"%.0f".format(scrubMovedTotal)} x=${"%.0f".format(scrubLastX)}")
        // 再走一帧，让滚动那条链路自己决定要不要继续
        return true
    }

    private fun step(): Boolean {
        val context = ctx ?: return false
        val now = SystemClock.uptimeMillis()

        // 用户把手指放到屏上了 —— 立刻让路：先抬手、丢掉目标，这一帧就结束会话。
        // 只在派发入口拦是不够的：那根手指已经压在屏幕上，系统会把他的触摸当成
        // 第二根手指直接忽略（实测那一滑只剩 57px）。让路之后他这一次可能还是慢半拍，
        // 但下一滑立刻恢复正常，而不是像现在这样整段会话都被吞。
        if (xyz.nextalone.hyperzoomring.hook.ConfigSync.touchPaused()) {
            if (fingerDown || scrubDown || tracker.fingerDown) {
                if (debugLog) Log.i(TAG, "YIELD 用户上手，立即抬手让路")
                releaseFinger(now)
                return false
            }
        }

        // 进度拖拽优先：会话期间滚动不插手（两者不会同时被驱动）
        if (scrubActive) return scrubFrame(now)

        // 记账说"手指还按着"但运动模型已经抬手了 = 上一次 UP 没注入成功。
        // 系统里那根幽灵手指必须先补掉，否则他之后的每一次真触摸都是双指手势。
        if (fingerDown && !tracker.fingerDown) {
            if (send(now, now, MotionEvent.ACTION_UP, curX, tracker.fingerY)) {
                fingerDown = false
            } else {
                trippedOnFailure(now)
                return true
            }
        }

        if (!fingerDown) updateGeometry(context)

        val res = tracker.frame()
        // 同一帧里的多个 op 必须用**不同**的 eventTime：
        // 之前 MOVE 和 UP 共用 now，应用侧算"两次事件之间的位移"得到 0，
        // DOWN->UP 就被判成点击（微博上表现为"没碰屏幕却打开了一条"）。
        var opIndex = 0
        for (op in res.ops) {
            val at = now + opIndex
            opIndex++
            when (op) {
                is ScrollTracker.Op.Down -> {
                    downTime = now
                    if (debugLog) {
                        Log.i(TAG, "SCR down y=${"%.0f".format(op.y)} err=${"%.0f".format(tracker.errorPx)} " +
                            "tgt=${"%.0f".format(tracker.targetPx)} band=${tracker.topPx.toInt()}..${tracker.bottomPx.toInt()}")
                    }
                    fingerDown = send(at, at, MotionEvent.ACTION_DOWN, curX, op.y)
                    if (!fingerDown) {
                        // DOWN 没进系统：模型已经记账成"按着"，得退回去，
                        // 否则后面的 MOVE 全被跳过、已滚量却照涨，误差就永久失真了
                        tracker.abort()
                        return true
                    }
                }
                is ScrollTracker.Op.Move -> {
                    if (!fingerDown) continue
                    send(downTime, at, MotionEvent.ACTION_MOVE, curX, op.y)
                }
                is ScrollTracker.Op.Up -> {
                    if (!fingerDown) continue
                    if (debugLog) {
                        Log.i(TAG, "SCR up y=${"%.0f".format(op.y)} moved=${"%.0f".format(tracker.movedSinceDown)} " +
                            "err=${"%.0f".format(tracker.errorPx)} tgt=${"%.0f".format(tracker.targetPx)}")
                    }
                    // 抬手失败就保持"按着"，下一帧由上面那段补抬：宁可多试一帧，
                    // 也不能让记账说手已经抬了、系统里却还压着一根手指
                    if (send(downTime, at, MotionEvent.ACTION_UP, curX, op.y)) fingerDown = false
                }
            }
        }
        if (debugLog && !res.keepGoing && !fingerDown && abs(tracker.errorPx) > 0.5f) {
            // 目标动了但还不够落指门槛：这就是"转了没反应"的那种帧，得能看见
            Log.i(TAG, "SCR hold err=${"%.0f".format(tracker.errorPx)} tgt=${"%.0f".format(tracker.targetPx)}")
        }
        return res.keepGoing
    }

    /** 手指不在屏幕上时才改几何：横竖屏切换后落点会重算 */
    private fun updateGeometry(context: Context) {
        val now = System.currentTimeMillis()
        if (now - geometryAt < GEOMETRY_TTL_MS) return
        refreshGeometryNow(context)
    }

    /** 强制取一次屏幕尺寸（拖进度条要按当前横竖屏算落点） */
    private fun refreshGeometryNow(context: Context) {
        val now = System.currentTimeMillis()
        if (now - geometryAt < 200L && lastW > 0) return
        geometryAt = now
        val (w, h) = screenSize(context)
        if (w <= 0 || h <= 0) return
        lastW = w
        lastH = h
        tracker.topPx = h * dragTop
        tracker.bottomPx = h * dragBottom
        curX = w / 2f
        tracker.clampFinger()
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        return try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val d: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d?.getRealMetrics(m)
            if (m.widthPixels > 0 && m.heightPixels > 0) m.widthPixels to m.heightPixels
            else fallback(context)
        } catch (e: Throwable) {
            fallback(context)
        }
    }

    private fun fallback(context: Context): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun send(dt: Long, et: Long, action: Int, x: Float, y: Float): Boolean {
        val context = ctx ?: return false
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 0.12f
            }
        )
        val ev = MotionEvent.obtain(
            dt, et, action, 1, props, coords,
            0, 0, 1f, 1f, -1, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        val ok = try {
            InjectBridge.inject(context, ev)
        } catch (e: Throwable) {
            Log.w(TAG, "inject threw", e)
            false
        } finally {
            ev.recycle()
        }
        val now = SystemClock.uptimeMillis()
        if (ok) {
            budget.onSuccess(now)
        } else if (budget.onFailure(now) && !releasing) {
            // 连续失败打到冷却：先把手指交出去再停
            releasing = true
            Log.w(TAG, "注入连续失败达上限，进入冷却并强制抬手")
            releaseFinger(now)
            releasing = false
        }
        return ok
    }

    private fun refreshParams() {
        val now = System.currentTimeMillis()
        if (now - paramAt < PARAM_TTL_MS) return
        paramAt = now
        val context = ctx ?: return
        try {
            val r = context.contentResolver
            gain = Settings.Global.getFloat(r, "hzr_scroll_gain", gain)
            tracker.ease = Settings.Global.getFloat(r, "hzr_scroll_ease", tracker.ease)
            tracker.maxStepPx =
                Settings.Global.getFloat(r, "hzr_scroll_max_step", tracker.maxStepPx)
            dragTop = Settings.Global.getFloat(r, "hzr_drag_top", DRAG_TOP)
            dragBottom = Settings.Global.getFloat(r, "hzr_drag_bottom", DRAG_BOTTOM)
            // 抽搐回归用热调：待命帧数 + 抬手前零速帧数（16ms/帧）
            tracker.holdFrames = Settings.Global.getInt(r, "hzr_scroll_hold", tracker.holdFrames)
            tracker.dwellFrames = Settings.Global.getInt(r, "hzr_scroll_dwell", tracker.dwellFrames)
            // 撞边换指是否就地换（1）还是先压零速（0）—— "卡手"的主开关
            tracker.edgeInstant = Settings.Global.getInt(r, "hzr_scroll_edge", 1) == 1
            debugLog = Settings.Global.getInt(r, "hzr_debug", 0) == 1
        } catch (e: Throwable) {
            Log.d(TAG, "scroll params refresh failed", e)
        }
    }
}

/**
 * injectInputEvent 与 INJECT_ENFORCE_NONE 都是 @hide（只对系统签名开放），公开 SDK 里没有，
 * 所以走反射。本模块跑在 system_server 内，反射不受隐藏 API 限制影响。
 * 优先 InputManager.injectInputEvent(event, mode)，退化到 InputManagerGlobal 的三参版本。
 */
internal object InjectBridge {
    private const val TAG = "HyperZoomRing"

    private var target: Any? = null
    private var method: java.lang.reflect.Method? = null
    private var mode: Int = 1
    private var resolved = false
    private var threeArg = false
    @Volatile private var lastResult = "none"

    @Synchronized
    private fun resolve(context: Context): Boolean {
        if (resolved) return method != null
        resolved = true
        try {
            mode = InputEvent::class.java.getField("INJECT_ENFORCE_NONE").getInt(null)
        } catch (_: Throwable) { /* 用兜底值 */ }

        try {
            val im = context.getSystemService(Context.INPUT_SERVICE)
            if (im != null) {
                val m = im.javaClass.methods.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterCount == 2
                }
                if (m != null) { target = im; method = m; threeArg = false; return true }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "InputManager.injectInputEvent not usable", e)
        }

        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val inst = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val m = cls.methods.firstOrNull { it.name == "injectInputEvent" && it.parameterCount == 3 }
                ?: cls.declaredMethods.firstOrNull { it.name == "injectInputEvent" && it.parameterCount == 3 }
            if (inst != null && m != null) {
                m.isAccessible = true
                target = inst; method = m; threeArg = true; return true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "InputManagerGlobal fallback not usable", e)
        }
        Log.e(TAG, "no injection path found; scroll disabled")
        return false
    }

    fun inject(context: Context, ev: InputEvent): Boolean {
        if (!resolve(context)) return false
        val m = method ?: return false
        val t = target ?: return false
        return try {
            val r = if (threeArg) m.invoke(t, ev, mode, -1) else m.invoke(t, ev, mode)
            lastResult = if (r == null) "null" else "${r.javaClass.simpleName}=$r"
            when (r) {
                is Boolean -> r
                // Android 14+ 的三参版返回 InjectionResult 整型，0 才是成功。
                // 这一支以前一律按 `r == true` 判，会把每一次注入都记成失败 ——
                // 旧版那个"累计 8 次就永久停用"的闸门就是这么被顶满的。
                is Int -> r == 0
                // 读不懂的返回类型不能当失败：误判会累积成"表冠突然再也不动"
                else -> true
            }
        } catch (e: Throwable) {
            lastResult = "throw:${e.javaClass.simpleName}"
            Log.w(TAG, "inject invoke failed", e)
            false
        }
    }

    fun diagnostics(): String =
        "resolved=$resolved path=${if (threeArg) "InputManagerGlobal3" else "InputManager2"} mode=$mode last=$lastResult"
}
