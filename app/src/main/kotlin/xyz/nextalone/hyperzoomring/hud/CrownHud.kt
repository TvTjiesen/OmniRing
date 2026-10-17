package xyz.nextalone.hyperzoomring.hud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 进度环的可视化：转的时候在屏幕上方挂一个小胶囊，显示"要跳到哪 / 相对现在多少秒"。
 *
 * 为什么要它：没有可视反馈时，表冠求对就是盲操作 —— 他原话"转动的时候也不知道快进到哪了
 * 直接就快进了"。胶囊上同时给绝对时间点和相对偏移，看一眼就知道会不会跳过 wanted 位置。
 *
 * 本模块跑在 system_server 里，所以浮窗只能自己加窗口。风险按"手机安全"的要求收敛：
 *  - 一切在独立 HandlerThread 上做，绝不占 system_server 主线程；
 *  - 任何异常（权限、token、布局）直接把整个 HUD 永久关掉，不重试、不刷屏；
 *  - 自动隐藏：静止 700ms 收起，另外有硬上限，绝不可能留一个擦不掉的浮窗；
 *  - 热开关 hzr_hud=0 立刻关掉，不用重新编译；
 *  - 设置界面里那个总开关关掉就完全不走到这里。
 */
object CrownHud {
    private const val TAG = "HyperZoomRing"
    private const val AUTO_HIDE_MS = 700L
    private const val HARD_CAP_MS = 6000L
    private const val MAX_LINES = 1

    @Volatile private var disabled = false
    private val worker: Handler by lazy {
        HandlerThread("HyperZoomRing-Hud").apply { start() }.looper.let { Handler(it) }
    }

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var shownAt = 0L
    private var pendingHide: Runnable? = null

    /**
     * @param offsetMs 相对本次会话基准的偏移（带符号毫秒）
     * @param targetPosMs 即将 seek 到的绝对位置
     * @param durationMs 总时长，<=0 表示未知
     */
    fun showOffset(context: Context, offsetMs: Float, targetPosMs: Long, durationMs: Long) {
        if (disabled) return
        val text = buildString {
            append(if (offsetMs >= 0) "⏩ +" else "⏪ −")
            append(fmt(abs(offsetMs).toLong()))
            append("  →  ")
            append(fmt(targetPosMs))
            if (durationMs > 0) {
                append(" / ")
                append(fmt(durationMs))
            }
        }
        show(context, text)
    }

    /** 会话结束 */
    fun hideNow(context: Context) {
        if (disabled) return
        worker.post {
            try { remove() } catch (e: Throwable) { giveUp(e) }
        }
    }

    private fun show(context: Context, text: String) {
        worker.post {
            if (disabled) return@post
            try {
                val now = System.currentTimeMillis()
                val v = view ?: create(context) ?: run {
                    disabled = true
                    return@post
                }
                v.text = text
                val prev = pendingHide
                if (prev != null) {
                    worker.removeCallbacks(prev)
                    pendingHide = null
                }
                val hard = now - shownAt > HARD_CAP_MS
                if (hard || !isAttached) {
                    remove()
                    add(context)
                    shownAt = now
                }
                val hide = Runnable { try { remove() } catch (e: Throwable) { giveUp(e) } }
                pendingHide = hide
                worker.postDelayed(hide, AUTO_HIDE_MS)
            } catch (e: Throwable) {
                giveUp(e)
            }
        }
    }

    private var isAttached = false
    private var density = 2.5f

    private fun create(context: Context): TextView? {
        val wmService = try {
            context.getSystemService(WindowManager::class.java)
        } catch (e: Throwable) {
            Log.w(TAG, "no WindowManager", e)
            return null
        } ?: return null
        wm = wmService
        density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 2.5f
        return TextView(context).apply {
            maxLines = MAX_LINES
            setTextColor(Color.WHITE)
            // textSize 单位是 sp，不要再乘 density
            textSize = 14f
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f * density
                setColor(Color.argb(210, 0, 0, 0))
            }
            elevation = 8f * density
        }
    }

    private fun params(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (110 * density).toInt()
            windowAnimations = 0
        }

    private fun add(context: Context) {
        val v = view ?: return
        val w = wm ?: return
        w.addView(v, params())
        isAttached = true
    }

    private fun remove() {
        val v = view ?: return
        if (!isAttached) return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) { /* 没挂上就算移除失败也不用管 */ }
        isAttached = false
    }

    private fun giveUp(e: Throwable) {
        disabled = true
        isAttached = false
        Log.w(TAG, "HUD disabled (scroll/seek 不受影响)", e)
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
